package dev.agentkit.examples.onboarding;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.planning.PlanExecution;
import dev.agentkit.examples.deferred.DeferredAction;
import dev.agentkit.examples.deferred.DeferredActions;
import dev.agentkit.examples.planexecute.PlanExecuteAgent;
import dev.agentkit.openrouter.OpenRouterLlmClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Onboards one hire against the in-memory systems and prints what it did: an {@link OnboardingGoal}
 * run by a {@link PlanExecuteAgent} with the prompts from {@link OnboardingConfig}.
 *
 * <p>The hire is an HRIS record in a {@code key: value} file. Without an argument, a bundled sample
 * contractor is used. The policy and prompts come from {@link OnboardingConfig} as usual.
 *
 * <pre>
 * export OPENROUTER_API_KEY=sk-or-...
 * export ONBOARDING_MODEL=anthropic/claude-sonnet-5          # optional
 * export ONBOARDING_HIRE_FILE=./my-hire.properties           # optional; the bundled sample otherwise
 * ./mvnw -f agentkit-examples/pom.xml exec:exec \
 *     -Dexec.mainClass=dev.agentkit.examples.onboarding.OnboardingApp
 * </pre>
 *
 * <p>The scenarios and checks that evaluate this application against a real model live with the
 * tests, in {@code dev.agentkit.examples.onboarding.evals}.
 */
public final class OnboardingApp {

    public static final String DEFAULT_MODEL = "anthropic/claude-sonnet-5";

    private OnboardingApp() {
    }

    public static void main(String[] args) {
        String key = System.getenv(OpenRouterLlmClient.API_KEY_ENV);
        if (key == null || key.isBlank()) {
            System.err.println("Set " + OpenRouterLlmClient.API_KEY_ENV + " to run this example.");
            System.exit(2);
        }
        String model = System.getenv().getOrDefault("ONBOARDING_MODEL", DEFAULT_MODEL);
        LlmClient llm = OpenRouterLlmClient.builder(key).title("agentkit onboarding example").build();

        String hireFile = args.length > 0 ? args[0] : System.getenv("ONBOARDING_HIRE_FILE");
        OnboardingSystems systems = OnboardingSystems.create();
        OnboardingSystems.Worker worker = systems.addWorker(readHire(hireFile));

        OnboardingConfig config = OnboardingConfig.fromEnv();
        PlanExecuteAgent agent = new PlanExecuteAgent(llm, model, config.plannerPrompt(), config.executorPrompt(),
                systems::registry, new ToolCallPrinter(System.out));
        System.out.println("=== Onboarding " + worker.fields().get("name") + " (" + worker.employeeId() + ") ===");
        PlanExecution execution = agent.run(OnboardingGoal.forWorker(config.policy(), worker));
        printRun(execution, systems, System.out);
        System.exit(execution.isSuccess() ? 0 : 1);
    }

    /** Prints the plan, how the run ended, and every deferred action with the goal and tools it will run with. */
    public static void printRun(PlanExecution execution, OnboardingSystems systems, PrintStream out) {
        out.println("\nPlan:");
        List<String> steps = execution.plan().steps();
        for (int i = 0; i < steps.size(); i++) {
            out.println("  " + (i + 1) + ". " + steps.get(i));
        }
        out.printf("%nRun: %s after %d/%d step(s), %d loop turns, %d in / %d out tokens%n",
                execution.overall().stopReason(), execution.stepResults().size(), steps.size(),
                execution.overall().steps(), execution.overall().usage().inputTokens(),
                execution.overall().usage().outputTokens());
        List<String> deferredTools = DeferredActions.restrict(systems.tools(), systems::declared).tools().stream()
                .map(t -> t.name()).toList();
        for (DeferredAction action : systems.deferredActions()) {
            out.println("\nDeferred action " + action.id() + " runs " + action.runOn() + " (" + action.when()
                    + "), scheduled " + action.scheduledOn() + ". It will run with tools " + deferredTools
                    + " and this goal:");
            systems.subjects().resolve(action.subjectKind(), action.subjectId()).ifPresent(subject ->
                    DeferredActions.goalFor(action, subject, action.runOn()).description().lines()
                            .forEach(line -> out.println("    | " + line)));
        }
    }

    /** Reads a {@code key: value} HRIS record from a file, or the bundled sample when {@code path} is null. */
    static Map<String, String> readHire(String path) {
        Properties properties = new Properties();
        try (InputStream in = path == null || path.isBlank()
                ? OnboardingApp.class.getClassLoader().getResourceAsStream("onboarding/sample-hire.properties")
                : Files.newInputStream(Path.of(path.strip()));
             Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the hire from " + (path == null ? "the bundled sample" : path), e);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        properties.stringPropertyNames().forEach(name -> fields.put(name, properties.getProperty(name).strip()));
        return fields;
    }
}
