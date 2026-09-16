package dev.agentkit.core.goap;

/** Why a {@link GoapRunner} stopped. */
public enum GoapStop {

    /** The objective is met: every required fact exists. */
    OBJECTIVE_MET,

    /**
     * No plan reaches the objective from where the run got to.
     *
     * <p>Distinct from {@link #NO_PLAN_AT_ALL}: this one had a plan and lost it, because
     * actions failed until nothing was left that could establish the missing facts. The
     * trace shows which, and the message names the fact that ran out of producers.
     */
    NO_ROUTE_LEFT,

    /** No plan existed even before anything ran — the action set cannot reach the objective. */
    NO_PLAN_AT_ALL,

    /**
     * The planner hit its search ceiling. Not the same as unreachable: a plan may well
     * exist, but the action set fans out into more states than the search will explore.
     * Raise the ceiling deliberately, or reduce the number of mutually independent actions.
     */
    PLANNING_GAVE_UP,

    /** The action budget was spent before the objective was met. */
    OUT_OF_ACTIONS,

    /**
     * An action reported success without establishing a fact it declared it produces.
     *
     * <p>Stopped rather than retried, because the planner would keep choosing that action —
     * it is the cheapest route to a fact it claims to produce — and the run would spin until
     * the action budget ran out, with a trace that says nothing about the cause.
     */
    BROKEN_ACTION,

    /** An action threw. The exception is on the result; the trace holds everything before it. */
    ACTION_THREW,

    /**
     * An action's agent parked a tool call: a gate said a person must decide, and the run
     * stopped rather than planning around the question.
     *
     * <p><strong>Stopped, not routed around, and that is the whole point.</strong> Routing
     * around a failed action is what this runner is <em>for</em> — see the class javadoc,
     * which recommends it over a graph precisely when "an action's failure should be routed
     * around rather than propagated". A park is not a failure, and applying that rule to
     * one inverts a control. Measured before this constant existed (#159), on an objective
     * needing {@code sources} with a cheap parking {@code search_web} and a dearer
     * {@code read_archive}:
     *
     * <pre>
     * stop          : OBJECTIVE_MET
     * path          : [search_web, read_archive]
     * isSuccess()   : true
     * </pre>
     *
     * <p>So a gate that stopped a call pending somebody's decision produced a run that
     * reported complete success, having obtained the same fact by another route while the
     * question sat unanswered. That is exactly the "deny and keep going" workaround the
     * parking primitive exists to replace, arriving through the planner instead of through
     * a gate.
     *
     * <p>{@link GoapResult#awaiting()} carries what is pending. The facts established
     * before the park are kept, as with every other stop: a decision may still be made and
     * the run started again, and the work up to the question is still work.
     */
    AWAITING_APPROVAL
}
