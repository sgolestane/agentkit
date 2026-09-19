package dev.agentkit.host.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.agentkit.chat.web.ChatServer;
import dev.agentkit.core.deferred.DeferredAction;
import dev.agentkit.host.AgentHost;
import dev.agentkit.host.DeferredWork;
import dev.agentkit.host.HostedAgent;
import dev.agentkit.host.OrgHost;
import dev.agentkit.host.Principal;
import dev.agentkit.host.RehearsalLog;
import dev.agentkit.host.Tenant;
import dev.agentkit.host.change.Proposals;
import dev.agentkit.host.models.ModelAccounts;
import dev.agentkit.host.plans.PlanBook;
import dev.agentkit.host.plans.PlanReuse;
import dev.agentkit.host.repo.AgentDefinition;
import dev.agentkit.host.repo.EvalCase;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What an organization's operators see of it: read-only, per organization, for the people {@code org.yaml} names as
 * admins.
 *
 * <pre>
 * GET  /host/admin                   the versions loaded, their agents, the connectors and whether each is reached
 * GET  /host/admin/agents/{id}       one agent at a version (?version=, the current by default): its prompts, its
 *                                   tools by effect with confirmations and bindings, its form, deferred work and evals
 * GET  /host/admin/deferred          every deferred action the organization's agents hold
 * GET  /host/admin/rehearsals        the rehearsals its pull requests reported, newest first
 * GET  /host/admin/usage             its model account: whose, calls at once, budgets, and what it spent today and
 *                                   this month, by agent
 * GET  /host/admin/agents/{id}/files the agent's files at the current version, to edit for a proposal
 * POST /host/admin/proposals         a change to agents' files, checked and opened as a pull request ({@link Proposals})
 * POST /host/rehearsals/{org}        a pull request's rehearsal report, from rehearse, with the org's REHEARSAL_TOKEN
 * </pre>
 *
 * Nothing here changes what the host runs: a change is proposed as a pull request to the organization's repository,
 * and served once it is merged.
 */
public final class AdminApi {

    /** The largest rehearsal report accepted. */
    static final int MAX_REPORT_BYTES = 4 * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, OrgHost> orgs;
    private final Map<String, DeferredWork> deferred;
    private final ChatServer.Tenants tenants;
    private final RehearsalLog rehearsals;
    private final Function<String, Optional<String>> reportToken;
    private final Supplier<Instant> clock;
    private final Proposals proposals;
    private final Optional<ModelAccounts> models;
    private final Optional<PlanReuse> plans;

    /**
     * @param reportToken the token a rehearsal report for an organization must carry; empty when it takes none
     * @param proposals   where changes proposed from the admin view go
     */
    public AdminApi(Map<String, OrgHost> orgs, Map<String, DeferredWork> deferred, ChatServer.Tenants tenants,
                    RehearsalLog rehearsals, Function<String, Optional<String>> reportToken, Supplier<Instant> clock,
                    Proposals proposals) {
        this(orgs, deferred, tenants, rehearsals, reportToken, clock, proposals, null, null);
    }

    /**
     * @param models each organization's model account, whose use the admin view shows; null when none is kept
     * @param plans  the plans plan-execute agents carried out from their forms, and which have settled; null for none
     */
    public AdminApi(Map<String, OrgHost> orgs, Map<String, DeferredWork> deferred, ChatServer.Tenants tenants,
                    RehearsalLog rehearsals, Function<String, Optional<String>> reportToken, Supplier<Instant> clock,
                    Proposals proposals, ModelAccounts models, PlanReuse plans) {
        this.models = Optional.ofNullable(models);
        this.plans = Optional.ofNullable(plans);
        this.orgs = Map.copyOf(orgs);
        this.deferred = Map.copyOf(deferred);
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.rehearsals = Objects.requireNonNull(rehearsals, "rehearsals");
        this.reportToken = Objects.requireNonNull(reportToken, "reportToken");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
    }

    /** The admin view's API, for {@code /host/admin}. */
    public HttpHandler admin() {
        return exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath().substring("/host/admin".length());
                boolean proposing = path.equals("/proposals");
                if (!(proposing ? "POST" : "GET").equals(exchange.getRequestMethod())) {
                    send(exchange, 405, Map.of("error", proposing ? "Post a proposal here."
                            : "The admin view only reads; a change is a proposal."));
                    return;
                }
                Optional<Tenant> tenant = tenants.of(exchange).flatMap(Tenant::parse);
                if (tenant.isEmpty()) {
                    send(exchange, 401, Map.of("error", "Sign in first."));
                    return;
                }
                OrgHost org = orgs.get(tenant.get().org());
                Optional<Principal> principal = org == null ? Optional.empty()
                        : org.current().principal(tenant.get().email());
                if (principal.isEmpty() || !org.current().isAdmin(principal.get())) {
                    send(exchange, 403, Map.of("error", "Only the admins org.yaml names see this organization's admin view."));
                    return;
                }
                if (proposing) {
                    propose(exchange, org, principal.get());
                } else if (path.isEmpty() || path.equals("/")) {
                    send(exchange, 200, overview(org));
                } else if (path.startsWith("/agents/") && path.endsWith("/files")) {
                    String id = URLDecoder.decode(path.substring("/agents/".length(), path.length() - "/files".length()),
                            StandardCharsets.UTF_8);
                    if (org.current().agent(id).isEmpty()) {
                        send(exchange, 404, Map.of("error", "There is no agent " + id + "."));
                    } else {
                        send(exchange, 200, Map.of("version", org.current().repo().version(), "files", files(org, id)));
                    }
                } else if (path.startsWith("/agents/")) {
                    String id = URLDecoder.decode(path.substring("/agents/".length()), StandardCharsets.UTF_8);
                    String version = query(exchange, "version").orElse(org.current().repo().version());
                    Optional<HostedAgent> agent = org.version(version).flatMap(v -> v.agent(id));
                    if (agent.isEmpty()) {
                        send(exchange, 404, Map.of("error", "There is no agent " + id + " at " + version + "."));
                    } else {
                        send(exchange, 200, agent(org.org(), agent.get(), version));
                    }
                } else if (path.equals("/deferred")) {
                    send(exchange, 200, deferred(org));
                } else if (path.equals("/usage")) {
                    if (models.isEmpty()) {
                        send(exchange, 404, Map.of("error", "This host keeps no account of model use."));
                    } else {
                        send(exchange, 200, models.get().report(org.current().repo()));
                    }
                } else if (path.equals("/rehearsals")) {
                    send(exchange, 200, Map.of("reports", rehearsals.recent(org.org(), 20)));
                } else {
                    send(exchange, 404, Map.of("error", "There is nothing at " + path + "."));
                }
            } catch (RuntimeException e) {
                send(exchange, 500, Map.of("error", "The admin view failed: " + e.getMessage()));
            }
        };
    }

    /** Where {@code rehearse} reports a pull request's rehearsal, for {@code /host/rehearsals/}. */
    public HttpHandler reports() {
        return exchange -> {
            try (exchange) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    send(exchange, 405, Map.of("error", "Post a rehearsal report here."));
                    return;
                }
                String org = exchange.getRequestURI().getPath().substring("/host/rehearsals/".length());
                Optional<String> token = orgs.containsKey(org) ? reportToken.apply(org) : Optional.empty();
                String given = Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), "");
                if (token.isEmpty() || !MessageDigest.isEqual(("Bearer " + token.get()).getBytes(StandardCharsets.UTF_8),
                        given.getBytes(StandardCharsets.UTF_8))) {
                    send(exchange, 401, Map.of("error", "A report needs the organization's rehearsal token."));
                    return;
                }
                byte[] body = read(exchange.getRequestBody());
                if (body == null) {
                    send(exchange, 413, Map.of("error", "The report is larger than " + MAX_REPORT_BYTES + " bytes."));
                    return;
                }
                Map<String, Object> report;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = JSON.readValue(body, Map.class);
                    report = parsed;
                } catch (IOException e) {
                    send(exchange, 400, Map.of("error", "The report is not JSON."));
                    return;
                }
                if (!(report.get("results") instanceof List<?>) || !org.equals(report.get("org"))) {
                    send(exchange, 400, Map.of("error", "The report is not a rehearsal of " + org + "."));
                    return;
                }
                rehearsals.add(org, report, clock.get());
                send(exchange, 202, Map.of("kept", true));
            } catch (RuntimeException e) {
                send(exchange, 500, Map.of("error", "The report could not be kept: " + e.getMessage()));
            }
        };
    }

    /** Checks a proposal and, if the host would load it, opens it for review. */
    private void propose(HttpExchange exchange, OrgHost org, Principal by) throws IOException {
        // JSON only: a form posted from another site cannot be JSON without asking first, so a proposal comes from here.
        String type = Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), "");
        if (!type.startsWith("application/json")) {
            send(exchange, 415, Map.of("error", "A proposal is JSON."));
            return;
        }
        byte[] body = read(exchange.getRequestBody());
        Map<String, Object> proposal;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = body == null ? null : JSON.readValue(body, Map.class);
            proposal = parsed;
        } catch (IOException e) {
            proposal = null;
        }
        if (proposal == null || !(proposal.get("files") instanceof Map<?, ?> given)) {
            send(exchange, 400, Map.of("error", "A proposal is {title, description, files: {path: content}}."));
            return;
        }
        Map<String, String> files = new LinkedHashMap<>();
        given.forEach((path, content) -> files.put(String.valueOf(path), content == null ? null : String.valueOf(content)));
        Proposals.Outcome outcome = proposals.propose(org, by, Objects.toString(proposal.get("title"), ""),
                Objects.toString(proposal.get("description"), ""), files);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("opened", outcome.opened());
        if (outcome.opened()) {
            answer.put("branch", outcome.branch());
            answer.put("url", outcome.url());
            answer.put("where", outcome.where());
            answer.put("summary", outcome.summary());
        } else {
            answer.put("problems", outcome.problems());
        }
        send(exchange, outcome.opened() ? 201 : 422, answer);
    }

    /** The agent's own files at the current version, as its checkout holds them: what a proposal starts from. */
    private static List<Map<String, Object>> files(OrgHost org, String id) throws IOException {
        java.nio.file.Path dir = org.checkout().resolve("agents").resolve(id);
        List<Map<String, Object>> files = new ArrayList<>();
        if (!java.nio.file.Files.isDirectory(dir)) {
            return files;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(dir)) {
            for (java.nio.file.Path file : walk.sorted().toList()) {
                String relative = org.checkout().relativize(file).toString().replace('\\', '/');
                if (!java.nio.file.Files.isRegularFile(file) || relative.contains("/.")
                        || java.nio.file.Files.size(file) > Proposals.MAX_FILE_BYTES) {
                    continue;
                }
                files.add(Map.of("path", relative, "content",
                        java.nio.file.Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return files;
    }

    // ---------------------------------------------------------------- what is shown

    private Map<String, Object> overview(OrgHost org) {
        AgentHost current = org.current();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("org", org.org());
        view.put("current", current.repo().version());
        view.put("admins", current.repo().admins());
        Map<String, Object> router = new LinkedHashMap<>();
        router.put("enabled", current.repo().router().enabled());
        router.put("model", current.repo().router().model() != null ? current.repo().router().model()
                : current.repo().defaultModel());
        router.put("instructions", !current.repo().router().prompt().isBlank());
        router.put("cases", current.repo().routing().size());
        view.put("router", router);
        List<Map<String, Object>> versions = new ArrayList<>();
        List<String> loaded = new ArrayList<>(org.versions());
        java.util.Collections.reverse(loaded);
        for (String version : loaded) {
            org.version(version).ifPresent(host -> {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("version", version);
                v.put("current", version.equals(current.repo().version()));
                v.put("agents", host.agents().values().stream().map(agent -> {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("id", agent.definition().id());
                    a.put("name", agent.definition().name());
                    a.put("description", agent.definition().description());
                    a.put("pattern", pattern(agent.definition()));
                    a.put("audience", agent.definition().audience());
                    a.put("evals", agent.definition().evals().size());
                    agent.unavailable().ifPresent(why -> a.put("unavailable", why));
                    return a;
                }).toList());
                versions.add(v);
            });
        }
        view.put("versions", versions);
        Proposals.Availability availability = proposals.availability(org);
        Map<String, Object> proposing = new LinkedHashMap<>();
        proposing.put("enabled", availability.enabled());
        if (availability.where() != null) {
            proposing.put("where", availability.where());
        }
        if (availability.why() != null) {
            proposing.put("why", availability.why());
        }
        view.put("proposals", proposing);
        view.put("connectors", current.repo().connectors().keySet().stream().map(name -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", name);
            c.put("reached", current.connectors().catalog(name).isPresent());
            current.connectors().failure(name).ifPresent(why -> c.put("failure", why));
            c.put("tools", current.connectors().catalog(name).map(t -> t.entries().size()).orElse(0));
            return c;
        }).toList());
        return view;
    }

    private Map<String, Object> agent(String org, HostedAgent agent, String version) {
        AgentDefinition definition = agent.definition();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", definition.id());
        view.put("name", definition.name());
        view.put("description", definition.description());
        view.put("version", version);
        view.put("pattern", pattern(definition));
        view.put("model", agent.model());
        view.put("audience", definition.audience());
        view.put("limits", Map.of("maxSteps", definition.maxSteps(), "maxTokens", definition.maxTokens()));
        agent.unavailable().ifPresent(why -> view.put("unavailable", why));

        Map<String, Object> prompts = new LinkedHashMap<>();
        if (definition.pattern() == AgentDefinition.Pattern.PLAN_EXECUTE) {
            prompts.put("planner", definition.plannerPrompt());
            prompts.put("executor", definition.systemPrompt());
        } else {
            prompts.put("system", definition.systemPrompt());
        }
        if (!definition.policy().isBlank()) {
            prompts.put("policy", definition.policy());
        }
        if (definition.deferred() != null) {
            prompts.put("deferred", definition.deferred().prompt());
        }
        view.put("prompts", prompts);

        Map<String, String> refused = agent.changing(List.of());
        view.put("tools", agent.toolInfo().stream().map(tool -> {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("connector", tool.connector());
            t.put("name", tool.name());
            t.put("description", tool.description());
            t.put("effect", tool.effect());
            t.put("system", tool.system());
            t.put("confirmed", tool.confirmed());
            t.put("bound", tool.bound());
            t.put("sideEffects", tool.sideEffects());
            t.put("refusedInRehearsal", refused.containsKey(tool.name()));
            return t;
        }).toList());
        view.put("input", definition.input() == null ? null : definition.input().jsonSchema());
        if (definition.deferred() != null) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("actor", definition.deferred().actor());
            Map<String, String> subjects = new LinkedHashMap<>();
            definition.deferred().subjects().forEach((kind, subject) -> subjects.put(kind,
                    subject.tool() + "(" + subject.argument() + ")"));
            d.put("subjects", subjects);
            view.put("deferred", d);
        }
        view.put("mcpDirect", definition.mcpDirect().stream().map(Object::toString).toList());
        view.put("evals", definition.evals().stream().map(AdminApi::evalCase).toList());
        if (definition.planReuse() != null) {
            view.put("planReuse", planReuse(org, definition, version));
        }
        return view;
    }

    /** When the agent reuses a plan, and for which kinds of task it has, at this version. */
    private Map<String, Object> planReuse(String org, AgentDefinition definition, String version) {
        AgentDefinition.PlanReuse rules = definition.planReuse();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("after", rules.after());
        view.put("recheckEvery", rules.recheckEvery());
        view.put("sameWhen", rules.sameWhen());
        List<Map<String, Object>> kinds = new ArrayList<>();
        plans.ifPresent(reuse -> {
            PlanBook.Agent where = new PlanBook.Agent(org, definition.id(), version);
            for (String shape : reuse.book().shapes(where).stream().limit(20).toList()) {
                Map<String, Object> kind = new LinkedHashMap<>();
                kind.put("task", shape.lines().skip(1).toList());
                kind.put("runs", reuse.book().count(where, shape));
                reuse.settled(where, rules, shape).ifPresent(settled -> kind.put("settled", settled.steps()));
                kinds.add(kind);
            }
        });
        view.put("kinds", kinds);
        return view;
    }

    private static Map<String, Object> evalCase(EvalCase c) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", c.name());
        view.put("as", c.as());
        if (c.say() != null) {
            view.put("say", c.say());
        }
        if (c.input() != null) {
            view.put("input", c.input());
        }
        view.put("answers", c.answers());
        view.put("expect", c.expect().stream().map(EvalCase.Expectation::describe).toList());
        return view;
    }

    private Map<String, Object> deferred(OrgHost org) {
        DeferredWork work = deferred.get(org.org());
        List<Map<String, Object>> agents = new ArrayList<>();
        for (HostedAgent agent : org.current().agents().values()) {
            if (agent.definition().deferred() == null || work == null) {
                continue;
            }
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", agent.definition().id());
            a.put("name", agent.definition().name());
            a.put("actions", work.store(agent.definition().id()).all().stream().map(AdminApi::action).toList());
            agents.add(a);
        }
        return Map.of("agents", agents);
    }

    private static Map<String, Object> action(DeferredAction action) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", action.id());
        view.put("subject", action.subjectKind() + " " + action.subjectId());
        view.put("runAt", action.runAt().toString());
        view.put("when", action.when());
        view.put("status", action.status().name());
        view.put("scheduledBy", action.scheduledBy());
        view.put("scheduledAt", action.scheduledAt() == null ? null : action.scheduledAt().toString());
        view.put("goal", action.goal());
        view.put("outcome", action.outcome());
        view.put("finishedAt", action.finishedAt() == null ? null : action.finishedAt().toString());
        return view;
    }

    private static String pattern(AgentDefinition definition) {
        return definition.pattern().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    // ---------------------------------------------------------------- http

    private static Optional<String> query(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                return value.isBlank() ? Optional.empty() : Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /** The body, or null when it is larger than a report may be. */
    private static byte[] read(InputStream in) throws IOException {
        byte[] body = in.readNBytes(MAX_REPORT_BYTES + 1);
        return body.length > MAX_REPORT_BYTES ? null : body;
    }

    private static void send(HttpExchange exchange, int status, Object body) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // The caller went away.
        }
    }
}
