package dev.agentkit.examples.routine;

import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.routine.RoutineAgent;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.OneLine;
import dev.agentkit.examples.onboarding.OnboardingApp;
import dev.agentkit.openrouter.OpenRouterLlmClient;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a morning's queue of locked-out accounts through {@link UnlockDesk} against a real model, and shows where the
 * model was paid for and where it was not.
 *
 * <pre>{@code
 * OPENROUTER_API_KEY=... ./mvnw -q -f agentkit-examples/pom.xml exec:exec \
 *     -Dexec.mainClass=dev.agentkit.examples.routine.UnlockDeskApp
 * }</pre>
 *
 * <p>{@code UNLOCK_DESK_MODEL} picks the model (an OpenRouter id); the default is the onboarding example's.
 */
public final class UnlockDeskApp {

    private UnlockDeskApp() {
    }

    public static void main(String[] args) {
        String key = System.getenv(OpenRouterLlmClient.API_KEY_ENV);
        if (key == null || key.isBlank()) {
            System.err.println("Set " + OpenRouterLlmClient.API_KEY_ENV + " to run this example.");
            System.exit(2);
        }
        String model = System.getenv().getOrDefault("UNLOCK_DESK_MODEL", OnboardingApp.DEFAULT_MODEL);
        LlmClient llm = OpenRouterLlmClient.builder(key).title("agentkit unlock desk example").build();
        UnlockDeskSystems systems = UnlockDeskSystems.seeded();
        UnlockDesk desk = new UnlockDesk(llm, model, systems, new Printer(System.out));

        List<UnlockDesk.Outcome> outcomes = new ArrayList<>();
        for (UnlockDeskSystems.Account account : systems.accounts()) {
            System.out.println("\n=== Ticket: " + account.email() + " ===");
            UnlockDesk.Outcome outcome = desk.handle(account);
            outcomes.add(outcome);
            System.out.println("  " + outcome.path() + ", " + outcome.modelCalls() + " model call(s): "
                    + OneLine.of(outcome.answer()));
        }
        report(outcomes, desk, System.out);
    }

    /** The table at the end: every ticket, which way it went, and what the model cost. */
    static void report(List<UnlockDesk.Outcome> outcomes, UnlockDesk desk, PrintStream out) {
        out.println("\nTicket                     Path                Model calls   Tokens in/out");
        long calls = 0;
        long in = 0;
        long outTokens = 0;
        int deliberated = 0;
        long deliberatedCalls = 0;
        for (UnlockDesk.Outcome o : outcomes) {
            out.printf("%-26s %-19s %11d   %,d / %,d%n", o.employee(), o.path(), o.modelCalls(), o.inputTokens(),
                    o.outputTokens());
            calls += o.modelCalls();
            in += o.inputTokens();
            outTokens += o.outputTokens();
            if (o.path() == UnlockDesk.Path.MODEL) {
                deliberated++;
                deliberatedCalls += o.modelCalls();
            }
        }
        out.printf("%nModel calls: %d for %d tickets", calls, outcomes.size());
        if (deliberated > 0) {
            long allModel = Math.round((double) deliberatedCalls / deliberated * outcomes.size());
            out.printf(" (about %d if every ticket had gone to the model)", allModel);
        }
        out.printf("%nTokens: %,d in / %,d out%n", in, outTokens);
        out.println("Routine now: " + desk.routine());
        UnlockDeskSystems s = desk.systems();
        out.println("Unlocked: " + s.unlocked().size() + ", MFA reset: " + s.mfaReset().size() + ", notices: "
                + s.notices().size() + ", IT tickets: " + s.tickets());
    }

    /** Prints each tool call, marking the ones a replay made. */
    static final class Printer implements AgentObserver {
        private final PrintStream out;

        Printer(PrintStream out) {
            this.out = out;
        }

        @Override
        public void onStart(AgentRun run, Goal goal) {
            if (RoutineAgent.RUN_NAME.equals(run.name())) {
                out.println("  (replaying the settled routine — no model)");
            }
        }

        @Override
        public void onToolResult(AgentRun run, int step, ToolInvocation proposed, ToolInvocation effective,
                                 ToolResult result, Disposition disposition) {
            String who = RoutineAgent.RUN_NAME.equals(run.name()) ? "replay" : "model ";
            out.println("  " + who + " -> " + effective.name() + " " + effective.arguments()
                    + (result.isError() ? "  !! " : "  ok: ") + OneLine.of(result.content()));
        }
    }
}
