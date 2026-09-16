package dev.agentkit.core.supervisor;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import java.util.Objects;

/**
 * The result of running one {@link DelegatedTask}: which subagent ran, the
 * subgoal it pursued, and the {@link AgentResult} it produced.
 *
 * @param subagentName the name of the subagent that ran
 * @param goal         the subgoal it pursued
 * @param result       the outcome of the run; never {@code null}
 */
public record SubagentOutcome(String subagentName, Goal goal, AgentResult result) {

    public SubagentOutcome {
        Objects.requireNonNull(subagentName, "subagentName");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(result, "result");
    }

    /** Whether the delegated run completed successfully. */
    public boolean succeeded() {
        return result.isSuccess();
    }

    /**
     * Whether the delegated run stopped because a gate wants a person to decide.
     *
     * <p>Not a failure, though {@link #succeeded()} answers {@code false} for it and
     * {@link SupervisionResult#failures()} lists it. Both are true as far as they go — the
     * subgoal was not achieved — and both were, before #159, the <em>only</em> things a
     * caller could learn, so a delegation that raised a question was indistinguishable from
     * one that hit its step cap. {@link SupervisionResult#awaiting()} is the question.
     */
    public boolean awaitsAPerson() {
        return result.isAwaitingApproval();
    }
}
