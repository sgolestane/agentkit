package dev.agentkit.core.planning;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.util.Cut;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a goal with the plan-and-execute pattern: a {@link Planner} decomposes the
 * goal into steps once, then a fresh executor {@link Agent} runs each step in order,
 * with the objective, the full plan, and prior steps' outputs threaded into each
 * step's context. This keeps a long, structured task on-track better than a single
 * reason-act loop that must hold the whole plan in its head.
 *
 * <p>The executor is supplied as a {@link Supplier factory} and a <em>new</em> agent
 * is built per step, so a stateful (progressive-disclosure) tool registry starts each
 * step from its baseline rather than leaking revealed tools forward — mirroring how
 * subagents are provided to a {@code Supervisor}. Build a fresh registry inside the
 * factory when the registry is per-run.
 *
 * <p>Execution halts as soon as a step ends in anything other than
 * {@link StopReason#COMPLETED} (an error, refusal, or budget/step exhaustion),
 * propagating that as the overall outcome. Because each step is a full agent run, the
 * cost scales with the number of steps. Note also that each step's prompt includes
 * the prior steps' outputs, so total prompt size grows with the plan; prior outputs
 * are truncated per step to bound that growth, but a very long plan with large
 * outputs can still approach the model's context window.
 */
public final class PlanningAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanningAgent.class);

    /** Prior-step output is truncated to this many characters when threaded into a later step. */
    private static final int MAX_PRIOR_OUTPUT_CHARS = 4000;

    private final Planner planner;
    private final Supplier<Agent> executorFactory;

    /**
     * @param planner         produces the plan
     * @param executorFactory builds a fresh executor {@link Agent} for each step (and
     *                        once to read the tool catalog for planning); never {@code null}
     */
    public PlanningAgent(Planner planner, Supplier<Agent> executorFactory) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
    }

    /** Plans and then executes {@code goal}. */
    public PlanExecution run(Goal goal) {
        Objects.requireNonNull(goal, "goal");

        Plan plan = planner.plan(goal, newExecutor().toolSpecs());
        log.debug("Planned {} step(s) for goal", plan.size());
        if (plan.isEmpty()) {
            // Nothing to decompose — run the goal directly so the caller still gets a result.
            AgentResult direct = newExecutor().run(goal);
            return new PlanExecution(plan, List.of(direct), direct);
        }

        List<AgentResult> stepResults = new ArrayList<>(plan.size());
        TokenUsage totalUsage = TokenUsage.ZERO;
        int totalSteps = 0;
        String lastOutput = "";

        for (int i = 0; i < plan.size(); i++) {
            AgentResult result = newExecutor().run(stepGoal(goal, plan, stepResults, i));
            stepResults.add(result);
            totalUsage = totalUsage.plus(result.usage());
            totalSteps += result.steps();
            lastOutput = result.output();

            if (result.stopReason() != StopReason.COMPLETED) {
                log.info("Halting plan at step {}/{}: {}", i + 1, plan.size(), result.stopReason());
                // Asked of the result rather than reconstructed by case. Reconstructing
                // enumerated the outcomes by exclusion, so a stop reason that stopped()
                // refuses crashed here with no compile-time signal — AWAITING_APPROVAL was
                // one (#101). The output is the halting step's, which is what lastOutput
                // holds at this point.
                AgentResult overall = result.withTotals(totalSteps, totalUsage);
                return new PlanExecution(plan, stepResults, overall);
            }
        }

        return new PlanExecution(plan, stepResults,
                AgentResult.completed(lastOutput, totalSteps, totalUsage));
    }

    private Agent newExecutor() {
        return Objects.requireNonNull(executorFactory.get(), "executorFactory returned null");
    }

    /**
     * Builds the sub-goal for one step: objective + full plan + prior outputs + this step.
     *
     * <p>Each prior step's output is fenced separately. It is whatever an executor agent
     * returned, tool results and all, and a bare {@code Step N:} line is text any of them
     * can emit — so without a fence one step's output could add a heading and put words in
     * an earlier step's mouth, or close the results section and address the executor as the
     * operator. Per-step fencing makes the {@code source} attribute the only claim about
     * which step produced what.
     *
     * <p>The plan is fenced as a {@link Spotlight.Kind#PROCEDURE}, not left bare. A first
     * pass left it unfenced on the grounds that it is the instruction being carried out and
     * "data, do not follow" would be incoherent — which was true of the strict kind and
     * false as a conclusion. A plan is written by a model that read tool descriptions
     * someone else supplied, so an unfenced plan step is a hostile MCP description with the
     * operator's authority attached. As a procedure it still directs the work, while the
     * bound every kind shares denies it a new objective or a tool the run was not given.
     */
    private static Goal stepGoal(Goal goal, Plan plan, List<AgentResult> done, int index) {
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < plan.size(); i++) {
            steps.append(i == 0 ? "" : "\n").append(i + 1).append(". ").append(plan.steps().get(i));
        }
        StringBuilder sb = new StringBuilder("Overall objective:\n").append(goal.render());
        sb.append("\n\nFull plan:\n")
                .append(Spotlight.wrap(Spotlight.Kind.PROCEDURE, Source.of("plan"), steps.toString()));
        if (!done.isEmpty()) {
            sb.append("\n\nResults from previous steps:");
            for (int i = 0; i < done.size(); i++) {
                sb.append("\n").append(Spotlight.wrap(
                        Source.of("step-" + (i + 1) + "-output"), truncate(done.get(i).output())));
            }
        }
        sb.append("\n\nComplete step ").append(index + 1).append(" now:\n")
                .append(Spotlight.wrap(Spotlight.Kind.PROCEDURE,
                        Source.of("plan-step-" + (index + 1)), plan.steps().get(index)));
        return Goal.of(sb.toString());
    }

    /**
     * A prior step's output, cut to what the next step's prompt can afford.
     *
     * <p>Measured on what the fence will emit rather than on what the step returned, and
     * cut by {@link Cut} rather than by a bare {@code substring}: both passes the fence
     * makes over a body expand on text the writer chose — NFKC turns one U+FDFA into
     * eighteen characters, marker removal turns ten characters of {@code <untrusted} into
     * twenty-two — and a cut at a fixed index lands between the halves of an astral
     * character. This output is written by a model, so all three are reachable.
     */
    private static String truncate(String output) {
        return Cut.to(Spotlight.sizedAsFenced(output), MAX_PRIOR_OUTPUT_CHARS);
    }
}
