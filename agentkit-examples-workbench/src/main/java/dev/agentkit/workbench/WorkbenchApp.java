package dev.agentkit.workbench;

import dev.agentkit.core.memory.Containment;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.workbench.connector.Alm;
import dev.agentkit.workbench.connector.JiraClient;
import dev.agentkit.workbench.runtime.AutoPilot;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.Triage;
import dev.agentkit.workbench.runtime.Workbench;
import dev.agentkit.workbench.store.WorkbenchStore;
import dev.agentkit.workbench.web.WebServer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Wires the workbench and starts it.
 *
 * <p>Everything configurable is here and nowhere else. Two real connections, both from the
 * environment, both optional at boot so the console can say what is missing instead of
 * refusing to start:
 *
 * <ul>
 *   <li><strong>The ALM</strong> — a live Jira: {@code JIRA_BASE_URL}, {@code JIRA_EMAIL},
 *       {@code JIRA_API_TOKEN}, optionally {@code WORKBENCH_JQL} to scope the inbox. There is
 *       no simulated ticket source in this module.</li>
 *   <li><strong>The model</strong> — {@code WORKBENCH_LLM=anthropic} (plus
 *       {@code ANTHROPIC_API_KEY}) or {@code WORKBENCH_LLM=openrouter} (plus
 *       {@code OPENROUTER_API_KEY} and an explicit {@code WORKBENCH_MODEL}, since OpenRouter
 *       identifiers name a vendor as well as a model).</li>
 * </ul>
 *
 * <p>Durable customer knowledge lives under {@code WORKBENCH_DATA_DIR} (default {@code data}),
 * in a file-backed memory store the lesson book appends to — delete the directory and the agent
 * forgets the customer.
 *
 * <pre>{@code
 * ./mvnw -q -DskipTests -pl agentkit-examples-workbench -am install
 * JIRA_BASE_URL=… JIRA_EMAIL=… JIRA_API_TOKEN=… \
 * WORKBENCH_LLM=openrouter WORKBENCH_MODEL=anthropic/claude-sonnet-4.5 OPENROUTER_API_KEY=… \
 * ./mvnw -q -pl agentkit-examples-workbench exec:exec
 * # then open http://localhost:8082
 * }</pre>
 */
public final class WorkbenchApp {

    /** One tenant in the demo; every store call is scoped by it regardless. */
    public static final String TENANT = "default";

    private WorkbenchApp() {
    }

    public static void main(String[] args) throws Exception {
        WorkbenchStore store = new WorkbenchStore();
        Path dataDir = Path.of(System.getenv().getOrDefault("WORKBENCH_DATA_DIR", "data"));
        java.nio.file.Files.createDirectories(dataDir.resolve("memory"));
        // BEST_EFFORT, stated rather than defaulted. This is a demo that has to start on
        // whatever laptop somebody clones it onto, and on anything but Linux the JDK
        // returns no SecureDirectoryStream, so the pinned descent is unavailable and
        // PINNED_OR_FAIL would refuse to boot. A real deployment writes the other one —
        // see the Containment javadoc, and ItOpsApp for a wiring that does.
        Learnings learnings = new Learnings(
                MemoryStore.file(dataDir.resolve("memory"), Containment.BEST_EFFORT), TENANT);

        Optional<JiraClient> jira = JiraClient.fromEnv();
        Optional<Backend> backend = realModel();

        Alm alm = jira.orElse(null);
        dev.agentkit.workbench.capture.EvalCaptures captures = alm == null ? null
                : new dev.agentkit.workbench.capture.EvalCaptures(dataDir.resolve("evals"),
                        store, alm, learnings, TENANT);
        Workbench workbench = null;
        Triage triage = null;
        AutoPilot autoPilot = null;
        if (alm != null && backend.isPresent()) {
            // Rejections teach (#331): the reason a person gives when refusing an action is
            // kept in the same durable store as the learnings, advised into later runs, and
            // — when the person says so — enforced by a gate until somebody lifts it.
            dev.agentkit.core.memory.MemoryStore memory =
                    dev.agentkit.core.memory.MemoryStore.file(dataDir.resolve("memory"),
                            dev.agentkit.core.memory.Containment.BEST_EFFORT);
            dev.agentkit.core.reflect.CorrectionBook corrections =
                    new dev.agentkit.core.reflect.CorrectionBook(memory);
            // And its mirror: the capabilities this tenant has stopped wanting to be asked
            // about. Durable in the same store, because "I already told you this" that
            // does not survive a restart is a preference rather than trust.
            dev.agentkit.workbench.runtime.StandingApprovals trusted =
                    new dev.agentkit.workbench.runtime.StandingApprovals(memory);
            workbench = new Workbench(store, backend.get().llm(), backend.get().model(), alm,
                    learnings, TENANT, corrections, trusted);
            triage = new Triage(store, backend.get().llm(), backend.get().model(), alm,
                    learnings, TENANT);
            autoPilot = new AutoPilot(store, alm, triage, workbench, TENANT);
            autoPilot.start(Duration.ofSeconds(Long.parseLong(
                    System.getenv().getOrDefault("WORKBENCH_AUTOPILOT_SECONDS", "90"))));
        }

        int port = Integer.parseInt(System.getenv().getOrDefault("WORKBENCH_PORT", "8082"));
        WebServer web = new WebServer(port, store, TENANT,
                backend.map(Backend::model).orElse(""), alm, workbench, triage, learnings,
                captures);
        web.start();

        AutoPilot closing = autoPilot;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            web.close();
            if (closing != null) {
                closing.close();
            }
        }));

        System.out.println("""

                The agent's workbench, on http://localhost:%d
                  ALM:   %s
                  model: %s

                The inbox is your real Jira queue. Pick a ticket and ask "what would the agent
                do?", let it work under your supervision, answer what it asks — every answer
                becomes knowledge — and, when you are comfortable, automate that category.
                """.formatted(port,
                jira.map(j -> j.baseUrl() + " (connected)")
                        .orElse("not connected — set JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN"),
                backend.map(Backend::model)
                        .orElse("not configured — set WORKBENCH_LLM (and its key)")));

        Thread.currentThread().join();
    }

    /** A model and the identifier requests to it carry. */
    public record Backend(LlmClient llm, String model) {}

    /** The class reflection reaches for, named once so a test can check it resolves. */
    static final String ANTHROPIC_CLIENT = "dev.agentkit.anthropic.AnthropicLlmClient";
    static final String ANTHROPIC_FACTORY = "fromEnv";
    static final String ANTHROPIC_DEFAULT_MODEL = "DEFAULT_MODEL";

    /** The same pair for OpenRouter, which reads {@code OPENROUTER_API_KEY}. */
    static final String OPENROUTER_CLIENT = "dev.agentkit.openrouter.OpenRouterLlmClient";
    static final String OPENROUTER_FACTORY = "fromEnv";

    /**
     * The real model this deployment asks for, or empty when it asks for none or cannot
     * have one. Reflective so the vendor jars stay optional at runtime; the names are
     * constants because a reflective misspelling is a runtime warning where a direct call
     * is a compile error — the itops example carries the scar and the test pattern.
     */
    public static Optional<Backend> realModel() {
        String backend = System.getenv().getOrDefault("WORKBENCH_LLM", "");
        if ("anthropic".equalsIgnoreCase(backend)) {
            return anthropic();
        }
        if ("openrouter".equalsIgnoreCase(backend)) {
            return openRouter();
        }
        return Optional.empty();
    }

    private static Optional<Backend> anthropic() {
        try {
            Class<?> type = Class.forName(ANTHROPIC_CLIENT);
            LlmClient llm = (LlmClient) type.getMethod(ANTHROPIC_FACTORY).invoke(null);
            String model = System.getenv().getOrDefault("WORKBENCH_MODEL",
                    String.valueOf(type.getField(ANTHROPIC_DEFAULT_MODEL).get(null)));
            return Optional.of(new Backend(llm, model));
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            System.err.println("WORKBENCH_LLM=anthropic was requested but the client could not be "
                    + "built — " + because(unavailable));
            return Optional.empty();
        }
    }

    /**
     * OpenRouter needs an explicit {@code WORKBENCH_MODEL}: its identifiers are qualified
     * ({@code anthropic/claude-sonnet-4.5}), so any default this file picked would be a
     * guess about somebody else's account and billing.
     */
    private static Optional<Backend> openRouter() {
        String model = System.getenv("WORKBENCH_MODEL");
        if (model == null || model.isBlank()) {
            System.err.println("WORKBENCH_LLM=openrouter also needs WORKBENCH_MODEL, which OpenRouter "
                    + "has no default for — its identifiers name a vendor as well as a model, "
                    + "for example anthropic/claude-sonnet-4.5.");
            return Optional.empty();
        }
        try {
            Class<?> type = Class.forName(OPENROUTER_CLIENT);
            LlmClient llm = (LlmClient) type.getMethod(OPENROUTER_FACTORY).invoke(null);
            return Optional.of(new Backend(llm, model));
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            System.err.println("WORKBENCH_LLM=openrouter was requested but the client could not be "
                    + "built — " + because(unavailable)
                    + ". A key set in a shell but not exported does not reach a forked JVM; "
                    + "check `export OPENROUTER_API_KEY`.");
            return Optional.empty();
        }
    }

    /** {@link #because} for the test that pins it; the method itself stays private. */
    static String becauseForTest(Throwable failure) {
        return because(failure);
    }

    /** Why a reflective lookup failed, in the words of whatever actually failed. */
    private static String because(Throwable failure) {
        Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException
                ? failure.getCause()
                : failure;
        Throwable reported = cause == null ? failure : cause;
        String message = reported.getMessage();
        return message == null || message.isBlank() ? reported.toString()
                : reported.getClass().getSimpleName() + ": " + message;
    }
}
