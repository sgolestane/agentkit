package dev.agentkit.itops.runtime;

import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.domain.ToolInvocationRecord;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.tools.ToolCatalog;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns the agent loop's callbacks into the platform's audit trail.
 *
 * <p>Everything here is derived from what the runtime observed, never from what the model
 * said about itself. That is the whole reason the audit trail is not simply the transcript:
 * a transcript records an account of events written by the party under review, and the
 * question an auditor actually asks — "what did it call, with what arguments, and what came
 * back" — is answerable only from the outside.
 *
 * <p>Note the ordering AgentKit gives us: {@code onToolProposed} fires before the gate
 * runs, so a proposal appears here even when the supervisor goes on to refuse it. That is
 * deliberate on our side too — a refused action is exactly the kind of thing an incident
 * review wants to see.
 *
 * <p>Which is why the two callbacks write different keys. {@code TOOL_STARTED} carries
 * {@code proposedArguments} and the completion row carries {@code arguments}, in this
 * observer and in {@code WorkflowRunner} alike: one audit vocabulary across both runners,
 * and a spelling that says which of a call's two invocations a row is holding. Writing
 * {@code arguments} on both rows is what let a supervisor's narrowing disappear from the
 * trail without anything looking wrong (#131).
 *
 * <p><strong>Every row names the agent that produced it (#311).</strong> Today this platform
 * runs one agent per execution, so {@code agent} is the same value on every row of a trail
 * and looks like dead weight. It is not: the moment an execution delegates — and one
 * observer witnessing the supervisor and its subagents is the only way "what did this
 * execution touch" keeps one answer — {@code step} restarts at 1 in the child and a row saying
 * {@code shred, REFUSED, step 2} names no actor at all. The trail is read after the incident,
 * and a key added afterwards is a key missing from every row written before it.
 *
 * <p>{@code WorkflowRunner} writes no such key, and that is not the vocabulary drift the
 * paragraph above warns about: it is a fixed sequence of steps with one actor and no
 * delegation, so there is no second answer for the key to distinguish.
 */
public final class AuditObserver implements AgentObserver {

    private final OpsContext context;
    private final OpsStore store;
    private final Map<String, Instant> started = new ConcurrentHashMap<>();

    public AuditObserver(OpsContext context, OpsStore store) {
        this.context = context;
        this.store = store;
    }

    @Override
    public void onStart(AgentRun run, Goal goal) {
        context.event(Execution.Event.Type.EXECUTION_STARTED,
                Map.of("goal", goal.render(), "agent", run.name(), "run", run.id()));
    }

    @Override
    public void onToolProposed(AgentRun run, int step, ToolInvocation invocation) {
        started.put(invocation.id(), Instant.now());
        context.event(Execution.Event.Type.TOOL_STARTED,
                Map.of("agent", run.name(), "run", run.id(),
                        "step", step, "tool", invocation.name(),
                        "proposedArguments", invocation.arguments()));
    }

    /**
     * The audit row, built from the call the gate settled on rather than the proposal.
     *
     * <p>{@code ToolInvocationRecord.arguments} has always been documented as "the
     * arguments as sent, after any supervisor edit". It was the proposal, so the sentence
     * was false for exactly the runs the supervisor did something to — the ones a reviewer
     * opens the record for.
     *
     * <p>Keyed on the <em>proposed</em> invocation's id, because that is the id
     * {@code onToolProposed} filed the start time under and a replacement keeps it.
     *
     * <p><strong>The verdict is the disposition, not a repeat of {@code isError} (#181).</strong>
     * This wrote {@code result.isError() ? "ERROR" : "OK"}, which is one bit derived from
     * one bit, and the seven ways a call can end all set it. A call the supervisor parked
     * and a tool that blew up half way through a group change were the same row — so the
     * one question these rows exist to answer, "did this action happen", was not answerable
     * from them. The framework now says which of the seven it was and this writes it down.
     *
     * <p>{@code TOOL_FAILED} against {@code TOOL_COMPLETED} still turns on {@code isError},
     * deliberately. That pair answers "what did the model get back", which is what a
     * timeline is read for; whether the action happened is a different question and now has
     * its own key rather than being crammed into an event type that cannot hold it.
     *
     * <p><strong>The row is handed {@code effective} whole (#170).</strong> It used to be
     * handed {@code effective.name()} and {@code effective.arguments()}, which the record
     * then deep-copied again over a map {@code ToolInvocation} had already frozen. Taking
     * the invocation itself deletes that copy and makes it impossible for this call site to
     * pair one call's name with another call's arguments. It is still {@code effective} and
     * not {@code proposed}: which of the two a row reports is #131's question, settled
     * there, and this change deliberately does not reopen it.
     */
    @Override
    public void onToolResult(AgentRun run, int step, ToolInvocation proposed,
                             ToolInvocation effective, ToolResult result,
                             Disposition disposition) {
        Instant begin = started.getOrDefault(proposed.id(), Instant.now());
        Risk baseline = ToolCatalog.policy(effective.name())
                .map(policy -> policy.baselineRisk()).orElse(Risk.HIGH);
        store.record(new ToolInvocationRecord(OpsStore.Ids.next("call"), context.executionId(),
                effective, baseline, baseline,
                disposition.name(), null, idempotencyKey(effective),
                begin, Instant.now(), result.isError(), result.content()));
        context.event(result.isError()
                        ? Execution.Event.Type.TOOL_FAILED
                        : Execution.Event.Type.TOOL_COMPLETED,
                Map.of("agent", run.name(), "run", run.id(),
                        "step", step, "tool", effective.name(),
                        "arguments", effective.arguments(),
                        // Written as well as stored on the row, because the timeline is what
                        // a reviewer reads first and "TOOL_FAILED" alone cannot say whether
                        // anything happened.
                        "disposition", disposition.name(),
                        "result", summarise(result.content())));
    }

    @Override
    public void onFinish(AgentRun run, AgentResult result) {
        context.event(Execution.Event.Type.VERIFICATION_COMPLETED,
                Map.of("agent", run.name(), "run", run.id(),
                        "stopReason", result.stopReason().name(), "steps", result.steps()));
    }

    /**
     * What makes a repeat of this call the same call.
     *
     * <p>Derived from the invocation rather than minted per attempt, so a retry after a
     * crash carries the key of the operation it is retrying. Nothing consumes these yet —
     * the connectors in this demo are idempotent on their own — but the key is what a real
     * executor would present to an external API to make a duplicate safe, and inventing it
     * later means inventing it without the arguments in hand.
     *
     * <p><strong>Deliberately not keyed on {@code invocation.id()} (#170).</strong> The
     * record now carries the whole invocation, so the model's call id is finally available
     * here, and #170 asked whether the key should use it. Measured against the sentence
     * above, it should not. Two retries reach this method:
     *
     * <ul>
     *   <li>A durable replay after a crash re-reads the same call out of workflow history,
     *       so the id is stable and an id-bearing key would still match.</li>
     *   <li>The model retrying after an error result — the common one, and the only one the
     *       in-process runner has — emits a <em>new</em> {@code tool_use} id for the same
     *       operation. An id-bearing key would differ, the external API would see two
     *       distinct requests, and the privileged action would happen twice. That is the
     *       exact failure this key exists to prevent.</li>
     * </ul>
     *
     * <p>What the current derivation costs instead: two genuinely distinct calls with the
     * same tool and the same arguments in one execution collide on one key, so an executor
     * honouring it would drop the second. Between "a retry runs twice" and "a deliberate
     * repeat runs once", the second is the failure to have, and it is the one the key's
     * stated purpose chooses. Recorded here rather than changed, because nothing consumes
     * these keys yet and an unconsumed contract is not the place to guess.
     */
    private String idempotencyKey(ToolInvocation invocation) {
        return context.executionId() + ':' + invocation.name() + ':'
                + Integer.toHexString(invocation.arguments().toString().hashCode());
    }

    private static String summarise(String content) {
        String oneLine = dev.agentkit.core.util.OneLine.of(content == null ? "" : content);
        return dev.agentkit.core.util.Cut.to(oneLine, 400);
    }
}
