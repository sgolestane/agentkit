package dev.agentkit.core.graph;

/** How a node in an {@link AgentGraph} ended up. */
public enum NodeState {

    /** Ran and succeeded. */
    COMPLETED,

    /**
     * Ran and did not succeed — it returned a non-{@code COMPLETED} result or threw.
     * Not {@link #AWAITING_APPROVAL}, which is a question rather than a failure and which
     * the graph treats differently in every respect that matters.
     * Its result is still available, since a failed run's partial output is often the
     * most useful thing in the graph.
     */
    FAILED,

    /**
     * Ran and stopped because a gate wants a person to decide before a tool call proceeds.
     *
     * <p>Its own state rather than a kind of {@link #FAILED}, because the edge conditions
     * read the result and the default one is "it succeeded" — so a park was a failure, and
     * the fallback an author writes as {@code edge("gated", "plan_b", r -> !r.isSuccess())}
     * fired on it. Measured before this existed (#159): the gated node parked, {@code plan_b}
     * ran, and the graph reported {@link GraphStop#COMPLETED}. A question about whether an
     * effect should happen became the effect happening by the other arm of the branch.
     *
     * <p>So {@link AgentGraph} stops scheduling when a node lands here. Nodes already in
     * flight are left to finish — they have spent their tokens and their answers are still
     * answers — and nothing new starts, which is what keeps every downstream and fallback
     * edge unevaluated. {@link GraphResult#failures()} does not count a node in this state,
     * and {@link GraphResult#awaiting()} says what is pending.
     */
    AWAITING_APPROVAL,

    /**
     * Never ran, because no incoming edge was taken: every upstream node either failed
     * (edges default to requiring success), was itself skipped, or its edge condition
     * said no.
     */
    SKIPPED,

    /**
     * Never started, because the run ended first — the timeout elapsed, the caller was
     * interrupted, or the executor went away while this node was still waiting its turn.
     * Distinct from {@link #SKIPPED}, which is a decision the graph made about the work
     * rather than about the clock, and from {@link #FAILED}, which is where a node that
     * <em>had</em> started and was cut short lands: it spent real time and tokens, so
     * calling it "not run" would lose that.
     */
    NOT_RUN
}
