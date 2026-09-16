package dev.agentkit.core.agent;

import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.reliability.PendingApproval;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of an agent run.
 *
 * <h2>What an empty {@code awaiting} does and does not mean (#285)</h2>
 *
 * <p>{@link #awaiting()} names calls <em>this</em> run's gate parked. A park raised one
 * level down, in an agent this run reached <strong>through a {@link
 * dev.agentkit.core.tool.Tool}</strong>, does not appear there and cannot: a tool hands the
 * loop a {@link dev.agentkit.core.tool.ToolResult}, which has no channel for "a person is
 * needed". Two shipped things have that shape. A <strong>subagent</strong> reached through
 * {@code SubagentTools.delegateTool}: the child's own result <em>is</em>
 * {@code AWAITING_APPROVAL} with a populated {@code awaiting()}, the delegation tool turns
 * it into an error result the supervisor's model is told to stop on, and this run finishes
 * {@link StopReason#COMPLETED} with {@code awaiting()} empty. And a <strong>call a script
 * makes</strong> through {@code CodeExecutionTool}'s bridge, where the bridge gate parks:
 * {@code ToolBridges} refuses it outright — nobody can be asked from inside a sandbox and
 * no resume lands back on that line — and the fact does not travel out of the
 * {@code run_code} call either.
 *
 * <p>Structural rather than accidental: the canonical constructor enforces that
 * {@code awaiting} is non-empty if and only if the stop reason is
 * {@link StopReason#AWAITING_APPROVAL}, so a result that finished normally has nowhere to
 * put a child's question even in principle. That biconditional is load-bearing and is not
 * the thing to relax.
 *
 * <p>Every composition that returns its <em>own</em> result type does surface its children's
 * parks — {@code SupervisionResult}, {@code GraphResult}, {@code GoapResult},
 * {@code RefineResult} and {@code Critique} each have an {@code awaiting()} — and
 * {@code ReflectiveAgent}, {@code PlanningAgent} and {@code SelfVerifyingAgent} carry this
 * one through {@link #withTotals}.
 *
 * <p>A harness that must see a delegated park has two ways to, and this used to name only
 * the dearer one (#302). The sentence here read: <em>A harness that must see a delegated
 * park should use {@code Supervisor.fanOut} rather than the {@code delegate} tool.</em>
 * {@code fanOut} does carry it, on {@code SupervisionResult.awaiting()} — but it is
 * programmatic decomposition, so taking that advice means giving up the model-driven kind,
 * and it was never needed for this. {@code Subagent.handling} is handed the child's whole
 * {@code AgentResult} before {@code SubagentTools.delegateTool} flattens it into a
 * {@code ToolResult}, so a wrapper around the subagent — {@code SubagentTools.recordingParks}
 * is one — records the park with the {@code delegate} tool still in use. What no wrapper
 * changes is the paragraph above: the fact is observable, and <em>this</em> result still
 * cannot carry it.
 *
 * <p>Whether the fact <em>should</em> reach a caller here was decided rather than
 * overlooked (#159): the parent cannot resume the child by approving the child's call —
 * the child's run is over, and there is no in-process resume (#157). A question a caller
 * can see and cannot answer is worth less than it looks. Giving {@code ToolResult} a
 * channel for it changes the loop's contract for every tool, which is a decision nobody has
 * argued for yet.
 *
 * @param stopReason why the run ended; never {@code null}
 * @param output     the final textual output produced by the agent; never
 *                   {@code null} (may be empty)
 * @param steps      the number of reasoning/tool steps taken
 * @param usage      cumulative token usage of the loop's own model turns; never
 *                   {@code null}. This is <em>not</em> the run's whole spend: the
 *                   framework also calls the model from context compaction,
 *                   verification, reflection, planning, critics and synthesis, each
 *                   through its own {@code LlmClient} that the loop cannot see. In a
 *                   compacting agent those calls can dominate. Wrap the clients in a
 *                   {@code UsageMeter} to account for a whole run.
 * @param error      the error that ended the run, if {@link StopReason#ERROR};
 *                   otherwise empty
 * @param awaiting   the tool calls <em>this run's own gate</em> stopped pending somebody's
 *                   decision, if {@link StopReason#AWAITING_APPROVAL}; otherwise empty.
 *                   Never {@code null}. What it excludes is in the record javadoc above,
 *                   and it is not an omission — see there before trusting an empty list
 */
public record AgentResult(StopReason stopReason, String output, int steps, TokenUsage usage,
                          Optional<Throwable> error, List<PendingApproval> awaiting) {

    public AgentResult {
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(error, "error");
        awaiting = List.copyOf(Objects.requireNonNull(awaiting, "awaiting"));
        if (steps < 0) {
            throw new IllegalArgumentException("steps must be >= 0, was " + steps);
        }
        if (error.isPresent() != (stopReason == StopReason.ERROR)) {
            throw new IllegalArgumentException(
                    "error must be present if and only if stopReason is ERROR (stopReason="
                            + stopReason + ", error present=" + error.isPresent() + ")");
        }
        if (awaiting.isEmpty() == (stopReason == StopReason.AWAITING_APPROVAL)) {
            // The same biconditional the error component gets, and for the same reason: a
            // stop reason that names a pending question with nothing pending tells a caller
            // to go and find something that is not there, and a pending question under any
            // other stop reason is a call somebody still owes an answer on, filed under a
            // heading nobody will read.
            throw new IllegalArgumentException(
                    "awaiting must be non-empty if and only if stopReason is AWAITING_APPROVAL"
                            + " (stopReason=" + stopReason + ", awaiting=" + awaiting.size() + ")");
        }
    }

    /**
     * The shape without a pending question, which is what every other stop builds.
     *
     * <p>Not a legacy form: all four factories below go through it. It exists so a caller
     * that has no question outstanding does not have to say so.
     */
    public AgentResult(StopReason stopReason, String output, int steps, TokenUsage usage,
                       Optional<Throwable> error) {
        this(stopReason, output, steps, usage, error, List.of());
    }

    public boolean isSuccess() {
        return stopReason == StopReason.COMPLETED;
    }

    /**
     * Whether this run ended because a gate stopped a call and a person has to decide.
     *
     * <p>One spelling, because six composing runners each need to ask it and a park that
     * one of them spells differently is a park that one of them misses. Measured before
     * this existed (#159): every composition point in the repository asked
     * {@link #isSuccess()} instead, so a parked inner run reached the outer one as an
     * ordinary failure — and a composer told "failed" routes around. A {@code GoapRunner}
     * with two actions producing the same fact abandoned the parked one, took the other,
     * and reported {@code OBJECTIVE_MET}: a person was asked whether the effect should
     * happen, and the run achieved it another way while the question sat unanswered.
     *
     * <p>Equivalent to {@code stopReason() == StopReason.AWAITING_APPROVAL}, and by the
     * constructor's biconditional also to {@code !awaiting().isEmpty()}. Neither is worth
     * a caller re-deriving: the point of the name is that the question gets asked at all.
     */
    public boolean isAwaitingApproval() {
        return stopReason == StopReason.AWAITING_APPROVAL;
    }

    public static AgentResult completed(String output, int steps) {
        return completed(output, steps, TokenUsage.ZERO);
    }

    public static AgentResult completed(String output, int steps, TokenUsage usage) {
        return new AgentResult(StopReason.COMPLETED, output, steps, usage, Optional.empty());
    }

    /**
     * Builds a non-error stop result. Use {@link #completed(String, int)} for a
     * successful finish and {@link #failed(Throwable, int)} for an error.
     *
     * @throws IllegalArgumentException if {@code reason} is {@link StopReason#ERROR}
     */
    public static AgentResult stopped(StopReason reason, String output, int steps) {
        return stopped(reason, output, steps, TokenUsage.ZERO);
    }

    public static AgentResult stopped(StopReason reason, String output, int steps, TokenUsage usage) {
        Objects.requireNonNull(reason, "reason");
        if (reason == StopReason.ERROR) {
            throw new IllegalArgumentException("Use failed(Throwable, int) to build an ERROR result");
        }
        if (reason == StopReason.AWAITING_APPROVAL) {
            // Same shape as the ERROR refusal above. This overload has no way to say what is
            // pending, and the constructor would refuse the empty list anyway; naming the
            // factory that works is more use than the invariant's own message.
            throw new IllegalArgumentException(
                    "Use awaitingApproval(...) to build an AWAITING_APPROVAL result");
        }
        return new AgentResult(reason, output, steps, usage, Optional.empty());
    }

    public static AgentResult failed(Throwable error, int steps) {
        return failed(error, steps, TokenUsage.ZERO);
    }

    public static AgentResult failed(Throwable error, int steps, TokenUsage usage) {
        return failed(error, "", steps, usage);
    }

    /**
     * An error result that keeps what the model had already said (#241).
     *
     * <p>{@link StopReason#ERROR} carried an empty {@code output} everywhere, because the
     * only factory for one hardcoded it. That is honest for the error exits that already
     * existed: {@code Agent} fails a run when a model call throws, when a context strategy
     * empties the transcript, and when a model turn reuses a tool-call id — the first two
     * before the turn produced anything, and the third on a turn whose whole output is the
     * malformed thing being refused. Every other caller in the repository builds one with
     * {@code steps = 0} for a run that never started.
     *
     * <p>It stopped being honest when a tool body that was entered and threw an
     * {@code Error} became a reported stop rather than an escaping throwable (#241). There
     * the run has a completed model turn behind it — usually the sentence saying what the
     * agent was about to do — and that sentence is the most useful thing on the result for
     * anybody working out what half-landed. The durable path keeps it for the same reason,
     * and {@code AgentRunResult} has always been able to.
     *
     * <p>An overload rather than a widening of the existing factories, and rather than a
     * widening of {@link #stopped}, which refuses {@code ERROR} on purpose: an error result
     * must name its error, and this still takes one. The two-argument and three-argument
     * forms are unchanged and their callers pass {@code ""} by meaning it rather than by
     * having no way to say otherwise.
     *
     * @param error  the error that ended the run; never {@code null}
     * @param output the model's last text, kept rather than discarded; never {@code null}
     *               and may be empty
     */
    public static AgentResult failed(Throwable error, String output, int steps,
                                     TokenUsage usage) {
        Objects.requireNonNull(error, "error");
        return new AgentResult(StopReason.ERROR, output, steps, usage, Optional.of(error));
    }

    /**
     * This result with different step and usage totals, and everything else unchanged.
     *
     * <p>For the agents that wrap a loop and run it more than once — {@code ReflectiveAgent}
     * across attempts, {@code PlanningAgent} across plan steps. Both had spelled this out
     * for themselves as {@code error().isPresent() ? failed(...) : stopped(...)}, which
     * enumerates the outcomes by exclusion: every stop reason that {@link #stopped} refuses
     * has to be remembered at both sites, and neither had a reason to know about a new one.
     *
     * <p>{@link StopReason#AWAITING_APPROVAL} was that new one, and it broke both.
     * Measured before this method existed: a real {@code Agent} with a parking gate, run
     * inside a {@code ReflectiveAgent}, threw {@code IllegalArgumentException: Use
     * awaitingApproval(...)} — a crash, in a composed runner, with no compile-time signal
     * and no test. Rebuilding by parts rather than by case means a fifth outcome lands
     * here silently and correctly.
     */
    public AgentResult withTotals(int steps, TokenUsage usage) {
        return new AgentResult(stopReason, output, steps, usage, error, awaiting);
    }

    /**
     * A run that ended because a gate stopped a call and this runner had nobody to ask.
     *
     * <p>{@code output} is the model's last text, kept for the same reason every other
     * non-error stop keeps it: the work done up to the question is still work, and a caller
     * resuming later wants to know what the run had established.
     *
     * @throws IllegalArgumentException if {@code awaiting} is empty — a run cannot stop on
     *     a question it cannot name
     */
    public static AgentResult awaitingApproval(String output, int steps, TokenUsage usage,
                                               List<PendingApproval> awaiting) {
        return new AgentResult(StopReason.AWAITING_APPROVAL, output, steps, usage,
                Optional.empty(), awaiting);
    }
}
