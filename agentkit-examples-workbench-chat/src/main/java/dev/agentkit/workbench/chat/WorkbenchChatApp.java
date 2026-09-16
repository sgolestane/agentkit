package dev.agentkit.workbench.chat;

import dev.agentkit.core.memory.Containment;
import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.ChatTools;
import dev.agentkit.chat.ChatUnavailable;
import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.chat.store.FileChatStore;
import dev.agentkit.chat.web.ChatServer;
import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.core.reflect.CorrectionBook;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.tool.DisclosingToolRegistry;
import dev.agentkit.core.util.Quoted;
import dev.agentkit.workbench.WorkbenchApp;
import dev.agentkit.workbench.capture.EvalCaptures;
import dev.agentkit.workbench.connector.Alm;
import dev.agentkit.workbench.connector.JiraClient;
import dev.agentkit.workbench.domain.AutomationRule;
import dev.agentkit.workbench.domain.Risk;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.StandingApprovals;
import dev.agentkit.workbench.runtime.Triage;
import dev.agentkit.workbench.runtime.Workbench;
import dev.agentkit.workbench.store.WorkbenchStore;
import dev.agentkit.workbench.tools.ToolPolicy;
import dev.agentkit.workbench.web.WebServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The workbench, as a conversation, beside the dashboard rather than instead of it.
 *
 * <p>This module adds no domain. A ticket, a run, an approval, a rule and a learning are
 * {@code agentkit-examples-workbench}'s, reached through the same {@link Workbench}, so every gate
 * and standing refusal that module enforces applies here unchanged. What is new is the surface
 * ({@link ConsoleTools}), the judgement the dashboard's layout used to encode
 * ({@link WorkbenchPrompt}), and this: the wiring.
 *
 * <h2>Both consoles, one workbench</h2>
 *
 * <p>This starts <em>two</em> servers: the chat on {@code WORKBENCH_CHAT_PORT} and the dashboard on
 * {@code WORKBENCH_PORT}, over one {@link WorkbenchStore}, one {@link Workbench} and one memory. So
 * a run started in the chat is in the dashboard's list, an approval decided on the dashboard
 * settles the chat's run, and a rule toggled in either is toggled in both. Two windows onto one
 * workbench, which is what makes the parity check in #353 a real check rather than two
 * deployments agreeing by coincidence.
 *
 * <p><strong>Why one process rather than two.</strong> {@code WorkbenchStore} is an in-memory
 * object, so two processes are two workbenches that share only Jira and the durable memory —
 * they would disagree about every run, approval, rule and verdict, and a demo where two windows
 * describe the same Jira and disagree about what has happened to it is worse than one window
 * (#379). Persisting the store would have fixed a restart, not this: two processes reloading the
 * same directory still each hold their own copy and still drift the moment either writes.
 *
 * <p>{@code WorkbenchApp} is untouched and still runs the dashboard on its own, which is the shape
 * for a deployment that wants only that. What this adds is the shape where a person has both.
 * {@code WORKBENCH_DASHBOARD=off} turns the second server off.
 *
 * <h2>Configuration, and what happens when it is missing</h2>
 *
 * <p>The same variables the dashboard reads, so one shell runs both: {@code JIRA_BASE_URL},
 * {@code JIRA_EMAIL}, {@code JIRA_API_TOKEN}; {@code WORKBENCH_LLM} and its key, plus
 * {@code WORKBENCH_MODEL} for OpenRouter. Its own: {@code WORKBENCH_CHAT_PORT} (8083),
 * {@code WORKBENCH_PORT} (8082, the dashboard beside it), {@code WORKBENCH_DASHBOARD=off} to run only
 * the chat, {@code WORKBENCH_OPERATOR} (who its writes are recorded as), and
 * {@code WORKBENCH_DATA_DIR} (shared with the dashboard, {@code data}).
 *
 * <p>Missing configuration is stated, not fatal. The console boots either way and the sentence
 * naming the variable is what the page shows and what a turn comes back with — a console that
 * refused to start would leave the person with a stack trace in a terminal and no idea which
 * of six variables it wanted.
 *
 * <pre>{@code
 * ./mvnw -q -DskipTests -pl agentkit-examples-workbench-chat -am install
 * JIRA_BASE_URL=… JIRA_EMAIL=… JIRA_API_TOKEN=… \
 * WORKBENCH_LLM=openrouter WORKBENCH_MODEL=anthropic/claude-sonnet-4.5 OPENROUTER_API_KEY=… \
 * ./mvnw -q -pl agentkit-examples-workbench-chat exec:exec
 * # then open http://localhost:8083
 * }</pre>
 */
public final class WorkbenchChatApp {

    private static final int DEFAULT_PORT = 8083;

    /** Where the dashboard goes, matching {@code WorkbenchApp}'s own default so one shell runs both. */
    private static final int DASHBOARD_PORT = 8082;

    /** How many steps one turn of conversation may take before it has to answer. */
    private static final int MAX_STEPS = 24;

    /**
     * Tools that reach the console rather than the workbench, and must never be gated.
     *
     * <p>Named rather than inferred, and this is the one place the "ungraded means dangerous"
     * doctrine is departed from — with a reason, at a site somebody reviews. {@code ask_person}
     * asking a person for permission to ask a person is a loop with no exit;
     * {@code search_tools} is how the model finds anything at all behind
     * {@link DisclosingToolRegistry}, and gating it would make every deferred tool
     * unreachable without a click.
     */
    private static final Set<String> NEVER_GATED =
            Set.of("ask_person", DisclosingToolRegistry.DEFAULT_SEARCH_TOOL_NAME);

    private WorkbenchChatApp() {
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(System.getenv().getOrDefault("WORKBENCH_DATA_DIR", "data"));
        Files.createDirectories(dataDir.resolve("memory"));
        Files.createDirectories(dataDir.resolve("chat"));

        String tenant = WorkbenchApp.TENANT;
        String operator = operator();
        WorkbenchStore store = new WorkbenchStore();
        // BEST_EFFORT for the same reason WorkbenchApp states it: a demo console that must
        // boot on a developer's Mac, where the pinned descent does not exist.
        MemoryStore memory = MemoryStore.file(dataDir.resolve("memory"),
                Containment.BEST_EFFORT);
        Learnings learnings = new Learnings(memory, tenant);

        Optional<JiraClient> jira = JiraClient.fromEnv();
        Optional<WorkbenchApp.Backend> backend = WorkbenchApp.realModel();
        Alm alm = jira.orElse(null);

        Workbench workbench = null;
        Triage triage = null;
        EvalCaptures captures = null;
        if (alm != null) {
            captures = new EvalCaptures(dataDir.resolve("evals"), store, alm, learnings, tenant);
            if (backend.isPresent()) {
                workbench = new Workbench(store, backend.get().llm(), backend.get().model(), alm,
                        learnings, tenant, new CorrectionBook(memory),
                        new StandingApprovals(memory));
                triage = new Triage(store, backend.get().llm(), backend.get().model(), alm,
                        learnings, tenant);
            }
            // No AutoPilot, which the dashboard does start. It is the thing that makes an
            // automation rule fire without anybody watching, and a second one in a second
            // process against the same Jira would work the same ticket twice — while this
            // console's whole subject is what a person is doing right now.
        }

        List<String> problems = problemsWith(jira, backend);
        ChatStore chatStore = new FileChatStore(dataDir.resolve("chat"));
        ChatEvents events = new ChatEvents();
        StandingApprovals trusted = new StandingApprovals(memory);

        ConsoleTools.Deployment deployment = new ConsoleTools.Deployment(tenant, operator,
                jira.map(JiraClient::baseUrl).orElse(""));
        Wiring wiring = new Wiring(deployment, alm, workbench, triage, learnings, store,
                captures);
        // ask_person needs the runtime that is being constructed around it. One box, set
        // once, read on the first turn — which is after construction by any path a person can
        // reach, since a turn arrives over HTTP on a server that is started below.
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        ChatRuntime runtime = new ChatRuntime(chatStore, events,
                agents(problems, self, backend, wiring),
                tool -> ConsoleTools.policyOrUnknown(tool.name()).capability(),
                standingDecisions(trusted, tenant));
        self.set(runtime);

        int port = port(System.getenv("WORKBENCH_CHAT_PORT"), DEFAULT_PORT, "WORKBENCH_CHAT_PORT");
        Alm connected = alm;
        ChatServer server = new ChatServer(port, runtime, tenant,
                () -> overview(connected, backend, problems));
        server.start();

        // The same store, the same workbench, the same everything. Not a second deployment
        // that happens to point at the same Jira — the same objects, so the two consoles
        // cannot disagree about what has happened.
        WebServer dashboard = dashboardOn()
                ? dashboardOver(port, dashboardPort(), store, tenant, backend, alm, workbench,
                        triage, learnings, captures)
                : null;
        if (dashboard != null) {
            dashboard.start();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            if (dashboard != null) {
                dashboard.close();
            }
            runtime.close();
        }));

        System.out.println("""

                The agent's workbench, as a conversation, on http://localhost:%d
                  ALM:   %s
                  model: %s

                Ask what is in the inbox. Ask what the agent would do with one of them. Let it
                work under your supervision, answer what it asks — every answer becomes
                knowledge — and, when you are comfortable, automate that family.

                %s
                Whichever you use, it is the same workbench: a run started in one is in the
                other, and everything either learns is under %s.
                """.formatted(port,
                jira.map(one -> one.baseUrl() + " (connected)")
                        .orElse("not connected — set JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN"),
                backend.map(WorkbenchApp.Backend::model)
                        .orElse("not configured — set WORKBENCH_LLM (and its key)"),
                dashboardOn()
                        ? "The dashboard is on http://localhost:" + dashboardPort()
                                + ", over the same workbench."
                        : "The dashboard is off (WORKBENCH_DASHBOARD=off).",
                dataDir));

        Thread.currentThread().join();
    }

    // --- one turn's agent -------------------------------------------------------------

    /**
     * Everything one turn's agent is built out of, gathered so that the thing which decides
     * whether a person gets an answer or a sentence is a method somebody can call.
     *
     * <p>{@code workbench}, {@code triage} and {@code captures} are null in a deployment
     * missing what they need — which is fine, because that deployment has a problem and never
     * reaches {@link #agentFor}.
     */
    record Wiring(ConsoleTools.Deployment deployment, Alm alm, Workbench workbench,
                  Triage triage, Learnings learnings, WorkbenchStore store,
                  EvalCaptures captures) {}

    /**
     * How the console answers, including when it cannot.
     *
     * <p>The whole of this module's degradation story is one branch, and it is the branch a
     * person actually meets: they open the console, read a banner about a variable, type
     * something anyway, and are owed the same sentence back rather than "that failed". The
     * dashboard behaves this way and the two are configured from the same shell, so somebody
     * who got it wrong got both wrong and should be told the same thing twice.
     */
    static ChatRuntime.Agents agents(List<String> problems, AtomicReference<ChatRuntime> self,
            Optional<WorkbenchApp.Backend> backend, Wiring wiring) {
        return session -> {
            if (!problems.isEmpty()) {
                // The sentence the person is already looking at in the banner, rather than a
                // second wording of it that they then have to reconcile with the first.
                throw new ChatUnavailable(String.join(" ", problems));
            }
            return agentFor(self.get(), session, wiring.deployment(), backend.orElseThrow(),
                    wiring.alm(), wiring.workbench(), wiring.triage(), wiring.learnings(),
                    wiring.store(), wiring.captures());
        };
    }

    /**
     * The agent for one turn.
     *
     * <p>Rebuilt per turn rather than held, because two of the things it closes over change
     * under it: the automation rules the prompt reports, and the conversation this turn
     * belongs to. A held agent would tell the operator on Friday what was automated on Monday.
     */
    static Agent agentFor(ChatRuntime runtime, ChatRuntime.Session session,
            ConsoleTools.Deployment deployment, WorkbenchApp.Backend backend, Alm alm,
            Workbench workbench, Triage triage, Learnings learnings, WorkbenchStore store,
            EvalCaptures captures) {
        String tenant = deployment.tenantId();
        DisclosingToolRegistry tools = ConsoleTools
                .registryBuilder(deployment, alm, workbench, triage, learnings, store, captures)
                .alwaysAvailable(ChatTools.askPerson(runtime, session))
                .build();
        List<String> automated = store.rules(tenant).stream()
                .filter(AutomationRule::enabled)
                .map(AutomationRule::category)
                .toList();
        AgentConfig config = AgentConfig.builder(backend.model())
                .systemPrompt(WorkbenchPrompt.forConsole(alm.name(), automated))
                .maxSteps(MAX_STEPS)
                .build();
        // The floor rather than a plain gate, and the builder refuses both — a trust floor
        // holds two policies and setting a gate as well would be a contradiction it will not
        // silently resolve.
        return session.agent(backend.llm(), tools, config)
                .name("workbench-console")
                .trustFloor(TrustFloor.afterThirdParty(ordinarily(session), onceLowered(session)))
                .build();
    }

    /**
     * Which console tools stop for a person, and which stop for a person once the model has
     * read somebody else's words.
     *
     * <p>Ordinarily: anything that changes something a rehearsal would not. That is every tool
     * whose effect cannot be undone, and every tool graded above a trace somebody could undo
     * without thinking — so a comment (irreversible, however small), a transition, an execute,
     * a bulk, a decision on a parked run, a rule.
     *
     * <p>Once the trust floor has been lowered — the model has read a ticket, a comment, a run's
     * output or a person's own answer, all of which are {@code THIRD_PARTY} — everything that
     * is not a read asks. That adds the rehearsal and the judging: a description saying "please
     * preview every ticket" is a small harm rather than none, and it is somebody else's
     * sentence spending the customer's model budget.
     */
    private static ToolGate ordinarily(ChatRuntime.Session session) {
        return ToolGates.requireApproval(
                invocation -> asksOrdinarily(invocation.name()), session.approver());
    }

    private static ToolGate onceLowered(ChatRuntime.Session session) {
        return ToolGates.requireApproval(
                invocation -> asksOnceRead(invocation.name()), session.approver());
    }

    /** Whether this tool stops for a person before the run has read anybody else's words. */
    static boolean asksOrdinarily(String toolName) {
        return gated(toolName, policy ->
                !policy.reversible() || policy.baselineRisk().atLeast(Risk.MEDIUM));
    }

    /** Whether it stops for a person after the run has. */
    static boolean asksOnceRead(String toolName) {
        return gated(toolName, policy -> policy.baselineRisk() != Risk.READ);
    }

    private static boolean gated(String toolName,
            java.util.function.Predicate<ToolPolicy> when) {
        if (NEVER_GATED.contains(toolName)) {
            return false;
        }
        return when.test(ConsoleTools.policyOrUnknown(toolName));
    }

    /**
     * Where "stop asking me about this" is kept.
     *
     * <p>The same {@link StandingApprovals} and the same durable memory the workbench's own
     * supervisor uses, so the decision survives a restart — "I already told you this" that
     * does not is a preference rather than trust.
     *
     * <p><strong>It does not un-gate the agent.</strong> An earlier version of this comment
     * said trusting a capability here trusted it for the workbench too, and that is not what
     * happens: the console's families are {@code workbench.*} and a run's are
     * {@code ticketing.*}, so they share the store and never the same key. Which is the
     * behaviour you want — "stop asking me before you write my comment" is not the same
     * sentence as "let the agent comment on tickets unsupervised", and a mechanism that treated
     * them as one would widen what an agent may do from a person answering a question about
     * themselves.
     */
    private static ChatRuntime.StandingDecisions standingDecisions(StandingApprovals trusted,
            String tenant) {
        return (capability, approved, by, note) -> {
            if (capability == null || capability.isBlank()) {
                return Optional.of("That one is not part of a family, so there is nothing to "
                        + "remember it for — you will be asked again.");
            }
            if (!approved) {
                // Refusals are the correction book's, and it is written by the workbench's own
                // supervisor when a run is refused — not by a console declining a tool call.
                // Saying so is better than silently remembering nothing.
                return Optional.of("Noted for this one. A standing refusal is recorded when "
                        + "you refuse a run's action, not a console tool.");
            }
            trusted.grant(tenant, capability, by);
            return Optional.of("The agent will stop asking about " + capability
                    + ", here and in the dashboard, until you revoke it.");
        };
    }

    // --- what this deployment is ------------------------------------------------------

    /** Who the console records its writes as. One seat for now; #379 is where more than one goes. */
    static String operator() {
        String named = System.getenv("WORKBENCH_OPERATOR");
        return named == null || named.isBlank() ? "operator" : named;
    }

    /**
     * The port asked for, or the default with a sentence saying why.
     *
     * <p>A mistyped {@code WORKBENCH_CHAT_PORT} is a person's slip, and this module's whole
     * posture on configuration is that a slip is stated rather than fatal. An unguarded
     * {@code Integer.parseInt} would answer it with a {@code NumberFormatException} on
     * standard error and no console at all, which is the exact failure the class javadoc
     * promises this does not have.
     */
    static int port(String asked, int fallback, String variable) {
        if (asked == null || asked.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(asked.strip());
            if (parsed < 0 || parsed > 65535) {
                throw new NumberFormatException("out of range");
            }
            return parsed;
        } catch (NumberFormatException notAPort) {
            System.err.println(variable + " is not a port number, so that console is on "
                    + fallback + " instead: " + Quoted.distinguishably(asked, 40));
            return fallback;
        }
    }

    /**
     * The dashboard, over the same workbench, or null if this deployment asked for only one
     * front door.
     *
     * <p>Reached as a dependency rather than reimplemented. {@code WebServer} is public, it
     * takes exactly the collaborators already built here, and running the real one is the only
     * version of this that proves anything — a second, chat-module copy of the dashboard would
     * be a thing that agrees with the chat because the same person wrote both.
     */
    static WebServer dashboardOver(int chatPort, int port, WorkbenchStore store, String tenant,
            Optional<WorkbenchApp.Backend> backend, Alm alm, Workbench workbench, Triage triage,
            Learnings learnings, EvalCaptures captures) {
        if (port == chatPort) {
            System.err.println("WORKBENCH_PORT and WORKBENCH_CHAT_PORT are both " + port
                    + ", so the dashboard is not started. The chat is on " + chatPort + ".");
            return null;
        }
        try {
            return new WebServer(port, store, tenant,
                    backend.map(WorkbenchApp.Backend::model).orElse(""), alm, workbench, triage,
                    learnings, captures);
        } catch (IOException busy) {
            // The chat is what this module is for, and a port somebody else is already on is
            // not a reason to have no console at all. Said out loud, because a second front
            // door that silently did not appear is worse than one that says why.
            System.err.println("The dashboard could not start on " + port
                    + " (" + busy.getMessage() + "), so only the chat is running.");
            return null;
        }
    }

    /** Whether the second front door is wanted. On unless a deployment says otherwise. */
    static boolean dashboardOn() {
        return !"off".equalsIgnoreCase(
                String.valueOf(System.getenv().getOrDefault("WORKBENCH_DASHBOARD", "on")).strip());
    }

    static int dashboardPort() {
        return port(System.getenv("WORKBENCH_PORT"), DASHBOARD_PORT, "WORKBENCH_PORT");
    }

    static List<String> problemsWith(Optional<JiraClient> jira,
            Optional<WorkbenchApp.Backend> backend) {
        List<String> problems = new ArrayList<>();
        if (jira.isEmpty()) {
            problems.add("No ticket system is connected. Set JIRA_BASE_URL, JIRA_EMAIL and "
                    + "JIRA_API_TOKEN, then restart.");
        }
        if (backend.isEmpty()) {
            problems.add("No model is configured. Set WORKBENCH_LLM=anthropic (plus "
                    + "ANTHROPIC_API_KEY) or WORKBENCH_LLM=openrouter (plus OPENROUTER_API_KEY and "
                    + "WORKBENCH_MODEL), then restart.");
        }
        return List.copyOf(problems);
    }

    static Map<String, Object> overview(Alm alm, Optional<WorkbenchApp.Backend> backend,
            List<String> problems) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("product", "Workbench");
        described.put("alm", alm == null ? "" : alm.name());
        described.put("model", backend.map(WorkbenchApp.Backend::model).orElse(""));
        described.put("ready", problems.isEmpty());
        described.put("problems", problems);
        return described;
    }

}
