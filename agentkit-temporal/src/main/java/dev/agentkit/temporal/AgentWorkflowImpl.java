package dev.agentkit.temporal;

import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.context.ContextStrategies;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.message.UnusableToolUseBlock;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.reliability.TokenBudget;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.Quoted;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import dev.agentkit.core.reliability.PendingApproval;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Deterministic implementation of {@link AgentWorkflow}. It mirrors the
 * in-process {@code Agent} loop step-for-step, but delegates the two
 * non-deterministic / side-effecting operations — model inference and tool
 * execution — to activities. All control flow, the conversation history, and the
 * step/usage counters live in the workflow, so Temporal can replay them exactly.
 *
 * <p><strong>Parity and limits versus the in-process loop.</strong> Stop-reason
 * handling, step counting, tool extraction, and the token/cost budget stop match
 * {@code Agent} exactly, and an LLM activity that exhausts its retries yields an
 * {@link StopReason#ERROR} result (as in-process), not a failed workflow. These
 * intentionally differ:
 * <ul>
 *   <li><em>Fixed tool set.</em> The advertised tools are taken once from
 *       {@link DurableAgentRun#tools()}; progressive disclosure (a registry that
 *       reveals tools mid-run) is not applied, because the revealed set is
 *       mutable state that would have to be tracked durably in the workflow.</li>
 *   <li><em>No <b>configurable</b> context strategy, and no verification, inside the
 *       loop.</em> Since #243 the loop applies {@link ContextStrategies#DEFAULT} — see
 *       {@link #TRANSCRIPT_BOUNDED} for the measurement it closes and for the two ways
 *       this differs from the in-process application. A caller cannot supply a different
 *       one: a {@code ContextStrategy} is a lambda and cannot cross Temporal history, so a
 *       deployment that wants another bound has to register it worker-side the way a
 *       {@code ToolGate} is registered, which is a separate change with its own
 *       determinism argument (#243 says so in as many words). Compaction is out for a
 *       second reason that outlives that one: it issues its own model call, which from
 *       workflow code has to be an activity. Verification wraps the loop rather than
 *       living in it. Gating is <em>not</em> in this list any more: a {@code ToolGate}
 *       runs in the tool activity ({@code ToolActivitiesImpl}), which is deliberate —
 *       evaluated here it would be recomputed on every replay, so a policy edited between
 *       replays would rewrite what history says happened. In the activity the decision is
 *       memoized with the result.</li>
 *   <li><em>The budget is declared on the input, not the client.</em> In-process a
 *       {@code BudgetLlmClient} decorates the model client; here the same
 *       {@link dev.agentkit.core.reliability.TokenBudget} travels in
 *       {@link DurableAgentRun#budget()} and is enforced by this loop, because a
 *       decorator's tally would be shared across every run the worker serves.</li>
 *   <li><em>No streaming or context-awareness note.</em> Both are in-process-only
 *       concerns of {@code Agent}'s request building.</li>
 *   <li><em>No {@code AgentObserver}.</em> The durable loop emits only replay-aware
 *       logs, so anything built on observer callbacks (the eval harness's trajectory
 *       capture, live text deltas) sees nothing here.</li>
 * </ul>
 *
 * <p>A failing tool does not end a run, matching in-process: {@code ToolActivitiesImpl}
 * turns a thrown tool into an error result, and an activity-level failure
 * (start-to-close timeout, lost worker, exhausted attempts) is caught here and becomes
 * one too, per invocation. Three exceptions, all deliberate: a run whose every tool call
 * fails at the activity level for {@value #MAX_CONSECUTIVE_DEAD_TOOL_TURNS} consecutive
 * turns stops with {@link StopReason#ERROR}, so a dead or misconfigured tool worker
 * cannot masquerade as a successful run; a tool body that throws outside
 * {@code RuntimeException} stops the run for the reason {@link #TOOL_ERROR_ENDS_A_RUN}
 * gives (#129); and cancellation is rethrown rather than
 * absorbed, so the loop stops promptly instead of issuing further calls into a
 * cancelled scope. (A workflow-level fault — a payload the worker cannot deserialize,
 * say — is a separate concern that fails the workflow task; see {@link DurableJson}.)
 */
public final class AgentWorkflowImpl implements AgentWorkflow {

    private static final Logger log = Workflow.getLogger(AgentWorkflowImpl.class);

    /**
     * Consecutive turns in which every tool call failed at the activity level before
     * the run gives up. One such turn can be a transient blip and leaves the model a
     * chance to adapt; two in a row means the tool tier itself is down.
     */
    private static final int MAX_CONSECUTIVE_DEAD_TOOL_TURNS = 2;

    /**
     * The change id for "an {@code Error} from a tool ends a durable run" (#129).
     *
     * <p><strong>What was measured.</strong> One tool that records a side effect and then
     * throws an {@code AssertionError}, run on each of the three runners in this
     * repository:
     *
     * <pre>
     * in-process Agent : ran=1  the AssertionError escapes Agent.run — no AgentResult, no onFinish
     * in-process Goap  : ran=1  run ends, GoapStop.ACTION_THREW, trace kept, onFinish called
     * durable          : ran=1  run continues, stopReason=COMPLETED, output="done", errorMessage=""
     * </pre>
     *
     * <p>#103 made the three agree on the number, which was its stated requirement, and left
     * the outcome. The durable row is the one worth fixing: a run reporting
     * {@code COMPLETED} for a turn in which the worker's own invariant broke is the model
     * answering around a defect nobody was told about, and this repository has repeatedly
     * called "the run reports success while nothing worked" the worse failure.
     *
     * <p><strong>Where the line is drawn, and why it is not a new line.</strong>
     * {@code Agent.runTool} catches {@code RuntimeException} and nothing else, so in process
     * a {@code RuntimeException} becomes an error result and continues while everything
     * outside it ends the run. {@code ToolActivitiesImpl} already has those same two catch
     * blocks; only the second one behaved differently. So this is not a new predicate — no
     * {@code instanceof Error} anywhere — it is the durable half of an existing line being
     * moved onto it.
     *
     * <p><strong>What deliberately still differs.</strong> A <em>gate</em> that throws an
     * {@code Error} does not end a durable run and does end an in-process one. That is
     * #103's decision, argued in {@code ToolActivitiesImpl} and pinned by
     * {@code DurableToolErrorTest.aGateThatThrowsAnErrorDoesNotEndTheRunEither}: a broken
     * policy must not become a total outage, and the failure it produces is fail-closed —
     * nothing ran. #129 says a decision of this size deserves its own issue rather than
     * riding along with another; reversing #103's under #129's number would be exactly that
     * mistake in the other direction. {@code brokeAnInvariant} is therefore restricted to a
     * tool body that was entered, which is a distinction #181's {@code Disposition} already
     * pays for.
     *
     * <p><strong>What versioning buys, said honestly.</strong> {@code brokeAnInvariant} is a
     * new component on {@code ToolOutcome}, so a payload recorded by any worker before this
     * change comes back false, and a run in flight replays down the old branch because the
     * fact it would branch on is not in its history. The additive component is what makes
     * the branch replay-safe; {@code Workflow.getVersion} is not load-bearing for that. It
     * is here for what it is load-bearing for: the branch is recorded in history as a named
     * change, so an operator can see which runs took it, and a revert has a version to pin
     * rather than a behaviour to guess at. #129 asks for it by name, and the cost is one
     * marker on the runs that reach the branch — it is called inside the condition, not
     * before it, so a run that never sees a broken invariant records nothing. Calling it
     * there is deterministic because the condition is read off an activity result that is
     * already in history.
     */
    static final String TOOL_ERROR_ENDS_A_RUN = "tool-error-ends-a-run";

    /** The version of {@link #TOOL_ERROR_ENDS_A_RUN} in which the run stops. */
    static final int TOOL_ERROR_ENDS_A_RUN_V1 = 1;

    /**
     * The change id for "a durable run's transcript is bounded by the default strategy"
     * (#243).
     *
     * <p><strong>What was measured.</strong> #151 gave every in-process {@code Agent} a
     * transcript bound and #242 defaulted it on; nothing applied a {@code ContextStrategy}
     * here at all, so the runner that lives long enough to accumulate was the one with no
     * ceiling. Measured on the branch before this change, through this workflow in
     * Temporal's test environment, with one tool that throws on every call — counting
     * characters a third party wrote in the transcript the model was handed, and summing
     * over every request the run made, because the transcript is re-sent every turn:
     *
     * <pre>
     * calls   in the last transcript   cumulative over the run
     *     1                   4,136                     4,136
     *     8                  33,088                   148,896
     *    32                 132,352                 2,183,808
     *   256               1,058,816               136,057,856
     * </pre>
     *
     * <p>Exactly 4,136 characters a call and uncapped, which is character for character the
     * IDENTITY column {@code DefaultContextStrategyTest} pins for the in-process loop — the
     * two runners agreed about the defect and then only one of them was repaired. The same
     * four runs after this change:
     *
     * <pre>
     * calls   in the last transcript   cumulative over the run   framework text left behind
     *     1                   4,136                     4,136                            0
     *     8                   4,136                    45,496                          553
     *    32                   4,136                   144,760                        2,449
     *   256                   4,136                 1,071,224                       20,145
     * </pre>
     *
     * <p>Flat rather than linear in the last transcript, and linear rather than quadratic
     * cumulatively. The third column is what {@code BoundedFailureTextEditor} leaves in
     * place of each dropped call — fixed sentences it wrote itself, bounded by the caller's
     * own {@code maxSteps} rather than by anybody else's choice — and 20,145 at 256 calls is
     * the same figure that class measured for the in-process loop.
     *
     * <p><strong>Why a version marker, when the edit is already replay-safe.</strong>
     * {@link ContextStrategies#DEFAULT} is a pure, idempotent function of the message list —
     * it holds one {@code int}, allocates no state, and reaches the same answer from the
     * same history — so a replay through the new code recomputes exactly what the new code
     * computed, marker or no marker. The marker is not what makes it safe and this javadoc
     * says so rather than implying otherwise. What it buys is the other thing: a run already
     * in flight when the worker is upgraded keeps the transcript shape its history was
     * written against, instead of the model being handed a differently-edited conversation
     * halfway through a run it is reasoning about. And, as with
     * {@link #TOOL_ERROR_ENDS_A_RUN}, the change is recorded in history as a named one, so
     * an operator can see which runs took it and a revert has a version to pin.
     *
     * <p><strong>Applied to the activity's input, not persisted into the loop's own
     * conversation.</strong> The in-process {@code Agent} writes the strategy's output back
     * through {@code Conversation.replaceAll}; this workflow keeps {@code conversation} as
     * the unedited record and edits only what the turn sends. Two reasons, and the second is
     * the one that decides it. A workflow's fields are re-materialised by replaying its own
     * history, so a persisted edit would make each turn's bound depend on the accumulated
     * result of every earlier turn's bound — a strictly longer determinism argument for no
     * gain. And the loop keeps holding what the tools actually returned, so nothing later in
     * the run is reasoning about a rewritten copy. What history records as the activity's
     * input is, correctly, the edited list — that is what was sent — and the unedited text is
     * still in history one event over, on the {@code ToolOutcome} it came back on.
     *
     * <p>{@link ContextStrategies#DEFAULT} cannot empty the list, which is why there is no
     * counterpart here to the in-process loop's {@code prepared.isEmpty()} guard:
     * {@code BoundedFailureTextEditor.edit} either returns its argument or a list with one
     * entry per message, and {@code Compactor.NONE} is identity. A guard for a state this
     * fixed strategy cannot reach is a line no test can kill, and the day a caller can
     * supply their own strategy is the day it is worth writing.
     *
     * <p>It costs one walk of the transcript per turn that the in-process loop does not pay,
     * and buys a tighter bound than persisting gives: recomputed from the raw list, an
     * over-budget run's transcript settles at 4,136 characters of third-party text at every
     * size above, where the in-process loop's persisted form oscillates up to 8,272 because
     * a turn under budget accumulates on top of the placeholders an earlier turn wrote (both
     * far inside {@code BoundedFailureTextEditor.DEFAULT_MAX_TOTAL_FAILURE_CHARS}, which is
     * what makes this a difference in the two runners' numbers rather than in their rule).
     * Cumulatively at 256 calls it is 1,071,224 here against the in-process 2,113,496. Named
     * so nobody reads the in-process table onto this path.
     *
     * <p><strong>The one cost that is not free, stated plainly.</strong>
     * {@code BoundedFailureTextEditor} logs through an ordinary {@code slf4j} logger, not
     * through {@link Workflow#getLogger}, so its lines are emitted again on every replay.
     * Measured on the 8-call run above: the execution itself emitted 5 lines, one per turn
     * that was over budget, and replaying that same history through
     * {@code WorkflowReplayer} emitted all 5 again — against 0 from this class's own
     * replay-aware logger, which is the whole difference between the two. Giving
     * {@code agentkit-core} a replay-aware logger was considered and rejected: it would put
     * a Temporal concept into the module that must not know about one, in order to quiet a
     * line an operator sees only once a run has already failed four tool calls in a row, and
     * only on the replays of those turns.
     */
    static final String TRANSCRIPT_BOUNDED = "transcript-bounded-by-default";

    /** The version of {@link #TRANSCRIPT_BOUNDED} in which the strategy is applied. */
    static final int TRANSCRIPT_BOUNDED_V1 = 1;

    /**
     * Verdicts signalled in for a park that is <em>currently outstanding</em>, by ticket.
     *
     * <p>Workflow state, so it is rebuilt by replaying the same signals in the same order
     * and a run that moves worker mid-wait carries on where it was. Only {@code get},
     * {@code containsKey} and {@code clear} are used, never iteration, so the map's order
     * is not a determinism hazard.
     *
     * <p>Emptied alongside {@link #pending} once a park is settled, which is what keeps a
     * decision from being spent twice — and keeps this from growing for the life of a run.
     */
    private final Map<String, ApprovalVerdict> verdicts = new HashMap<>();

    /** What the run is waiting on right now, for {@link #pendingApprovals()}. */
    private final List<PendingApproval> pending = new ArrayList<>();

    /**
     * How many calls this run has parked, which is where a ticket's number comes from.
     *
     * <p>Deterministic: the loop parks in a fixed order, so a replay mints the same tickets
     * in the same sequence. And it is the runner's own counter, so nothing the model writes
     * reaches it — which is the whole point (see {@link PendingApproval}).
     */
    private int parks;

    /**
     * Whether this run has read somebody else's words yet (#122).
     *
     * <p>Workflow state, which is what makes it replay-safe: it is set in tool order, and
     * tool order is fixed, so a replayed run and a fresh one agree about which policy is in
     * force. Deriving it by scanning the conversation would have had to agree with history
     * rather than with in-memory state — a determinism bug as well as a security one, and
     * the reason {@code TrustFloor} is monotonic rather than recomputed.
     *
     * <p>It cannot live in the activity. One {@code ToolActivitiesImpl} serves every run on
     * the worker, so a field there would leak between unrelated runs and tenants — the
     * hazard that class documents for a stateful gate. So the state is here and the gates
     * are there, and the workflow says which one applies by which method it calls.
     */
    private boolean trustLowered;

    @Override
    public void decide(ApprovalVerdict verdict) {
        // No throwing. A signal handler that throws fails the workflow task, and Temporal
        // retries a failed workflow task forever — so a malformed verdict would stall the
        // run rather than be rejected. ApprovalVerdict's constructor coerces for the same
        // reason.
        if (verdict == null) {
            return;
        }
        // Accepted only for a park this run is waiting on right now. An earlier version
        // kept every verdict for the life of the run, keyed on the model's own tool-call
        // id, and that was a laundering primitive twice over: a decision outlived the call
        // it answered and satisfied a later call that reused the id, and a decision
        // signalled before anything was parked ran the first park the moment it was raised
        // — in both cases without the call ever appearing in pendingApprovals(). The
        // javadoc then said an early verdict "cannot make anything happen that policy would
        // not"; it could.
        //
        // Dropping is right rather than harsh: a ticket is learned by querying, and the
        // query lists only what is outstanding, so a verdict for anything else is a mistake
        // or an attempt.
        if (pending.stream().noneMatch(parked -> parked.ticket().equals(verdict.ticket()))) {
            log.warn("Ignoring a decision for '{}', which this run is not waiting on",
                    Quoted.of(verdict.ticket()));
            return;
        }
        log.info("Approval signalled for {}: {} by {}", Quoted.of(verdict.ticket()),
                verdict.kind(), Quoted.of(verdict.decidedBy()));
        verdicts.put(verdict.ticket(), verdict);
    }

    @Override
    public List<PendingApproval> pendingApprovals() {
        return List.copyOf(pending);
    }

    @Override
    public AgentRunResult run(DurableAgentRun input) {
        AgentConfig config = input.config();
        // Same rule as the in-process Agent: a durable run gets fenced content too — a
        // knowledge passage arrives as a tool result, a skill catalog is already in the
        // prompt — and a fence the prompt never explains is decoration. Derived once here
        // rather than per turn: it is a pure function of the (frozen) input, so it replays
        // identically.
        String systemPrompt = config.explainsFencedContent()
                ? Spotlight.withInstruction(config.systemPrompt())
                : config.systemPrompt();
        LlmActivities llm = Workflow.newActivityStub(LlmActivities.class, llmOptions(input.options()));
        ToolActivities toolActivities =
                Workflow.newActivityStub(ToolActivities.class, toolOptions(input.options()));

        List<Message> conversation = new ArrayList<>();
        conversation.add(Message.user(input.goal().render()));

        // An absent budget means uncapped. TokenBudget is an immutable record carried
        // on the (frozen) input, so it needs no reconstruction and replays identically.
        TokenBudget budget = input.budget();

        TokenUsage totalUsage = TokenUsage.ZERO;
        int steps = 0;
        String lastText = "";
        int consecutiveDeadToolTurns = 0;

        while (steps < config.maxSteps()) {
            // Pre-flight budget guard, mirroring BudgetLlmClient: a turn's cost is
            // unknown until it returns, so the turn that first reaches the cap still
            // completes and the NEXT one is refused. Enforced here rather than in the
            // activity so the cap is per-run and replays from workflow state.
            if (budget != null && budget.isExhausted(totalUsage)) {
                log.info("Durable run stopping before step {}: budget exhausted (spent {} against {})",
                        steps + 1, totalUsage, budget);
                return AgentRunResult.of(StopReason.BUDGET_EXHAUSTED, lastText, steps, totalUsage);
            }

            // See TRANSCRIPT_BOUNDED for the measurement, for why the marker is not what
            // makes this replay-safe, and for why the result is not written back into
            // `conversation`.
            //
            // Called every turn rather than hoisted above the loop, and the two are
            // equivalent rather than one being right: getVersion resolves a change id once
            // per execution however often it is asked — measured, one Version marker across
            // a nine-turn run — and AgentConfig refuses maxSteps <= 0, so this loop always
            // runs at least once and the hoisted form could not record a marker this one
            // does not. The mutation pass raised the hoisted form and nothing killed it,
            // which is that proof rather than a gap. It is written here because the branch
            // reads better beside the thing it decides.
            List<Message> forTheModel =
                    Workflow.getVersion(TRANSCRIPT_BOUNDED, Workflow.DEFAULT_VERSION,
                            TRANSCRIPT_BOUNDED_V1) >= TRANSCRIPT_BOUNDED_V1
                            ? ContextStrategies.DEFAULT.prepare(conversation)
                            : conversation;
            LlmCallSpec spec = new LlmCallSpec(config.model(), systemPrompt,
                    forTheModel, input.tools(), config.maxTokens(), config.options());
            LlmTurn turn;
            try {
                turn = llm.generate(spec);
            } catch (ActivityFailure e) {
                // Cancellation unwinds rather than being absorbed. Not because the run
                // would otherwise report success — Temporal marks a cancel-requested
                // workflow CANCELLED whatever this method returns — but so the loop
                // stops now instead of issuing further calls into a cancelled scope,
                // each failing immediately, until maxSteps.
                if (isCanceled(e)) {
                    throw e;
                }
                // The LLM activity exhausted its retries. Mirror the in-process
                // Agent: return a populated ERROR result rather than failing the
                // whole workflow, so callers get the partial steps/usage and a
                // message they can act on.
                log.warn("LLM activity failed after retries on step {}", steps + 1, Quoted.failure(e));
                return new AgentRunResult(StopReason.ERROR, lastText, steps, totalUsage,
                        messageOf(e));
            }

            steps++;
            totalUsage = totalUsage.plus(turn.usage());
            conversation.add(turn.message());
            lastText = turn.message().text();
            log.debug("Durable step {} stopReason={}", steps, turn.stopReason());

            switch (turn.stopReason()) {
                case REFUSAL -> {
                    return AgentRunResult.of(StopReason.REFUSED, lastText, steps, totalUsage);
                }
                case PAUSE -> {
                    // A resumable pause; this loop stops rather than blocking a worker.
                    return AgentRunResult.of(StopReason.PAUSED, lastText, steps, totalUsage);
                }
                case MAX_TOKENS -> {
                    // This turn's output was truncated at the per-call maxTokens limit; a
                    // tool call this turn may be incomplete, so stop. Distinct from a
                    // run-wide budget stop.
                    return AgentRunResult.of(StopReason.OUTPUT_TRUNCATED, lastText, steps, totalUsage);
                }
                default -> {
                    // END_TURN / OTHER: finish if no tools, else run them and continue.
                }
            }

            List<ProposedCall> toolUses = toolUses(turn.message());
            if (toolUses.isEmpty()) {
                return AgentRunResult.of(StopReason.COMPLETED, lastText, steps, totalUsage);
            }

            List<ContentBlock> results = new ArrayList<>(toolUses.size());
            java.util.Optional<String> malformed =
                    ToolUseBlock.refusalForRepeatedIds(toolUses);
            if (malformed.isPresent()) {
                // The same answer the in-process loop gives, asked of the same method, and
                // the same stop: a turn reusing an id has destroyed the correlation a
                // result is matched by, and the assistant turn carrying those ids is echoed
                // back verbatim, so there is no next request to make (#119).
                //
                // Deterministic given this code — it reads only the turn — but not memoized
                // the way a gate decision is, so a rolling deploy that changes the rule mid
                // run makes a replay decide differently from history. That is the hazard
                // this class's own gating note names, and it is why the rule is a fixed
                // property of the turn rather than anything configurable.
                log.warn("Model turn on step {} reused a tool-call id; no tool was run", steps);
                return new AgentRunResult(StopReason.ERROR, lastText, steps, totalUsage,
                        malformed.get());
            }
            int activityFailures = 0;
            for (ProposedCall proposed : toolUses) {
                if (proposed instanceof UnusableToolUseBlock unusable) {
                    // Refused here, before the activity, and not by the activity (#246).
                    // ToolActivitiesImpl is handed a ToolInvocation, and a ToolInvocation
                    // cannot exist holding arguments this framework refused — its own
                    // constructor freezes them. So the durable path's counterpart of
                    // Agent.runTool's decision is this line: the workflow is where a block
                    // becomes an invocation, and a call that cannot become one never
                    // reaches the worker. Refusing later would mean putting the tree on the
                    // activity wire, where a deserialization throw lands on the WORKFLOW
                    // thread and stalls the run rather than failing it — the outcome
                    // ToolInvocation's javadoc calls the worst failure mode in this
                    // codebase.
                    //
                    // No activity is started, so nothing is written to history for this
                    // call beyond the turn that proposed it and the result block below.
                    // Deterministic given the turn, like the repeated-id check above: it
                    // reads a property of a block already in history and calls nothing.
                    log.warn("Model turn on step {} proposed call '{}' on tool '{}' with"
                                    + " arguments this framework will not carry; no gate was"
                                    + " asked and nothing ran", steps,
                            Quoted.of(unusable.id()), Quoted.of(unusable.name()));
                    // Through ToolResult.refused, like every other runner's refusal, so
                    // the FIRST_PARTY on this block is the one factory's answer rather than
                    // this file's own (#272).
                    ToolResult refusal = ToolResult.refused(unusable.refusal());
                    results.add(new ToolResultBlock(unusable.id(), refusal.content(),
                            refusal.isError(), refusal.provenance()));
                    continue;
                }
                ToolUseBlock use = (ToolUseBlock) proposed;
                ToolInvocation invocation = new ToolInvocation(use.id(), use.name(), use.input());
                ToolResult result;
                // The activity's judgement, not this workflow's. The TrustFloor that says
                // which provenances count lives on the worker beside the gates it chooses
                // between; a second copy here would be one policy spelled twice.
                boolean loweredHere = false;
                // Read out of the outcome in both branches for the same reason loweredHere
                // is: a resumed call runs a tool too, and a person having approved it makes
                // a broken invariant more serious rather than less. Left false on the
                // ActivityFailure path, where no tool body reported anything at all.
                boolean brokeAnInvariant = false;
                try {
                    ToolOutcome outcome = trustLowered
                            ? toolActivities.executeToolUnderLoweredTrust(invocation)
                            : toolActivities.executeTool(invocation);
                    if (outcome.parked().isPresent()) {
                        // The ticket is minted here, not by the activity: it identifies a
                        // wait this workflow is about to enter, and only this workflow
                        // knows how many it has entered.
                        PendingApproval parked = new PendingApproval(
                                outcome.parked().get().invocation(),
                                outcome.parked().get().why(), "park-" + (++parks));
                        Decided decided = waitForAPerson(parked, invocation, input.options());
                        if (decided.timedOut()) {
                            // The run ends rather than continuing without the call. Not an
                            // error result the model reacts to, which is what a denial is:
                            // nobody denied anything, so telling the model it was refused
                            // would be a confident falsehood about a question still open.
                            // And continuing would have the model propose the same action
                            // again, park again, and wait again — a loop with a person in
                            // it, paging somebody who has already not answered once.
                            log.warn("Durable run stopping on step {}: nobody decided '{}' within"
                                    + " {}s", steps, Quoted.of(use.name()),
                                    input.options().approvalTimeoutSeconds());
                            return AgentRunResult.awaitingApproval(lastText, steps, totalUsage,
                                    List.of(parked));
                        }
                        result = decided.outcome().result();
                        // An approved read is still a read. The first version left this
                        // false, so a floor whose *ordinary* policy parks — put an approval
                        // on your reads, which is prudent — never lowered at all: approve
                        // the fetch, and the write after it ran unguarded.
                        loweredHere = decided.outcome().lowered();
                        brokeAnInvariant = decided.outcome().brokeAnInvariant();
                    } else {
                        result = outcome.result();
                        loweredHere = outcome.lowered();
                        brokeAnInvariant = outcome.brokeAnInvariant();
                    }
                } catch (ActivityFailure e) {
                    if (isCanceled(e)) {
                        throw e; // as above: a cancelled run must unwind, not continue
                    }
                    // ToolActivitiesImpl already converts a thrown tool into an error
                    // result, so reaching here means the activity itself failed — a
                    // start-to-close timeout, a lost worker, or exhausted attempts.
                    // Mirror the in-process loop, where a tool failure is always an
                    // error result the model can react to and never ends the run;
                    // failing the workflow here would discard every completed step.
                    // Caught per invocation, so one bad tool does not sink the others
                    // in a multi-tool turn.
                    log.warn("Tool activity '{}' failed on step {} (attempts exhausted, activityId={})",
                            Quoted.of(use.name()), steps, Quoted.of(e.getActivityId()),
                            Quoted.failure(e));
                    activityFailures++;
                    // error() and not refused(), which is the distinction #272 drew:
                    // toolFailureMessage carries messageOf(e), which is the far side's own
                    // text — a tool's exception message travelling out through the
                    // activity. UNKNOWN is the conservative answer for it, and this
                    // workflow never sees a Tool, so attributedTo is not available to give
                    // a better one.
                    result = ToolResult.error(toolFailureMessage(use.name(), e));
                } finally {
                    // Whatever happened, this call is no longer something a person is being
                    // asked about — including when the await unwound on cancellation. A
                    // query that still listed it would have a console offering a decision
                    // that can no longer be applied.
                    //
                    // Including on the timeout path, where the run is ending: a completed
                    // workflow answers no queries anyway, so the outstanding call travels
                    // on the result instead.
                    pending.clear();
                    // With it, so a decision cannot be spent on a later call and the map
                    // does not grow for the life of the run.
                    verdicts.clear();
                }
                // Before the trust floor is consulted and before the result is added to
                // the turn, because neither matters to a run that is ending: nothing later
                // in this turn will be sent to the model, and no further tool in it is
                // started — which is also what the in-process loop does, where the
                // throwable simply leaves runTool and the remaining calls never happen.
                //
                // getVersion is called here rather than above the loop so that a run which
                // never meets a broken invariant records no marker. The condition is read
                // off an activity result already in history, so this is replay-deterministic
                // — see TOOL_ERROR_ENDS_A_RUN for why the marker is not what makes it safe.
                if (brokeAnInvariant && Workflow.getVersion(TOOL_ERROR_ENDS_A_RUN,
                        Workflow.DEFAULT_VERSION, TOOL_ERROR_ENDS_A_RUN_V1)
                        >= TOOL_ERROR_ENDS_A_RUN_V1) {
                    log.warn("Durable run stopping on step {}: tool '{}' threw outside"
                            + " RuntimeException, so the worker's own invariant broke rather"
                            + " than the action failing", steps, Quoted.of(use.name()));
                    // The activity's own sentence, which already names the tool and the
                    // throwable's type and says that whatever the body had done before it
                    // threw has been done. Composing a second one here would be a second
                    // spelling of the same fact, and this one is already bounded by
                    // ToolActivitiesImpl.MAX_CONTENT_CHARS.
                    return new AgentRunResult(StopReason.ERROR, lastText, steps, totalUsage,
                            result.content());
                }
                // Within the turn, not after it, matching the in-process loop: a turn that
                // reads a page and then writes has the page in hand before the write is
                // gated. Waiting until the turn ended would make every multi-tool turn's
                // first read a free pass.
                if (!trustLowered && loweredHere) {
                    log.info("Trust floor lowered on step {} by '{}' ({}): the rest of this"
                            + " run is gated by the tightened policy",
                            steps, Quoted.of(use.name()), result.provenance());
                    trustLowered = true;
                }
                // Carried across the activity boundary, so a durable transcript is
                // labelled the same as an in-process one. The activity stamped it
                // from the tool's own declaration; this workflow never sees a Tool.
                results.add(new ToolResultBlock(use.id(), result.content(), result.isError(),
                        result.provenance()));
            }

            // Tolerating tool failures must not let a dead or misconfigured tool tier
            // masquerade as a successful run: with every call failing, the model would
            // answer around the outage and the run would report COMPLETED. A turn in
            // which *nothing* reached a tool is that signal; one such turn may be a
            // blip and leaves the model room to adapt, so stop only when the next turn
            // fails the same way.
            if (activityFailures == toolUses.size()) {
                if (++consecutiveDeadToolTurns >= MAX_CONSECUTIVE_DEAD_TOOL_TURNS) {
                    log.warn("Durable run stopping on step {}: every tool call failed at the activity"
                            + " level for {} consecutive turns", steps, consecutiveDeadToolTurns);
                    return new AgentRunResult(StopReason.ERROR, lastText, steps, totalUsage,
                            "Every tool call failed at the activity level for "
                                    + consecutiveDeadToolTurns + " consecutive turns; the tool worker is"
                                    + " likely unavailable or misconfigured.");
                }
            } else {
                consecutiveDeadToolTurns = 0;
            }

            conversation.add(Message.of(Role.USER, results));
        }

        return AgentRunResult.of(StopReason.MAX_STEPS, lastText, steps, totalUsage);
    }

    /**
     * What came back from waiting on a person: a result, or the fact that nobody answered.
     *
     * <p>A {@code null} result carries the timeout rather than a boolean flag being
     * checked separately, so a caller cannot read one without the other.
     */
    private record Decided(ToolOutcome outcome) {

        static final Decided TIMED_OUT = new Decided(null);

        boolean timedOut() {
            return outcome == null;
        }
    }

    /**
     * Blocks the run until somebody decides this call, or the deadline passes.
     *
     * <p>This is the half of #101 that could not live in a gate. In an activity, waiting
     * burns a start-to-close timeout, fails, and is retried — paging the person again per
     * attempt and failing the call anyway, which is why {@code ToolActivitiesImpl} refuses
     * a gate that declares {@code waitsForAHuman()}. Here it is a timer and a signal, both
     * recorded in history: the worker is free, the run survives it dying, and a replay
     * re-reads the decision rather than re-asking for it.
     *
     * <p>Deterministic. {@code Workflow.await} with a duration is a workflow timer, and the
     * signal that satisfies it is a history event, so a replay reaches the same answer at
     * the same point. Nothing here reads a clock or a map's iteration order.
     *
     * <p>The verdict goes back to the activity rather than being applied here, because the
     * gate has to run again and the workflow has no tool to run it against — the same reason
     * {@code ToolActivitiesImpl} hosts the gate at all. So an approval cannot widen what
     * policy allows: it answers the question the gate asked, and only where the gate is
     * still asking it.
     */
    private Decided waitForAPerson(PendingApproval parked, ToolInvocation invocation,
                                   DurableAgentOptions options) {
        pending.add(parked);
        log.info("Durable run waiting for a person to decide '{}' as {}: {}",
                Quoted.of(invocation.name()), Quoted.of(parked.ticket()),
                Quoted.of(parked.why().reason()));
        boolean decided = Workflow.await(
                Duration.ofSeconds(options.approvalTimeoutSeconds()),
                () -> verdicts.containsKey(parked.ticket()));
        if (!decided) {
            return Decided.TIMED_OUT;
        }
        ToolActivities activities = Workflow.newActivityStub(ToolActivities.class, toolOptions(options));
        // The parked call, not the proposed one: a composite may narrow the arguments and
        // then park, and what runs has to be what the reviewer was shown.
        //
        // Resumed under the policy that parked it, which is why the floor state travels
        // here at all. Resuming under the ordinary policy skipped the tightened gate
        // entirely, so a reviewer's edited arguments were dropped and the model's original
        // invocation ran.
        ApprovalVerdict verdict = verdicts.get(parked.ticket());
        // Not currently load-bearing, and worth saying so rather than leaving a reader to
        // assume otherwise. Measured: replacing this with an unconditional resumeTool
        // changes nothing observable, because the floor cannot move between parking and
        // resuming — the loop parks and awaits with no tool call in between — and because
        // the invocation handed over is already the effective one. Where the two do differ
        // (the ordinary policy denies what the tightened one parked) the unconditional form
        // is *stricter*, not looser: it denied where this runs.
        //
        // Kept because it says what is meant. A resumed call is re-gated by the policy that
        // parked it, and spelling that out is what stopped the original hole — where
        // resuming under the ordinary policy skipped the verdict branch entirely and ran
        // the model's proposal.
        return new Decided(trustLowered
                ? activities.resumeToolUnderLoweredTrust(parked.invocation(), verdict)
                : activities.resumeTool(parked.invocation(), verdict));
    }

    private static ActivityOptions llmOptions(DurableAgentOptions options) {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(options.llmStartToCloseSeconds()))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(options.llmMaxAttempts())
                        .build())
                .build();
    }

    private static ActivityOptions toolOptions(DurableAgentOptions options) {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(options.toolStartToCloseSeconds()))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(options.toolMaxAttempts())
                        .build())
                .build();
    }

    /**
     * Every tool call the turn proposed, in order, including the ones whose arguments this
     * framework refused (#246) — the same list {@code Agent.proposedCalls} builds, for the
     * same reason: both shapes need exactly one result block carrying their id.
     */
    private static List<ProposedCall> toolUses(Message message) {
        List<ProposedCall> uses = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (block instanceof ProposedCall use) {
                uses.add(use);
            }
        }
        return uses;
    }

    /**
     * Whether this run is actually being cancelled, rather than merely holding a
     * failure that mentions cancellation. Temporal reports the cancellation of an
     * in-flight activity as an {@link ActivityFailure} caused by a
     * {@link CanceledFailure}, which a blanket {@code catch (ActivityFailure)} would
     * otherwise absorb — leaving the loop to issue further model and tool calls into
     * an already-cancelled scope (each failing at once) until {@code maxSteps}.
     *
     * <p>The shape alone is not enough: a tool may itself throw or wrap a
     * {@code CanceledFailure} without this run being cancelled, and treating that as
     * cancellation would abort the whole workflow — the very outcome the surrounding
     * handler exists to prevent. So the scope's cancel-requested flag is the deciding
     * check, matching the SDK's own predicate. Both inputs are workflow state
     * reconstructed from history, so this is replay-deterministic.
     */
    private static boolean isCanceled(Throwable failure) {
        return CancellationScope.current().isCancelRequested() && mentionsCancellation(failure);
    }

    /** Whether {@code failure} is, or wraps, a {@link CanceledFailure}. */
    static boolean mentionsCancellation(Throwable failure) {
        // Bounded rather than trusting getCause() to terminate: the JDK collapses a
        // self-referencing cause, but an overriding Throwable need not.
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
            if (cause instanceof CanceledFailure) {
                return true;
            }
        }
        return false;
    }

    /**
     * How an activity-level tool failure is described to the model.
     *
     * <p>A timeout is reported as an <em>unknown</em> outcome rather than a failure.
     * Activity delivery is at-least-once, so a tool that timed out may in fact have
     * completed on a worker that died before reporting — calling that "failed" would
     * invite the model to re-drive a side effect that already happened.
     */
    private static String toolFailureMessage(String toolName, ActivityFailure e) {
        if (e.getCause() instanceof TimeoutFailure) {
            return "Tool '" + toolName + "' did not return within its time limit. Whether it ran"
                    + " is unknown — it may have completed, so do not assume it did not.";
        }
        return "Tool '" + toolName + "' failed: " + messageOf(e);
    }

    /**
     * The underlying failure text. Prefers a {@link TemporalFailure}'s original
     * message: its {@code getMessage()} is a composed SDK diagnostic (type names,
     * retry bookkeeping, worker identity) that would otherwise be fed to the model.
     */
    private static String messageOf(ActivityFailure e) {
        Throwable cause = e.getCause();
        if (cause instanceof TemporalFailure temporal && !temporal.getOriginalMessage().isBlank()) {
            return temporal.getOriginalMessage();
        }
        String message = cause != null ? cause.getMessage() : e.getMessage();
        return message != null ? message : "activity failed";
    }
}
