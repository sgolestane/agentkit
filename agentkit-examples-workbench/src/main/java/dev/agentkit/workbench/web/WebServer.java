package dev.agentkit.workbench.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.Quoted;
import dev.agentkit.workbench.connector.Alm;
import dev.agentkit.workbench.connector.JiraClient;
import dev.agentkit.workbench.domain.Approval;
import dev.agentkit.workbench.domain.AutomationRule;
import dev.agentkit.workbench.domain.OperatorAction;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.domain.Ticket;
import dev.agentkit.workbench.domain.TriageVerdict;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.Triage;
import dev.agentkit.workbench.runtime.Workbench;
import dev.agentkit.workbench.store.WorkbenchStore;
import dev.agentkit.workbench.tools.ToolCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The web tier: a JSON API over the workbench plus the single page that consumes it.
 *
 * <p>The JDK's own HTTP server, so the demo has no framework to install. The API's shape is
 * the part meant to be read: the ticket inbox, a run, a pending approval and a learned
 * lesson are all resources an operator can list, inspect and act on — the difference
 * between an agent's workbench and a chatbot in front of a ticket queue. Live updates ride
 * the run event stream over server-sent events; the same append-only log that answers
 * "what happened" later is what paints the console now.
 *
 * <p>Everything Jira or model-shaped degrades stated rather than broken: with no ALM
 * connected or no model configured, the console boots, says exactly what is missing, and
 * refuses the endpoints that need it with the same sentence.
 */
public final class WebServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WebServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final WorkbenchStore store;
    private final String tenantId;
    private final String model;
    private final Alm alm;                 // null when unconfigured
    private final Workbench workbench;     // null when alm or model is missing
    private final Triage triage;           // null when alm or model is missing
    private final Learnings learnings;
    private final dev.agentkit.workbench.capture.EvalCaptures captures;  // null when alm is
    private final List<SseClient> clients = new CopyOnWriteArrayList<>();
    private final AutoCloseable subscription;

    private record SseClient(HttpExchange exchange, OutputStream out) {}

    public WebServer(int port, WorkbenchStore store, String tenantId, String model, Alm alm,
            Workbench workbench, Triage triage, Learnings learnings,
            dev.agentkit.workbench.capture.EvalCaptures captures) throws IOException {
        this.store = Objects.requireNonNull(store, "store");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.model = model == null ? "" : model;
        this.alm = alm;
        this.workbench = workbench;
        this.triage = triage;
        this.learnings = Objects.requireNonNull(learnings, "learnings");
        this.captures = captures;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", this::serveUi);
        server.createContext("/api/", this::route);
        this.subscription = store.subscribe(this::broadcast);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        try {
            subscription.close();
        } catch (Exception ignored) {
            // Closing a subscription that is already gone is not a failure worth reporting.
        }
        server.stop(0);
    }

    // --- routing ------------------------------------------------------------------

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring("/api".length());
        String method = exchange.getRequestMethod();
        try {
            if (path.equals("/events")) {
                streamEvents(exchange);
                return;
            }
            Object body = switch (method + ' ' + strip(path)) {
                case "GET /overview" -> overview();
                case "GET /tickets" -> ticketList();
                case "GET /runs" -> store.runs(tenantId).stream()
                        .map(WebServer::runJson).toList();
                case "GET /approvals" -> store.approvals(tenantId).stream()
                        .map(WebServer::approvalJson).toList();
                case "GET /rules" -> store.rules(tenantId).stream()
                        .map(this::ruleJson).toList();
                case "GET /gaps" -> store.gaps(tenantId).stream()
                        .map(gap -> Map.of("id", gap.id(), "ticketKey", gap.ticketKey(),
                                "runId", gap.runId(), "capability", gap.capability(),
                                "description", gap.description(),
                                "reportedAt", gap.reportedAt().toString()))
                        .toList();
                case "GET /learnings" -> learnings.recall();
                case "GET /corrections" -> workbench == null ? List.of()
                        : workbench.standingRefusals().stream()
                                .map(correction -> Map.of(
                                        "capability", correction.area(),
                                        "by", correction.decidedBy(),
                                        "note", correction.note()))
                                .toList();
                case "POST /corrections/lift" -> liftCorrection(exchange);
                case "GET /trusted" -> workbench == null ? List.of()
                        : workbench.trustedCapabilities().stream()
                                .map(granted -> Map.of(
                                        "capability", granted.capability(),
                                        "by", granted.by(),
                                        "at", granted.at()))
                                .toList();
                case "POST /trusted/revoke" -> revokeTrust(exchange);
                case "GET /tools" -> ToolCatalog.policies().stream()
                        .map(policy -> Map.of("name", policy.name(),
                                "capability", policy.capability(),
                                "connector", policy.connector(),
                                "risk", policy.baselineRisk().name(),
                                "reversible", policy.reversible(),
                                "idempotent", policy.idempotent()))
                        .toList();
                case "POST /triage/run" -> triageSweep();
                case "POST /rules" -> createRule(exchange);
                case "POST /bulk/execute" -> bulkExecute(exchange);
                default -> dynamic(method, strip(path), exchange);
            };
            if (body == null) {
                send(exchange, 404, Map.of("error", "No such endpoint: " + method + ' ' + path));
                return;
            }
            send(exchange, 200, body);
        } catch (Unavailable stated) {
            send(exchange, 409, Map.of("error", stated.getMessage()));
        } catch (IllegalArgumentException stated) {
            // A stated refusal from the workbench — an unknown ticket, a done one — is an
            // answer, not a fault: the sentence goes back as-is rather than as a 500.
            send(exchange, 409, Map.of("error", String.valueOf(stated.getMessage())));
        } catch (RuntimeException | IOException failure) {
            log.warn("{} {} failed", Quoted.of(method), Quoted.of(Cut.to(path, 512)),
                    Quoted.failure(failure));
            send(exchange, 500, Map.of("error", String.valueOf(failure.getMessage())));
        }
    }

    /** Routes with an id in them, which a switch over literals cannot express. */
    private Object dynamic(String method, String path, HttpExchange exchange) throws IOException {
        List<String> parts = new ArrayList<>(List.of(path.split("/")));
        parts.removeIf(String::isEmpty);

        if (parts.size() == 2 && parts.get(0).equals("tickets") && method.equals("GET")) {
            return requireAlm().ticket(decode(parts.get(1)))
                    .map(this::ticketDetailJson)
                    .orElse(null);
        }
        if (parts.size() == 3 && parts.get(0).equals("tickets") && method.equals("POST")) {
            String key = decode(parts.get(1));
            return switch (parts.get(2)) {
                case "preview" -> outcomeJson(requireWorkbench().preview(key));
                case "execute" -> outcomeJson(requireWorkbench()
                        .execute(key, Run.Trigger.OPERATOR));
                // The operator's own hands. Only the ALM is required — a person working a
                // ticket needs no model, and the workbench should not stop being useful
                // because none is configured.
                case "comment" -> operatorComment(key, exchange);
                case "transition" -> operatorTransition(key, exchange);
                // One ticket's verdict, formed again. A whole-inbox sweep is the wrong
                // instrument when one row went out of date — usually because the operator
                // just changed that very ticket.
                case "triage" -> {
                    if (triage == null) {
                        throw unavailable();
                    }
                    yield requireAlm().ticket(key)
                            .flatMap(triage::triage)
                            .map(entry -> triageJson(entry.verdict()))
                            .orElse(null);
                }
                default -> null;
            };
        }
        if (parts.size() == 3 && parts.get(0).equals("runs") && parts.get(2).equals("capture")
                && method.equals("POST")) {
            if (captures == null) {
                throw new Unavailable("Jira is not connected, so there is no live world to "
                        + "capture from.");
            }
            try {
                java.nio.file.Path file = captures.capture(decode(parts.get(1)));
                return Map.of("captured", file.toString());
            } catch (IllegalArgumentException unteachable) {
                throw new Unavailable(String.valueOf(unteachable.getMessage()));
            }
        }
        if (parts.size() == 2 && parts.get(0).equals("runs") && method.equals("GET")) {
            Optional<Run> run = store.run(tenantId, decode(parts.get(1)));
            if (run.isEmpty()) {
                return null;
            }
            Map<String, Object> detail = new LinkedHashMap<>(runJson(run.get()));
            detail.put("events", store.events(run.get().id()).stream()
                    .map(WebServer::eventJson).toList());
            return detail;
        }
        if (parts.size() == 3 && parts.get(0).equals("approvals") && method.equals("POST")) {
            String id = decode(parts.get(1));
            Map<String, Object> request = readJson(exchange);
            String who = String.valueOf(request.getOrDefault("by", "operator"));
            return switch (parts.get(2)) {
                case "approve" -> requireWorkbench()
                        .resume(id, who, true, String.valueOf(request.getOrDefault("note", "")),
                                Boolean.TRUE.equals(request.get("standing")))
                        .map(this::outcomeJson).orElse(null);
                case "reject" -> requireWorkbench()
                        .resume(id, who, false, String.valueOf(request.getOrDefault("note", "")),
                                Boolean.TRUE.equals(request.get("standing")))
                        .map(this::outcomeJson).orElse(null);
                case "answer" -> requireWorkbench()
                        .answer(id, who, String.valueOf(request.getOrDefault("answer", "")))
                        .map(this::outcomeJson).orElse(null);
                default -> null;
            };
        }
        if (parts.size() == 3 && parts.get(0).equals("rules") && parts.get(2).equals("toggle")
                && method.equals("POST")) {
            return store.rule(tenantId, decode(parts.get(1)))
                    .map(rule -> ruleJson(store.save(rule.toggled(!rule.enabled()))))
                    .orElse(null);
        }
        return null;
    }

    // --- handlers -----------------------------------------------------------------

    private Map<String, Object> overview() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("model", model);
        Map<String, Object> almJson = new LinkedHashMap<>();
        almJson.put("configured", alm != null);
        if (alm != null) {
            almJson.put("provider", alm.name());
            if (alm instanceof JiraClient jira) {
                almJson.put("baseUrl", jira.baseUrl());
            }
            try {
                Alm.Me me = alm.myself();
                almJson.put("signedInAs", me.displayName());
            } catch (RuntimeException unreachable) {
                almJson.put("error", String.valueOf(unreachable.getMessage()));
            }
        }
        json.put("alm", almJson);
        json.put("ready", workbench != null);
        json.put("pendingApprovals", store.approvals(tenantId).stream()
                .filter(a -> a.state() == Approval.State.PENDING).count());
        json.put("rules", store.rules(tenantId).size());
        json.put("gaps", store.gaps(tenantId).size());
        json.put("learnings", learnings.recall().size());
        return json;
    }

    private List<Map<String, Object>> ticketList() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Ticket ticket : requireAlm().inbox(50)) {
            Map<String, Object> row = new LinkedHashMap<>(ticketJson(ticket));
            store.triage(tenantId, ticket.key()).ifPresent(entry -> {
                row.put("triage", triageJson(entry.verdict()));
                row.put("triageStale", !entry.ticketUpdatedAt().equals(ticket.updatedAt())
                        || entry.knowledgeFingerprint() != learnings.fingerprint());
                row.put("automated", store.automated(tenantId, entry.verdict().category()));
            });
            store.runsForTicket(tenantId, ticket.key()).stream().findFirst()
                    .ifPresent(run -> {
                        row.put("lastRun", run.id());
                        row.put("lastRunStatus", run.status().name());
                        row.put("lastRunMode", run.mode().name());
                    });
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> ticketDetailJson(Ticket ticket) {
        Map<String, Object> json = new LinkedHashMap<>(ticketJson(ticket));
        json.put("comments", requireAlm().comments(ticket.key()).stream()
                .map(comment -> Map.of("author", comment.author(), "body", comment.body(),
                        "at", comment.at().toString()))
                .toList());
        store.triage(tenantId, ticket.key()).ifPresent(entry -> {
            json.put("triage", triageJson(entry.verdict()));
            json.put("triageStale", !entry.ticketUpdatedAt().equals(ticket.updatedAt())
                    || entry.knowledgeFingerprint() != learnings.fingerprint());
        });
        json.put("runs", store.runsForTicket(tenantId, ticket.key()).stream()
                .map(WebServer::runJson).toList());
        // What the ALM will currently accept, so the operator is offered the moves that
        // exist rather than a free-text box that fails on the far side.
        json.put("transitions", requireAlm().transitions(ticket.key()).stream()
                .map(Alm.Transition::toName).toList());
        json.put("operatorActions", store.operatorActions(tenantId, ticket.key()).stream()
                .map(WebServer::operatorActionJson).toList());
        return json;
    }

    private static Map<String, Object> operatorActionJson(OperatorAction action) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", action.id());
        json.put("ticketKey", action.ticketKey());
        json.put("kind", action.kind().name());
        json.put("detail", action.detail());
        json.put("by", action.by());
        json.put("at", action.at().toString());
        return json;
    }

    private Map<String, Object> triageSweep() {
        if (triage == null) {
            throw unavailable();
        }
        var entries = triage.sweep(50);
        long canHandle = entries.stream().filter(e -> e.verdict().canHandle()).count();
        return Map.of("triaged", entries.size(), "canHandle", canHandle);
    }

    private Map<String, Object> createRule(HttpExchange exchange) throws IOException {
        Map<String, Object> request = readJson(exchange);
        String category = String.valueOf(request.getOrDefault("category", "")).strip();
        if (category.isEmpty()) {
            return Map.of("error", "A rule needs a 'category'.");
        }
        AutomationRule rule = store.save(new AutomationRule(WorkbenchStore.Ids.next("rule"),
                tenantId, dev.agentkit.core.prompt.Spotlight.name(category), true,
                String.valueOf(request.getOrDefault("by", "operator")), Instant.now()));
        return ruleJson(rule);
    }

    /** Starts asking about a capability again. */
    private Map<String, Object> revokeTrust(HttpExchange exchange) throws IOException {
        Map<String, Object> request = readJson(exchange);
        String capability = String.valueOf(request.getOrDefault("capability", "")).strip();
        if (capability.isEmpty()) {
            throw new Unavailable("Name the capability to be asked about again.");
        }
        return Map.of("capability", capability,
                "revoked", requireWorkbench().untrust(capability));
    }

    /** The operator's own comment, written to the ALM and recorded as theirs. */
    private Map<String, Object> operatorComment(String key, HttpExchange exchange)
            throws IOException {
        Map<String, Object> request = readJson(exchange);
        String body = String.valueOf(request.getOrDefault("body", "")).strip();
        if (body.isEmpty()) {
            throw new Unavailable("A comment needs something to say.");
        }
        String who = String.valueOf(request.getOrDefault("by", "operator"));
        requireAlm().addComment(key, body);
        return recorded(new OperatorAction(WorkbenchStore.Ids.next("act"), tenantId, key,
                OperatorAction.Kind.COMMENT, body, who, Instant.now()),
                Run.Event.Type.OPERATOR_COMMENTED);
    }

    /** The operator moving the ticket themselves — including resolving it. */
    private Map<String, Object> operatorTransition(String key, HttpExchange exchange)
            throws IOException {
        Map<String, Object> request = readJson(exchange);
        String transition = String.valueOf(request.getOrDefault("transition", "")).strip();
        if (transition.isEmpty()) {
            throw new Unavailable("Name the transition to take.");
        }
        String who = String.valueOf(request.getOrDefault("by", "operator"));
        requireAlm().transition(key, transition);
        return recorded(new OperatorAction(WorkbenchStore.Ids.next("act"), tenantId, key,
                OperatorAction.Kind.TRANSITION, transition, who, Instant.now()),
                Run.Event.Type.OPERATOR_TRANSITIONED);
    }

    /**
     * Files what the person did and puts it on the live feed.
     *
     * <p>Keyed on the action's own id rather than a run's: the event log records what
     * happened, and this did not happen inside a run. A run's own timeline query asks for
     * its id and so never picks these up.
     */
    private Map<String, Object> recorded(OperatorAction action, Run.Event.Type type) {
        store.save(action);
        store.append(action.id(), type,
                Map.of("ticketKey", action.ticketKey(), "by", action.by()));
        return operatorActionJson(action);
    }

    private Map<String, Object> liftCorrection(HttpExchange exchange) throws IOException {
        Map<String, Object> request = readJson(exchange);
        String capability = String.valueOf(request.getOrDefault("capability", "")).strip();
        if (capability.isEmpty()) {
            throw new Unavailable("Lifting needs a 'capability'.");
        }
        return requireWorkbench().liftStandingRefusal(capability)
                .map(lifted -> Map.<String, Object>of("lifted", lifted,
                        "capability", capability))
                .orElseThrow(() -> new Unavailable("This deployment keeps no correction "
                        + "book."));
    }

    private List<Map<String, Object>> bulkExecute(HttpExchange exchange) throws IOException {
        Map<String, Object> request = readJson(exchange);
        Object raw = request.get("keys");
        if (!(raw instanceof List<?> keys) || keys.isEmpty()) {
            throw new Unavailable("Bulk execution needs a non-empty 'keys' list.");
        }
        // Sequential and bounded on purpose: this is human-approved bulk execution, and 25
        // agent runs racing one Jira project helps nobody debug the first failure.
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (Object key : keys.stream().limit(25).toList()) {
            try {
                outcomes.add(outcomeJson(requireWorkbench()
                        .execute(String.valueOf(key), Run.Trigger.BULK)));
            } catch (RuntimeException failure) {
                outcomes.add(Map.of("ticketKey", String.valueOf(key),
                        "error", String.valueOf(failure.getMessage())));
            }
        }
        return outcomes;
    }

    private Map<String, Object> outcomeJson(Workbench.Outcome outcome) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("runId", outcome.run().id());
        json.put("ticketKey", outcome.run().ticketKey());
        json.put("mode", outcome.run().mode().name());
        json.put("status", outcome.run().status().name());
        json.put("parked", outcome.parked());
        json.put("output", outcome.output());
        json.put("summary", outcome.run().summary() == null ? "" : outcome.run().summary());
        outcome.pending().ifPresent(pending -> json.put("pending", approvalJson(pending)));
        return json;
    }

    // --- degradation --------------------------------------------------------------

    /** A stated limitation, not a fault: rendered as a 409 with the sentence. */
    private static final class Unavailable extends RuntimeException {
        Unavailable(String message) {
            super(message);
        }
    }

    private Alm requireAlm() {
        if (alm == null) {
            throw new Unavailable("Jira is not connected. Set JIRA_BASE_URL, JIRA_EMAIL and "
                    + "JIRA_API_TOKEN, then restart.");
        }
        return alm;
    }

    private Workbench requireWorkbench() {
        requireAlm();
        if (workbench == null) {
            throw unavailable();
        }
        return workbench;
    }

    private Unavailable unavailable() {
        return new Unavailable("No model is configured. Set WORKBENCH_LLM=anthropic (plus "
                + "ANTHROPIC_API_KEY) or WORKBENCH_LLM=openrouter (plus OPENROUTER_API_KEY and "
                + "WORKBENCH_MODEL), then restart.");
    }

    // --- server-sent events -------------------------------------------------------

    private void streamEvents(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        clients.add(new SseClient(exchange, exchange.getResponseBody()));
    }

    private void broadcast(Run.Event event) {
        byte[] payload;
        try {
            payload = ("data: " + JSON.writeValueAsString(eventJson(event)) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8);
        } catch (IOException impossible) {
            return;
        }
        for (SseClient client : clients) {
            try {
                client.out().write(payload);
                client.out().flush();
            } catch (IOException disconnected) {
                clients.remove(client);
                client.exchange().close();
            }
        }
    }

    // --- rendering ----------------------------------------------------------------

    // Values written by whoever filed the ticket stay raw in the JSON on purpose: the
    // transport is not the sink. ui/index.html renders them through `esc` into text nodes
    // or quoted attributes, and the tools fence the same fields for the model.

    private static Map<String, Object> ticketJson(Ticket ticket) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("key", ticket.key());
        json.put("provider", ticket.provider());
        json.put("summary", ticket.summary());
        json.put("description", ticket.description());
        json.put("status", ticket.status());
        json.put("statusCategory", ticket.statusCategory().name());
        json.put("priority", ticket.priority());
        json.put("assignee", ticket.assignee());
        json.put("reporter", ticket.reporter());
        json.put("createdAt", ticket.createdAt().toString());
        json.put("updatedAt", ticket.updatedAt().toString());
        return json;
    }

    private static Map<String, Object> triageJson(TriageVerdict verdict) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("canHandle", verdict.canHandle());
        json.put("category", verdict.category());
        json.put("confidence", verdict.confidence());
        json.put("plan", verdict.plan());
        json.put("missing", verdict.missing());
        return json;
    }

    private static Map<String, Object> runJson(Run run) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", run.id());
        json.put("ticketKey", run.ticketKey());
        json.put("mode", run.mode().name());
        json.put("trigger", run.trigger().name());
        json.put("status", run.status().name());
        json.put("goal", run.goal());
        json.put("createdAt", run.createdAt().toString());
        json.put("startedAt", String.valueOf(run.startedAt()));
        json.put("completedAt", String.valueOf(run.completedAt()));
        json.put("summary", run.summary() == null ? "" : run.summary());
        return json;
    }

    private static Map<String, Object> eventJson(Run.Event event) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("runId", event.runId());
        json.put("sequence", event.sequence());
        json.put("type", event.type().name());
        json.put("at", event.at().toString());
        json.put("detail", event.detail());
        return json;
    }

    private static Map<String, Object> approvalJson(Approval approval) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", approval.id());
        json.put("runId", approval.runId());
        json.put("ticketKey", approval.ticketKey());
        json.put("kind", approval.kind().name());
        json.put("tool", approval.toolName());
        // The family the decision generalises to — what "stop asking me about this" and
        // "keep refusing this" are both keyed on, so the card can name it.
        json.put("capability",
                ToolCatalog.policyOrUnknown(approval.toolName()).capability());
        json.put("arguments", approval.arguments());
        json.put("question", approval.question());
        json.put("risk", approval.risk().name());
        json.put("reason", approval.reason());
        json.put("effect", approval.effect());
        json.put("reversible", approval.reversible());
        json.put("evidence", approval.evidence());
        json.put("state", approval.state().name());
        json.put("requestedAt", approval.requestedAt().toString());
        json.put("decidedBy", approval.decidedBy());
        return json;
    }

    /**
     * A rule, with what it actually covers and what it has done.
     *
     * <p>The category alone is a word: pausing is a consequential decision and the operator
     * could not see what they were pausing. So the row carries its reach — the tickets
     * currently triaged into this category — and its record: the runs it started, how they
     * ended, and when it last acted.
     */
    private Map<String, Object> ruleJson(AutomationRule rule) {
        List<WorkbenchStore.TriageEntry> inCategory = store.triageEntries(tenantId).stream()
                .filter(entry -> rule.category().equalsIgnoreCase(entry.verdict().category()))
                .toList();
        // What the rule would actually work, which is not the whole category: the autopilot
        // takes only tickets triage says the agent can handle. A ticket of this kind that needs
        // knowledge nobody has written down yet sits in the category and waits for a
        // person, so counting it here would overstate the switch the operator is throwing.
        List<String> covers = inCategory.stream()
                .filter(entry -> entry.verdict().canHandle())
                .map(WorkbenchStore.TriageEntry::ticketKey)
                .toList();
        long notYet = inCategory.size() - covers.size();
        List<Run> started = store.runs(tenantId).stream()
                .filter(run -> run.trigger() == Run.Trigger.RULE)
                .filter(run -> store.triage(tenantId, run.ticketKey())
                        .map(entry -> rule.category()
                                .equalsIgnoreCase(entry.verdict().category()))
                        .orElse(false))
                .toList();

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", rule.id());
        json.put("category", rule.category());
        json.put("enabled", rule.enabled());
        json.put("createdBy", rule.createdBy());
        json.put("createdAt", rule.createdAt().toString());
        json.put("covers", covers);
        json.put("notYetHandleable", notYet);
        json.put("handled", started.stream()
                .filter(run -> run.status() == Run.Status.COMPLETED).count());
        json.put("waiting", started.stream()
                .filter(run -> run.status() == Run.Status.WAITING_FOR_HUMAN).count());
        json.put("runs", started.size());
        json.put("lastActedAt", started.stream()
                .map(Run::createdAt)
                .max(java.util.Comparator.naturalOrder())
                .map(Object::toString).orElse(null));
        return json;
    }

    // --- plumbing -----------------------------------------------------------------

    private void serveUi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) {
            send(exchange, 404, Map.of("error", "Not found"));
            return;
        }
        byte[] page;
        try (InputStream in = WebServer.class.getResourceAsStream("/ui/index.html")) {
            if (in == null) {
                send(exchange, 500, Map.of("error", "UI resource missing"));
                return;
            }
            page = in.readAllBytes();
        }
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, page.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(page);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (body.length == 0) {
            return Map.of();
        }
        return JSON.readValue(body, Map.class);
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static String strip(String path) {
        return path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
