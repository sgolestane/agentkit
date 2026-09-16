package dev.agentkit.core.planning;

import dev.agentkit.core.agent.AgentResult;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of a {@link PlanningAgent} run: the plan it produced, the result of
 * each executed step, and an aggregate {@link AgentResult}.
 *
 * @param plan        the plan that was executed; never {@code null}
 * @param stepResults the result of each step actually run, in order; never
 *                    {@code null}. Shorter than {@link Plan#size()} if execution
 *                    halted early on a non-completing step.
 * @param overall     the aggregate result — cumulative usage and step count across
 *                    all executed steps, the final step's output, and the stop
 *                    reason of the halting step (or {@code COMPLETED}); never {@code null}
 */
public record PlanExecution(Plan plan, List<AgentResult> stepResults, AgentResult overall) {

    public PlanExecution {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(stepResults, "stepResults");
        Objects.requireNonNull(overall, "overall");
        stepResults = List.copyOf(stepResults);
    }

    /** Whether the whole plan completed successfully. */
    public boolean isSuccess() {
        return overall.isSuccess();
    }
}
