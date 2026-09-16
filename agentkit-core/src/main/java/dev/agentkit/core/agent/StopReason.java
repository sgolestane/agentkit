package dev.agentkit.core.agent;

/**
 * Why an agent run ended.
 */
public enum StopReason {
    /** The agent produced a final answer and considers the goal complete. */
    COMPLETED,
    /** The configured maximum number of steps was reached. */
    MAX_STEPS,
    /**
     * The configured cumulative token or cost budget was exhausted. The budget is a
     * {@code TokenBudget} either way, applied in-process with {@code BudgetLlmClient}
     * or durably with {@code DurableAgentRun.withBudget(...)}.
     */
    BUDGET_EXHAUSTED,
    /**
     * A single turn's output was truncated at the per-call {@code maxTokens} limit.
     * Distinct from {@link #BUDGET_EXHAUSTED}, which is a run-wide spend cap: this is
     * one response cut short, so any tool call in that turn may be incomplete.
     */
    OUTPUT_TRUNCATED,
    /** A verification or guardrail check rejected the outcome and no recovery was possible. */
    VERIFICATION_FAILED,
    /** The model or a safety layer refused to continue. */
    REFUSED,
    /** The turn paused (e.g. a long-running server-side tool) and the run does not support resuming it. */
    PAUSED,
    /**
     * A tool gate stopped a call and said a person has to decide before it can proceed,
     * and this runner has nobody to ask.
     *
     * <p>Distinct from {@link #REFUSED}, which is somebody saying no, and from
     * {@link #PAUSED}, which is the model's own turn suspending mid-flight. Here the policy
     * has not refused and the model has not stopped: the run is over because a question is
     * outstanding. {@code AgentResult.awaiting()} carries what it is.
     *
     * <p>The in-process loop stops rather than blocking a thread on the answer. A blocking
     * approver is still available and still blocks — {@code ToolGates.requireApproval} —
     * and is the right choice for an interactive harness that can prompt. This is for the
     * unattended case, where the answer will arrive later, through some other door, and
     * holding a thread until then is not a plan.
     *
     * <p>The durable runner never returns this from a park it can wait out; it blocks on a
     * signal instead. It does return it when nobody answered before the deadline, which is
     * the same fact — the run ended with a question outstanding.
     */
    AWAITING_APPROVAL,
    /** An unrecoverable error occurred while running the agent. */
    ERROR,
    /**
     * The run stopped because the thread carrying it was interrupted.
     *
     * <p>This said "cancelled by the caller" while nothing produced it. {@code Agent.run}
     * now does (#183), and the flag is the only evidence it has — so the reason says what
     * was observed rather than who did it. In process that is usually the caller, who owns
     * the thread; it can also be a tool, gate or observer that set the flag and left it,
     * which the loop cannot distinguish and does not guess.
     *
     * <p>An ordinary stop rather than {@link #ERROR}: nothing failed, and the steps already
     * taken and the model's last text are still on the result. The flag is put back before
     * {@code run} returns, so a caller who did cancel still finds it set.
     */
    CANCELLED
}
