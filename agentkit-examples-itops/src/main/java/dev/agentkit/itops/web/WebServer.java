package dev.agentkit.itops.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.Quoted;
import dev.agentkit.itops.connector.TicketProvider;
import dev.agentkit.itops.domain.ApprovalRequest;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.Ticket;
import dev.agentkit.itops.runtime.ExecutionRunner;
import dev.agentkit.itops.runtime.IntakeWorker;
import dev.agentkit.itops.runtime.OpsScheduler;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.tools.ToolCatalog;
import dev.agentkit.itops.workflow.Workflow;
import dev.agentkit.itops.workflow.WorkflowRunner;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * The web tier: a JSON API over the platform plus the single page that consumes it.
 *
 * <p>The JDK's own HTTP server, so the demo has no framework to install and no port
 * conflict with anything real. The shape of the API is the part meant to be read — chat is
 * one endpoint among many, not the product, and everything the chat can do is also
 * reachable as a resource an operator can list, inspect and act on. That is the difference
 * between an operations platform and a chatbot in front of a ticket queue.
 *
 * <p>Live updates ride the execution event stream over server-sent events. The same append
 * -only log that answers "what happened" months later is what paints the UI now, which is
 * what keeps the two from drifting: there is no second, prettier version of events for the
 * screen.
 */
public final class WebServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WebServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final OpsStore store;
    private final String tenantId;
    private final TicketProvider tickets;
    private final ExecutionRunner runner;
    private final IntakeWorker intake;
    private final OpsScheduler scheduler;
    private final WorkflowRunner workflows;
    private final List<Workflow> workflowDefinitions;
    private final List<SseClient> clients = new CopyOnWriteArrayList<>();
    private final AutoCloseable subscription;

    private record SseClient(HttpExchange exchange, OutputStream out) {}

    public WebServer(int port, OpsStore store, String tenantId, TicketProvider tickets,
            ExecutionRunner runner, IntakeWorker intake, OpsScheduler scheduler,
            WorkflowRunner workflows, List<Workflow> workflowDefinitions) throws IOException {
        this.store = Objects.requireNonNull(store, "store");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.tickets = tickets;
        this.runner = runner;
        this.intake = intake;
        this.scheduler = scheduler;
        this.workflows = workflows;
        this.workflowDefinitions = List.copyOf(workflowDefinitions);

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
                case "GET /tickets" -> ticketList();
                case "GET /executions" -> store.executions(tenantId).stream()
                        .map(WebServer::executionJson).toList();
                case "GET /approvals" -> store.approvals(tenantId).stream()
                        .map(WebServer::approvalJson).toList();
                case "GET /artifacts" -> store.artifacts(tenantId).stream()
                        .map(artifact -> Map.of("id", artifact.id(), "kind", artifact.kind().name(),
                                "title", artifact.title(), "executionId", artifact.executionId(),
                                "createdAt", artifact.createdAt().toString()))
                        .toList();
                case "GET /workflows" -> workflowDefinitions.stream()
                        .map(WebServer::workflowJson).toList();
                case "GET /schedules" -> scheduler.schedules().stream()
                        .map(state -> Map.of("name", state.schedule().name(),
                                "cron", String.valueOf(state.schedule().cron()),
                                "agent", state.schedule().agentId(),
                                "enabled", state.schedule().enabled(),
                                "input", state.schedule().input(),
                                "lastRunAt", String.valueOf(state.lastRunAt()),
                                "lastStarted", state.lastStartedExecutions()))
                        .toList();
                case "GET /tools" -> ToolCatalog.policies().stream()
                        .map(policy -> Map.of("name", policy.name(),
                                "capability", policy.capability(),
                                "connector", policy.connector(),
                                "risk", policy.baselineRisk().name(),
                                "reversible", policy.reversible(),
                                "idempotent", policy.idempotent()))
                        .toList();
                case "POST /chat" -> chat(exchange);
                case "POST /intake/run" -> Map.of("started",
                        intake.tick(null, Duration.ofMinutes(60), 10).size());
                default -> dynamic(method, strip(path), exchange);
            };
            if (body == null) {
                send(exchange, 404, Map.of("error", "No such endpoint: " + method + ' ' + path));
                return;
            }
            send(exchange, 200, body);
        } catch (RuntimeException | IOException failure) {
            // The path is percent-decoded by HttpExchange and this endpoint is
            // unauthenticated, so a request line is an operator's log line unless it is
            // escaped; the failure carries the same text back in its message (#98).
            //
            // Cut before escaping, and that order is deliberate here where it is the other
            // way round elsewhere: the ceiling is on how much log one request may buy, and
            // escaping is what makes a request expensive — a megabyte of %0A escaped to six
            // megabytes, so bounding the output would still let the escaping be paid for.
            // The remaining amplification is the escape's own six-to-one.
            log.warn("{} {} failed", Quoted.of(method), Quoted.of(Cut.to(path, 512)),
                    Quoted.failure(failure));
            send(exchange, 500, Map.of("error", String.valueOf(failure.getMessage())));
        }
    }

    /** Routes with an id in them, which a switch over literals cannot express. */
    private Object dynamic(String method, String path, HttpExchange exchange) throws IOException {
        List<String> parts = new ArrayList<>(List.of(path.split("/")));
        parts.removeIf(String::isEmpty);
        if (parts.size() >= 2 && parts.get(0).equals("tickets") && method.equals("GET")) {
            return tickets.get(decode(parts.get(1)))
                    .map(WebServer::ticketJson)
                    .orElse(null);
        }
        if (parts.size() >= 2 && parts.get(0).equals("executions") && method.equals("GET")) {
            Optional<Execution> execution = store.execution(tenantId, decode(parts.get(1)));
            if (execution.isEmpty()) {
                return null;
            }
            if (parts.size() == 3 && parts.get(2).equals("events")) {
                return store.events(execution.get().id()).stream()
                        .map(WebServer::eventJson).toList();
            }
            Map<String, Object> detail = new LinkedHashMap<>(executionJson(execution.get()));
            detail.put("events", store.events(execution.get().id()).stream()
                    .map(WebServer::eventJson).toList());
            detail.put("calls", store.invocations(execution.get().id()).stream()
                    .map(call -> Map.of("tool", call.toolName(), "arguments", call.arguments(),
                            "risk", call.effectiveRisk().name(), "error", call.error(),
                            "result", call.result() == null ? "" : call.result()))
                    .toList());
            return detail;
        }
        if (parts.size() == 3 && parts.get(0).equals("approvals") && method.equals("POST")) {
            boolean approve = parts.get(2).equals("approve");
            Map<String, Object> request = readJson(exchange);
            String who = String.valueOf(request.getOrDefault("by", "operator"));
            String note = String.valueOf(request.getOrDefault("note", ""));
            // Off unless asked for. A rejection ordinarily means "not this one", and a
            // standing refusal is a gate on the whole capability until somebody lifts it --
            // so it is a second, deliberate answer rather than something a reviewer gets by
            // clicking reject (#329).
            boolean standing = Boolean.parseBoolean(
                    String.valueOf(request.getOrDefault("standing", "false")));
            return runner.resume(tenantId, decode(parts.get(1)), who, approve, note, standing)
                    .map(outcome -> Map.<String, Object>of(
                            "executionId", outcome.execution().id(),
                            "status", outcome.execution().status().name(),
                            "summary", String.valueOf(outcome.execution().summary())))
                    .orElse(null);
        }
        // The other half of a standing refusal (#329). Every denial tells the model an
        // operator can lift it, and the README calls that the reason standing refusals are
        // defensible at all -- so the person who can install one from this console has to be
        // able to remove one from it. It was a Java method with nothing to type it into,
        // which made the remedy real only for whoever could restart the process.
        //
        // This endpoint is as unauthenticated as its neighbours, and that cuts both ways: it
        // is a knob that turns a control OFF. It is here because the reject route beside it
        // can already install the control, and a control with no reachable undo is the worse
        // of the two. See the README on putting this console behind something.
        if (parts.size() == 3 && parts.get(0).equals("corrections")
                && parts.get(2).equals("lift") && method.equals("POST")) {
            return runner.liftStandingRefusal(tenantId, decode(parts.get(1)))
                    .<Object>map(lifted -> Map.of("capability", decode(parts.get(1)),
                            "lifted", lifted))
                    .orElse(null);
        }
        if (parts.size() == 3 && parts.get(0).equals("workflows") && parts.get(2).equals("execute")
                && method.equals("POST")) {
            String id = decode(parts.get(1));
            Workflow definition = workflowDefinitions.stream()
                    .filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
            if (definition == null) {
                return null;
            }
            Map<String, Object> request = readJson(exchange);
            WorkflowRunner.Result result = workflows.run(tenantId, definition, request);
            return Map.of("executionId", result.execution().id(),
                    "status", result.execution().status().name(),
                    "lastNode", result.lastNodeId(), "parked", result.parked());
        }
        if (parts.size() == 2 && parts.get(0).equals("artifacts") && method.equals("GET")) {
            return store.artifact(tenantId, decode(parts.get(1)))
                    .map(artifact -> Map.of("id", artifact.id(), "kind", artifact.kind().name(),
                            "title", artifact.title(), "body", artifact.body(),
                            "createdAt", artifact.createdAt().toString()))
                    .orElse(null);
        }
        return null;
    }

    private Map<String, Object> chat(HttpExchange exchange) throws IOException {
        Map<String, Object> request = readJson(exchange);
        String message = String.valueOf(request.getOrDefault("message", "")).strip();
        if (message.isEmpty()) {
            return Map.of("error", "Say something.");
        }
        Execution execution = store.createExecution(tenantId, "it-ops-agent",
                Execution.Trigger.CHAT, "chat", message);
        ExecutionRunner.Outcome outcome = runner.run(execution, null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("executionId", outcome.execution().id());
        response.put("status", outcome.execution().status().name());
        response.put("parked", outcome.parked());
        response.put("reply", outcome.parked()
                ? "I stopped and asked for approval — see the Approvals panel."
                : outcome.output());
        store.pendingApprovalFor(outcome.execution().id())
                .ifPresent(approval -> response.put("approval", approvalJson(approval)));
        return response;
    }

    private List<Map<String, Object>> ticketList() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Ticket ticket : tickets.searchRecent(null, Duration.ofDays(7), 50)) {
            Map<String, Object> row = new LinkedHashMap<>(ticketJson(ticket));
            store.processingRecord(tenantId, ticket.provider(), ticket.id())
                    .ifPresent(record -> {
                        row.put("processing", record.status().name());
                        row.put("processingExecution", record.executionId());
                    });
            rows.add(row);
        }
        return rows;
    }

    // --- server-sent events -------------------------------------------------------

    private void streamEvents(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        SseClient client = new SseClient(exchange, exchange.getResponseBody());
        clients.add(client);
        // The connection stays open; the handler returns and the stream is written to from
        // whatever thread appends the next event.
    }

    private void broadcast(Execution.Event event) {
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

    private static Map<String, Object> ticketJson(Ticket ticket) {
        // Every value below is written by whoever filed the ticket, the id included — it is
        // the ticketing system's, and on the demo connector anyone who can open a ticket
        // chooses it. They stay raw here on purpose: JSON is the transport, and a value
        // escaped for a sink it has not reached yet is a value that arrives wrong at every
        // other one. Neutralising happens where the context is known — `ui/index.html`
        // renders these through `esc` into a text node or a quoted attribute and never into
        // an event handler (#192), and TicketTools fences the same fields for the model
        // (#178).
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", ticket.id());
        json.put("provider", ticket.provider());
        json.put("title", ticket.title());
        json.put("description", ticket.description());
        json.put("status", ticket.status().name().toLowerCase(java.util.Locale.ROOT));
        json.put("assignment_group", ticket.assignmentGroup());
        json.put("assignee", ticket.assignee());
        json.put("created_at", ticket.createdAt().toString());
        json.put("updated_at", ticket.updatedAt().toString());
        json.put("comments", ticket.comments().stream()
                .map(comment -> Map.of("author", comment.author(), "body", comment.body(),
                        "at", comment.at().toString()))
                .toList());
        return json;
    }

    private static Map<String, Object> executionJson(Execution execution) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", execution.id());
        json.put("agent", execution.agentId());
        json.put("trigger", execution.trigger().name());
        json.put("triggerReference", execution.triggerReference());
        json.put("goal", execution.goal());
        json.put("status", execution.status().name());
        json.put("createdAt", execution.createdAt().toString());
        // startedAt was absent from this rendering, so the console could not show a start
        // time or a duration even for the rows that had one (#267). Same String.valueOf as
        // completedAt below, which renders "null" for a row that has not reached that point
        // — a run that never started, and a run still running, respectively.
        json.put("startedAt", String.valueOf(execution.startedAt()));
        json.put("completedAt", String.valueOf(execution.completedAt()));
        json.put("summary", execution.summary() == null ? "" : execution.summary());
        return json;
    }

    private static Map<String, Object> eventJson(Execution.Event event) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("executionId", event.executionId());
        json.put("sequence", event.sequence());
        json.put("type", event.type().name());
        json.put("at", event.at().toString());
        json.put("detail", event.detail());
        return json;
    }

    private static Map<String, Object> approvalJson(ApprovalRequest approval) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", approval.id());
        json.put("executionId", approval.executionId());
        json.put("tool", approval.toolName());
        json.put("arguments", approval.arguments());
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

    private static Map<String, Object> workflowJson(Workflow workflow) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", workflow.id());
        json.put("name", workflow.name());
        json.put("version", workflow.version());
        json.put("description", workflow.description());
        json.put("active", workflow.active());
        json.put("nodes", workflow.nodes().stream()
                .map(node -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", node.id());
                    row.put("type", node.type().name().toLowerCase(java.util.Locale.ROOT));
                    row.put("label", node.label());
                    row.put("tool", node.tool());
                    row.put("expression", node.expression());
                    return row;
                })
                .toList());
        json.put("edges", workflow.edges().stream()
                .map(edge -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("from", edge.from());
                    row.put("to", edge.to());
                    row.put("when", edge.when());
                    return row;
                })
                .toList());
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
