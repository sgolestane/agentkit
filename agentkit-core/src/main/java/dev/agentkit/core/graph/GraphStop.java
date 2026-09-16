package dev.agentkit.core.graph;

/** Why an {@link AgentGraph} run stopped. */
public enum GraphStop {

    /** Every node reached a terminal state. Nodes may still have failed or been skipped. */
    COMPLETED,

    /** The overall {@code timeout} elapsed. Nodes that had already finished are kept. */
    TIMED_OUT,

    /**
     * The calling thread was interrupted. Reported rather than thrown so a cancelled run
     * still accounts for the tokens it spent, and the thread's interrupt flag is
     * re-asserted before {@code run} returns.
     */
    INTERRUPTED,

    /**
     * The injected executor stopped accepting or completing work — it was shut down
     * from under the run. Distinct from {@link #TIMED_OUT}: nothing about the graph took
     * too long, its executor went away.
     */
    ABANDONED,

    /**
     * A node stopped for a person's decision, so the graph stopped scheduling.
     *
     * <p>Distinct from {@link #COMPLETED} with a failed node, which is what a park used to
     * be. The difference is not cosmetic: under {@code COMPLETED} the graph carried on, so
     * every edge out of the parked node was evaluated, and an author's
     * {@code r -> !r.isSuccess()} fallback took the branch that gets the same effect without
     * the decision (#159).
     *
     * <p>Nodes already running are left to finish rather than cancelled — unlike
     * {@link #TIMED_OUT}, where the clock is the thing being enforced and there is nothing
     * to be gained by waiting. Here the parked node's siblings are unrelated work that has
     * already been paid for, and a park says nothing about them. What stops is the
     * <em>launching</em>: nothing downstream of anything starts, and every node that never
     * started is {@link NodeState#NOT_RUN}, which is what it is.
     *
     * <p>{@link GraphResult#awaiting()} carries the pending calls.
     */
    AWAITING_APPROVAL
}
