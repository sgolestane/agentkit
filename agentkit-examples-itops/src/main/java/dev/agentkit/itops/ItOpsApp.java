package dev.agentkit.itops;

import dev.agentkit.core.memory.Containment;
import dev.agentkit.core.memory.FileMemoryStore;
import dev.agentkit.core.reflect.CorrectionBook;
import java.nio.file.Path;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.ServiceNowConnector;
import dev.agentkit.itops.connector.TicketProvider;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.llm.ScriptedOpsLlm;
import dev.agentkit.itops.runtime.ExecutionRunner;
import dev.agentkit.itops.runtime.IntakeWorker;
import dev.agentkit.itops.runtime.OpsScheduler;
import dev.agentkit.itops.runtime.Reviewers;
import dev.agentkit.itops.runtime.Supervisor;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.web.WebServer;
import dev.agentkit.itops.workflow.WorkflowRunner;
import dev.agentkit.itops.workflow.Workflows;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Wires the platform and starts it.
 *
 * <p>Everything configurable is here and nowhere else: which model, which ticket provider,
 * which identity the integration acts as, and where the approval threshold sits. That last
 * one is the policy dial an operator actually turns — set it to {@code MEDIUM} and every
 * membership change waits for a person; set it to {@code DESTRUCTIVE} and only deletions do.
 *
 * <p><strong>{@code ITOPS_MEMORY_DIR}</strong> turns on remembering why operators refused
 * things (#329), and there is deliberately no default. The store is durable, is replayed
 * into every later run's goal, and is trusted because only someone who can decide an
 * approval writes it — so it must be a directory only this service can write to. Pointed at
 * a world-writable path it stops being that: a local user planting a symlink there before
 * startup gets a forged advisory, attributed to a named operator, into every run. Unset, the
 * platform behaves as it did before #329 and says so once at startup.
 *
 * <pre>{@code
 * ./mvnw -q -pl agentkit-examples-itops -am compile
 * ./mvnw -q -pl agentkit-examples-itops exec:exec
 * # then open http://localhost:8080
 * }</pre>
 *
 * <p>Runs offline by default against a scripted stand-in model. Set {@code ITOPS_LLM=anthropic}
 * and {@code ANTHROPIC_API_KEY} to put a real model behind the same interface; nothing else
 * changes, which is the point — none of the safety story is enforced by the model.
 */
public final class ItOpsApp {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ItOpsApp.class);

    /** One tenant in the demo; every store call is scoped by it regardless. */
    public static final String TENANT = "acme";

    /** The identity the platform acts as when it claims and comments on tickets. */
    private static final String INTEGRATION_USER = "agentkit-integration";

    private ItOpsApp() {
    }

    public static void main(String[] args) throws Exception {
        OpsStore store = new OpsStore();
        TicketProvider tickets = new ServiceNowConnector();
        DirectoryConnector directory = new DirectoryConnector();
        IdentityConnector identity = new IdentityConnector();

        Backend backend = backend();
        LlmClient llm = backend.llm();
        String model = backend.model();

        // Anything HIGH or worse waits for a person. MEDIUM — a routine membership change
        // to a non-privileged group — proceeds on its own. One variable for the two places
        // the line is drawn, because they must be the same line: the model reviewer is
        // skipped exactly where a person decides, and an operator who moves the approval
        // threshold without moving the skip reintroduces the measured defect
        // Reviewers.exceptWhereAPersonDecides exists to close.
        Risk approvalThreshold = Risk.HIGH;

        // Rules first, and the model only where rules cannot see. With the scripted
        // stand-in there is no second model to ask, so the rules stand alone. The model
        // reviewer is not consulted at or above the approval threshold: a person judges
        // those from the approval card, and a model REJECT there is what would prevent the
        // person from ever being asked — see Reviewers.exceptWhereAPersonDecides.
        Supervisor.Reviewer reviewer = llm instanceof ScriptedOpsLlm
                ? Reviewers.goalAlignment()
                : Reviewers.allOf(Reviewers.goalAlignment(),
                        Reviewers.exceptWhereAPersonDecides(
                                Reviewers.model(llm, model), approvalThreshold));

        // What operators have refused, kept across runs (#329). A rejection is the best
        // correction signal this system produces and it was write-only: the note an operator
        // wrote went onto the approval row, into the trail, and nowhere a later run could
        // read it -- so the next similar ticket proposed the same action and cost the same
        // person the same decision. The gate made that safe; nothing made it rarer.
        //
        // OFF unless a directory is named, and no default. A first draft defaulted to
        // java.io.tmpdir + "/itops-memory", which is a durable, security-relevant store on a
        // world-writable path: /tmp is 0777+sticky, FileMemoryStore creates its root 0755,
        // and it pins whatever toRealPath() finds. Demonstrated end to end -- a local user
        // planting a symlink there before startup got a forged advisory attributed to a
        // NAMED operator into every run's goal, which is precisely the trust argument this
        // wiring rests on turned inside out. An env var like every other knob here, because
        // a system property cannot be set through the documented `exec:exec` launch at all.
        String memoryDir = System.getenv("ITOPS_MEMORY_DIR");
        CorrectionBook corrections = null;
        if (memoryDir == null || memoryDir.isBlank()) {
            log.info("ITOPS_MEMORY_DIR is not set, so this deployment will not remember why"
                    + " operators refused things; set it to a directory only this service can"
                    + " write to in order to turn that on");
        } else {
            // PINNED_OR_FAIL by default, and this is the wiring that most deserves it: the
            // paragraph above is about a planted symlink turning this store into a channel
            // for forged advisories attributed to a named operator, and containment is what
            // closes it. Opting in to durable corrections is already deliberate here — the
            // directory is an env var nobody sets by accident — so refusing to start where
            // the guarantee is unavailable costs a developer one more variable and tells
            // them exactly what they are turning off.
            corrections = new CorrectionBook(new FileMemoryStore(Path.of(memoryDir),
                    containmentFromEnv()));
        }

        ExecutionRunner runner = new ExecutionRunner(store, llm, model, tickets, directory,
                identity, INTEGRATION_USER, reviewer, approvalThreshold, corrections);
        IntakeWorker intake = new IntakeWorker(store, tickets, runner, TENANT, "it-ops-agent");
        WorkflowRunner workflows = new WorkflowRunner(store, tickets, directory, identity,
                INTEGRATION_USER, Risk.HIGH,
                // The trust floor (#162). Both shipped workflows read only the identity
                // provider, so nothing lowers this and they run exactly as before. A graph
                // that reaches into ticketing does lower it, and from then on a MEDIUM
                // action — assigning, resolving, a membership change to an ordinary group —
                // waits for a person instead of proceeding on its grade alone. There is no
                // model in this runner's loop and its supervisor is built with no reviewer,
                // so this is the only control here that reacts to having read a ticket.
                Risk.MEDIUM,
                // And standing refusals (#329). A workflow has no model, so the rules about
                // a model's behaviour do not apply to it -- but a person saying "not in this
                // capability until somebody lifts it" is not about the model, and the
                // shipped offboarding workflow removes group members. Measured without this:
                // the workflow ran that node to COMPLETED while the refusal was in force.
                corrections);

        OpsScheduler scheduler = new OpsScheduler();
        scheduler.register(new OpsScheduler.Schedule("ServiceNow intake", "*/5 * * * *",
                        Duration.ofSeconds(60), "it-ops-agent",
                        Map.of("assignment_group", "AgentKit", "lookback_minutes", 60), true),
                input -> intake.tick(String.valueOf(input.get("assignment_group")),
                        Duration.ofMinutes(60), 10).size());

        int port = Integer.parseInt(System.getenv().getOrDefault("ITOPS_PORT", "8080"));
        WebServer web = new WebServer(port, store, TENANT, tickets, runner, intake, scheduler,
                workflows, Workflows.seeded());
        web.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            web.close();
            scheduler.close();
        }));

        System.out.println("""

                Agentic IT Operations — running on http://localhost:%d
                  model:     %s
                  provider:  %s
                  approvals: anything %s or above waits for a human

                Try:
                  - "show me recent tickets"
                  - "fix INC0012345"      routine access request, completes on its own
                  - "fix INC0012348"      privileged group, parks for approval
                  - "fix INC0012346"      account deletion, parks for approval
                  - "fix INC0012347"      no capability for it, declines and leaves it alone
                The scheduler sweeps for new tickets every minute on its own.
                """.formatted(port, model, tickets.name(), Risk.HIGH));

        Thread.currentThread().join();
    }

    /** A model and the identifier requests to it carry. */
    public record Backend(LlmClient llm, String model) {}

    /** The class reflection reaches for, named once so a test can check it resolves. */
    static final String ANTHROPIC_CLIENT = "dev.agentkit.anthropic.AnthropicLlmClient";

    /** Its factory, and the field carrying the identifier to send when nobody names one. */
    static final String ANTHROPIC_FACTORY = "fromEnv";
    static final String ANTHROPIC_DEFAULT_MODEL = "DEFAULT_MODEL";

    /** The same pair for OpenRouter, which reads {@code OPENROUTER_API_KEY}. */
    static final String OPENROUTER_CLIENT = "dev.agentkit.openrouter.OpenRouterLlmClient";
    static final String OPENROUTER_FACTORY = "fromEnv";

    /**
     * The real model this deployment asks for, or empty when it asks for none or cannot have
     * one.
     *
     * <p>Reflective lookup so the Anthropic client stays an optional dependency at runtime:
     * the demo's whole claim is that it works without one, and a hard reference would make
     * the offline path depend on a jar it never calls.
     *
     * <p><strong>Optional rather than a fallback, and that is the point of the seam.</strong>
     * {@link #main} wants a stand-in when there is no key; an eval wants to <em>skip</em>,
     * because a suite that quietly scores the scripted plan and reports green has measured
     * the fixture. One resolution serves both and the caller decides what an absence means.
     *
     * <p><strong>Two names in here were wrong and the reflection hid it</strong> (measured by
     * {@code TheRealModelPathResolvesTest}). The factory was spelt {@code fromEnvironment},
     * which does not exist, so every {@code ITOPS_LLM=anthropic} run threw
     * {@code NoSuchMethodException}, printed one line to stderr and ran the scripted stand-in
     * — the demo's "nothing else changes with a real model behind it" claim was never once
     * exercised. And the model identifier came from {@code ITOPS_MODEL}, whose default is
     * {@code scripted}, so a run that got past the first defect would have sent
     * {@code model: "scripted"} to the API. Both are the hazard a reflective lookup carries:
     * a name that does not resolve is a runtime warning where a direct call is a compile
     * error. The names are constants now and a test resolves every one of them.
     */
    /**
     * Why a reflective backend lookup failed, in the words of whatever actually failed.
     *
     * <p>Written because the message this produces was useless at the one moment it was
     * read. A factory that throws comes back from {@code Method.invoke} wrapped in an
     * {@link java.lang.reflect.InvocationTargetException}, whose {@code toString} is the
     * class name and nothing else — so an operator whose {@code OPENROUTER_API_KEY} was set
     * but not exported was told:
     *
     * <pre>
     * ITOPS_LLM=openrouter was requested but the client could not be built
     * (java.lang.reflect.InvocationTargetException).
     * </pre>
     *
     * <p>The cause said {@code Environment variable OPENROUTER_API_KEY is not set}, which is
     * the whole answer, and the wrapper hid it. That is the same failure the reflective
     * spelling bug had — a diagnostic that reports the mechanism rather than the fault — and
     * it is worse here, because this line exists only to be read when something has gone
     * wrong.
     */
    /** {@link #because} for the test that pins it; the method itself stays private. */
    static String becauseForTest(Throwable failure) {
        return because(failure);
    }

    private static String because(Throwable failure) {
        Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException
                ? failure.getCause()
                : failure;
        Throwable reported = cause == null ? failure : cause;
        String message = reported.getMessage();
        return message == null || message.isBlank() ? reported.toString()
                : reported.getClass().getSimpleName() + ": " + message;
    }

    public static Optional<Backend> realModel() {
        String backend = System.getenv().getOrDefault("ITOPS_LLM", "scripted");
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
            // The client's own default, read rather than copied: a model identifier written
            // into this file is one more place to go stale, and ITOPS_MODEL's default says
            // "scripted", which is not something to send an API.
            String model = System.getenv().getOrDefault("ITOPS_MODEL",
                    String.valueOf(type.getField(ANTHROPIC_DEFAULT_MODEL).get(null)));
            return Optional.of(new Backend(llm, model));
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            System.err.println("ITOPS_LLM=anthropic was requested but the client could not be "
                    + "built — " + because(unavailable));
            return Optional.empty();
        }
    }

    /**
     * OpenRouter, which needs an explicit {@code ITOPS_MODEL} and has no default to fall back
     * on.
     *
     * <p>Deliberately refused rather than defaulted. OpenRouter routes to many vendors and
     * its identifiers are qualified — {@code anthropic/claude-sonnet-4.5},
     * {@code openai/gpt-4o} — so there is no one identifier this file could pick that is not
     * a guess about somebody else's account and billing. Picking one is also the defect this
     * method already carries a scar from: {@code ITOPS_MODEL}'s own default is
     * {@code scripted}, and a run that sent that to an API was what the reflective spelling
     * bug would have produced next.
     *
     * <p>Empty, not a stand-in, for {@link #realModel}'s stated reason: an eval that asked
     * for a real model and silently scored the scripted plan has measured its own fixture.
     */
    private static Optional<Backend> openRouter() {
        String model = System.getenv("ITOPS_MODEL");
        if (model == null || model.isBlank()) {
            System.err.println("ITOPS_LLM=openrouter also needs ITOPS_MODEL, which OpenRouter "
                    + "has no default for: its identifiers name a vendor as well as a model, "
                    + "for example anthropic/claude-sonnet-4.5.");
            return Optional.empty();
        }
        try {
            Class<?> type = Class.forName(OPENROUTER_CLIENT);
            LlmClient llm = (LlmClient) type.getMethod(OPENROUTER_FACTORY).invoke(null);
            return Optional.of(new Backend(llm, model));
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            System.err.println("ITOPS_LLM=openrouter was requested but the client could not be "
                    + "built — " + because(unavailable)
                    + ". A key set in a shell but not exported does not reach a forked test"
                    + " JVM; check `export OPENROUTER_API_KEY`.");
            return Optional.empty();
        }
    }

    /** The real model if there is one, and the offline stand-in otherwise. */
    private static Backend backend() {
        return realModel().orElseGet(() -> new Backend(new ScriptedOpsLlm(),
                System.getenv().getOrDefault("ITOPS_MODEL", "scripted")));
    }

    /**
     * Whether this deployment insists the correction store can actually contain what it holds.
     *
     * <p>Defaults to refusing rather than proceeding, which is the safe end of a choice the
     * framework deliberately gives no default to. A developer on a platform with no
     * {@code openat} — anything but Linux — sets {@code ITOPS_MEMORY_CONTAINMENT=best-effort}
     * and has then said, in their own shell history, what they turned off.
     *
     * <p>An env var like every other knob here, for the reason stated above: a system property
     * cannot be set through the documented {@code exec:exec} launch at all.
     */
    private static Containment containmentFromEnv() {
        String asked = System.getenv().getOrDefault("ITOPS_MEMORY_CONTAINMENT", "pinned")
                .trim().toLowerCase(java.util.Locale.ROOT);
        return switch (asked) {
            case "pinned" -> Containment.PINNED_OR_FAIL;
            case "best-effort" -> Containment.BEST_EFFORT;
            // Not defaulted. An unreadable value here is somebody trying to say something
            // about containment and failing, which is the one case where guessing is worst.
            default -> throw new IllegalArgumentException("ITOPS_MEMORY_CONTAINMENT is '"
                    + asked + "'; it must be 'pinned' or 'best-effort'.");
        };
    }
}
