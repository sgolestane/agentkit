package dev.agentkit.core.planning;

import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.tool.ToolSpec;
import java.util.List;

/**
 * Produces a {@link Plan} for a goal up front, before execution begins.
 *
 * <p>Planning ahead of execution is the "plan-and-execute" pattern: separating a
 * single planning pass from step-by-step execution reduces the mid-stream drift a
 * pure reason-act loop is prone to on long, structured tasks. The shipped
 * implementation is {@link LlmPlanner}; this seam lets a caller substitute a
 * hand-written or heuristic planner and keeps {@link PlanningAgent} testable.
 */
@FunctionalInterface
public interface Planner {

    /**
     * Plans how to achieve {@code goal}.
     *
     * @param goal           the objective to decompose
     * @param availableTools the tools the executor can use, as planning hints; may be empty
     * @return an ordered plan; may be {@link Plan#isEmpty() empty} if the goal needs no decomposition
     */
    Plan plan(Goal goal, List<ToolSpec> availableTools);
}
