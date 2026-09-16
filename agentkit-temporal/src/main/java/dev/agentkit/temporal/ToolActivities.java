package dev.agentkit.temporal;

import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The tool-execution boundary as a Temporal activity. Each tool call the model
 * requests runs as its own activity; once it completes, its result is memoized in
 * history and never recomputed on replay.
 *
 * <p><strong>Tools must be idempotent under retry.</strong> Temporal activities
 * are <em>at-least-once</em>: an infrastructure fault (a start-to-close timeout, or
 * a worker crash after the side effect but before the result is recorded) re-runs
 * the activity, so a non-idempotent side effect (charging a card, sending an
 * email) can execute more than once. The in-process loop never retries tools, so
 * the durable path introduces this risk. For a non-idempotent tool, set its
 * {@code toolMaxAttempts} to 1 (see {@link DurableAgentOptions}) or make the tool
 * idempotent (e.g. key the side effect by the invocation id).
 *
 * <p>That caveat is about <em>infrastructure</em> faults, and it used to be read as
 * covering more than it does. A fault the <em>tool body</em> produces is deterministic:
 * retrying it cannot succeed, and each attempt re-runs whatever the tool did before it
 * threw. {@code ToolActivitiesImpl} therefore fails such a call once rather than
 * {@code toolMaxAttempts} times: whatever the tool body throws becomes an error result
 * (#103). Only the at-least-once bargain above remains, and only for the faults it names.
 *
 *
 * <h2>Four methods, and why they are not one method over a facts record (#163)</h2>
 *
 * <p>The methods below are two entry points — a fresh call and a resumed one, which differ
 * by carrying an {@link ApprovalVerdict} — crossed with one <em>run-scoped</em> bit: has
 * this run's trust floor lowered yet (#122). Two times one bit is four, and the growth is
 * in the bit and not the entry point: a second run-scoped bit spelled this way makes eight,
 * a third sixteen. #163 proposed collapsing that into one {@code RunFacts} record parameter
 * and a {@code *InRun} method per entry point — "four methods now, four forever". The
 * enumeration was right and the conclusion was measured and rejected, on three counts.
 *
 * <p><strong>The record fails closed exactly once, and it exists to fail closed every
 * time.</strong> The whole argument for a method per run-scoped bit is what a worker on the
 * <em>previous</em> version does with a payload from the next one. A method it does not
 * have fails as unregistered — loud, and closed. A parameter it does not know is dropped in
 * silence: {@code DurableJson} disables {@code FAIL_ON_UNKNOWN_PROPERTIES}, and has to,
 * because failing there fails a workflow <em>task</em>, which Temporal retries forever and
 * so stalls a live run rather than failing it. {@code
 * DurableJsonTest.unknownFieldsAreIgnoredSoInputCanGrowAdditively} measures that drop from
 * the benign direction. #163 grants the point for the <em>first</em> migration — an old
 * worker lacks {@code executeToolInRun} — and that is true and also the only migration it
 * covers. The second run-scoped dimension is a new component on a {@code RunFacts} a
 * previous-version worker already knows how to deserialize, so that worker reads the fact
 * it understands, discards the one it does not, and runs the tool under the wrong policy
 * with nothing logged. That is the fail-open at an authorization boundary this shape was
 * chosen to prevent, arriving at the dimension the collapse exists to serve.
 *
 * <p><strong>It is not four; it is six plus the repository's first {@code
 * Workflow.getVersion}.</strong> An {@code @ActivityMethod} name is an activity type
 * recorded in history, so the four below cannot be deleted when {@code *InRun} arrives —
 * runs in flight schedule the type their own history names, and they replay for as long as
 * they run. So {@code AgentWorkflowImpl} would need a version branch to keep choosing the
 * recorded type, and this module has none today. #163's own strongest argument is that
 * these names are a one-way door; the migration is the door, and it buys a shape whose
 * next step is the fail-open above.
 *
 * <p><strong>The third bit has not landed.</strong> #179 added {@code effective} to
 * {@link ToolOutcome} and a {@code settled(proposed)} reader. That is a component on the
 * <em>return</em> value, strictly additive, invisible to a worker that has not heard of it,
 * and it multiplies nothing: this interface has the same four methods it had before it. The
 * trigger #163 names — a second run-scoped dimension — is #63's gate context, which is open
 * and waiting on #57 to settle {@code ToolGate}'s shape. #163 says to design {@code
 * RunFacts} <em>with</em> #63 rather than ahead of it, and #63 warns that a third parameter
 * shape landing before it means doing the migration twice. Doing it now is that second
 * migration.
 *
 * <p>What would change the answer: a facts record that a previous-version worker cannot
 * silently truncate — one that captures unknown properties and refuses the call rather than
 * running under a policy it could not fully read. That fails closed on every dimension
 * rather than the first, and it is the design worth having; it just has to be designed
 * alongside the dimension that needs it. Until then the eightfold growth is a cost paid in
 * two-line delegating methods, and {@code
 * DurableTypesTest.theActivityTypeNamesAreAWireFormatAndAreNotRenamedLightly} pins both the
 * names and the count, so a fifth arrives in a diff rather than unremarked.
 *
 * <p><strong>No longer a functional interface</strong> (#101). It was one, so
 * {@code ToolActivities x = invocation -> ...} compiled; adding {@link #resumeTool} breaks
 * that, and deliberately. A {@code default} implementation would compile every existing
 * caller and silently give them a resume path that ignores the verdict — which is the
 * {@code ToolGate.evaluate} trap #57 was about, where two callers took a default that
 * dropped what they were holding. An abstract method is a compile error; a default is a
 * security hole with a green build.
 */
@ActivityInterface
public interface ToolActivities {

    /**
     * Executes one tool invocation, unless the gate stops it.
     *
     * <p>Returns a {@link ToolOutcome} rather than a bare {@link ToolResult} so a gate that
     * says "a person must decide" can say so <em>to the workflow</em>, and — since #179 — so
     * that history says which call the gate settled on. Temporal records this return value,
     * and on a path with no {@code AgentObserver} that record is the audit trail; a
     * {@code ToolResult} could only say what came back, never what ran. See that type for
     * why its components are laid out to survive a rolling deploy in both directions; both
     * additions are invisible to a run that neither parks nor gets narrowed.
     *
     * <p>The alternative — throwing a non-retryable failure for a park — was rejected: this
     * class already converts everything a tool or a gate throws into an error result, on
     * purpose, and a park that had to be picked back out of two {@code catch} blocks by type
     * is one refactor away from being swallowed by the very code that exists to swallow
     * throws.
     */
    @ActivityMethod
    ToolOutcome executeTool(ToolInvocation invocation);

    /**
     * Executes a call that was parked, now that somebody has decided.
     *
     * <p>A separate method rather than a nullable second argument on {@link #executeTool},
     * so the resume path is legible in history, in a worker's logs and in this interface: a
     * verdict is consulted here and nowhere else.
     *
     * <p><strong>The gate runs again.</strong> A verdict does not bypass policy; it answers
     * the one question policy asked. A gate that denies outright still denies, and a gate
     * that has stopped asking simply allows. Only where the gate still says a person must
     * decide does the verdict decide it — which also means a policy edited while the call
     * sat on somebody's desk is the policy that applies, not the one that parked it.
     *
     * <p>Idempotency is the same bargain as {@link #executeTool}: this is an activity, so an
     * infrastructure fault re-runs it, and an approved non-idempotent action can land twice.
     * A person having said yes does not change that; {@code toolMaxAttempts} still governs.
     */
    @ActivityMethod
    ToolOutcome resumeTool(ToolInvocation invocation, ApprovalVerdict verdict);

    /**
     * As {@link #resumeTool}, for a run whose trust floor has already lowered (#122).
     *
     * <p>A resumed call is re-gated under the policy that <em>parked</em> it, not under the
     * ordinary one. The first version resumed under the ordinary policy and argued that a
     * person having read the arguments was supervision enough — which argues about the
     * resumed call and says nothing about the two things that actually broke.
     *
     * <p>Measured, with the composition the README recommends
     * ({@code ordinarily = ALLOW_ALL}, {@code onceLowered = parkForApproval}): the ordinary
     * gate does not park, so the branch that applies a verdict was skipped entirely and the
     * call fell through to run <strong>the model's original invocation</strong>. A reviewer
     * who approved {@code {"text": "just the summary"}} got {@code {"text": "EVERY SECRET"}}.
     * The tightened gate's other restrictions were not consulted either, so an approval
     * widened what policy allowed — the one thing this path is written to prevent.
     */
    @ActivityMethod
    ToolOutcome resumeToolUnderLoweredTrust(ToolInvocation invocation, ApprovalVerdict verdict);

    /**
     * Executes one tool invocation under the tightened policy of a lowered trust floor
     * (#122).
     *
     * <p>A third method rather than a flag on {@link #executeTool}, and the reason is a
     * rolling deploy. Both gates live here, because the workflow has no {@code Tool} to
     * gate; the one bit of state — has this run read somebody else's words yet — lives in
     * the workflow, because an activity may not hold run state. So the workflow has to say
     * which policy applies, and how it says it decides what happens when the two ends of a
     * deploy disagree.
     *
     * <p>An extra parameter would be <strong>ignored</strong> by a worker still on the
     * previous version, which would then apply the ordinary gate to a run whose floor had
     * lowered — failing open, silently, at an authorization boundary. A method that worker
     * does not have fails as unregistered, burns {@code toolMaxAttempts}, and the call
     * becomes an error result. Louder and closed, which is the trade this repository makes
     * everywhere else.
     *
     * <p>Once lowered a floor stays lowered, so there is no method for raising it again.
     *
     * <p><strong>And a method rather than a component on a shared facts record</strong>, for
     * the same reason one level up: a record component a previous-version worker has not
     * heard of is dropped in silence, which is this exact failure with the alarm removed.
     * The class javadoc has the measurement and the conditions under which the answer would
     * change (#163).
     */
    @ActivityMethod
    ToolOutcome executeToolUnderLoweredTrust(ToolInvocation invocation);
}
