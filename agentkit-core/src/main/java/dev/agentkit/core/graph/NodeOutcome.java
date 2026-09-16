package dev.agentkit.core.graph;

import dev.agentkit.core.agent.AgentResult;
import java.util.Objects;
import java.util.Optional;

/**
 * What happened to one node of an {@link AgentGraph}.
 *
 * @param name   the node's name; never {@code null}
 * @param state  how it ended up; never {@code null}
 * @param result the run's result, present exactly when the node ran — that is, for
 *               {@link NodeState#COMPLETED}, {@link NodeState#FAILED} and
 *               {@link NodeState#AWAITING_APPROVAL}, and absent for
 *               {@link NodeState#SKIPPED} and {@link NodeState#NOT_RUN}
 */
public record NodeOutcome(String name, NodeState state, Optional<AgentResult> result) {

    public NodeOutcome {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(result, "result");
        boolean ran = state == NodeState.COMPLETED || state == NodeState.FAILED
                || state == NodeState.AWAITING_APPROVAL;
        if (ran != result.isPresent()) {
            throw new IllegalArgumentException(
                    "A result must be present if and only if the node ran (state=" + state
                            + ", result present=" + result.isPresent() + ")");
        }
    }

    static NodeOutcome of(String name, AgentResult result) {
        Objects.requireNonNull(result, "result");
        return new NodeOutcome(name, state(result), Optional.of(result));
    }

    /**
     * Three outcomes, not two.
     *
     * <p>The order of the two questions does not matter and is not claimed to: the two stop
     * reasons are mutually exclusive, so asking about success first and the park second
     * classifies every result identically. Checked, by mutation — the reordered version
     * survives, and it is equivalent rather than untested. What was wrong before #159 was
     * that the second question was not asked at all: {@code isSuccess() ? COMPLETED : FAILED}
     * filed a call somebody was being asked about under the state that a graph's fallback
     * edges are drawn for.
     */
    private static NodeState state(AgentResult result) {
        if (result.isAwaitingApproval()) {
            return NodeState.AWAITING_APPROVAL;
        }
        return result.isSuccess() ? NodeState.COMPLETED : NodeState.FAILED;
    }

    static NodeOutcome skipped(String name) {
        return new NodeOutcome(name, NodeState.SKIPPED, Optional.empty());
    }

    static NodeOutcome notRun(String name) {
        return new NodeOutcome(name, NodeState.NOT_RUN, Optional.empty());
    }

    /** Whether the node ran at all, successfully or not. */
    public boolean ran() {
        return result.isPresent();
    }

    /** Whether the node stopped because a gate wants a person to decide. */
    public boolean awaitsAPerson() {
        return state == NodeState.AWAITING_APPROVAL;
    }

    /** The node's output text, if it ran and succeeded. */
    public Optional<String> output() {
        return result.filter(AgentResult::isSuccess).map(AgentResult::output);
    }
}
