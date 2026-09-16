package dev.agentkit.core.agent;

import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;

/**
 * A hook into the agent loop for observability. All methods have no-op defaults, so
 * implementations override only what they need.
 *
 * <p>Observability and not steering, which this said until #166 and which the guard below
 * made false: an observer's only lever over a run was throwing, and that is now absorbed.
 *
 * <p>Later phases layer verification, gating, and durable-execution concerns on
 * top of this seam.
 *
 * <h2>Every callback says whose run it is (#311)</h2>
 *
 * <p>The first parameter of all six is an {@link AgentRun}, minted per {@link Agent#run(Goal)}
 * and constant for that run's lifetime. It is there because one observer has to witness a
 * supervisor <em>and</em> the agents its subagents build — that is the only way "what did
 * this run touch" has one answer — and until #311 nothing separated their rows:
 * {@code step} restarts at 1 in every agent, delegation is synchronous so a child's calls
 * arrive before the {@code delegate} that caused them, and a call the policy stopped inside a
 * child was attributed to nobody. {@link AgentRun} carries the measurement and the shapes
 * that were rejected for being forgettable.
 *
 * <h2>What happens if you throw (#166)</h2>
 *
 * <p><strong>Nothing, to the run.</strong> Every callback below is dispatched through a
 * guard: a throw — including an {@code Error} — is absorbed, the loop carries on, and the
 * caller still gets an {@code AgentResult}. It is then handed to the deployment's
 * {@code Agent.Builder.onObservationFailure} handler with the callback's name, which
 * defaults to a log line.
 *
 * <p>It did not always. {@code Agent} called an observer in eight places and guarded none
 * of them, and {@code onToolResult} fires <em>after</em> the tool has run. Measured with an
 * observer that threw there: the tool ran, nothing recorded it, {@code Agent.run} threw
 * instead of returning, and {@code onFinish} never fired — so an observer keeping per-run
 * state was left holding a run it would never see the end of.
 *
 * <p>Two consequences worth knowing before writing one:
 *
 * <ul>
 *   <li><strong>This is not a policy seam.</strong> An observer cannot refuse anything, and
 *       one written as though it could now fails without stopping the call. A control that
 *       must be able to say no is a {@code ToolGate}, which is asked <em>before</em> the
 *       tool runs and whose refusal reaches the model as a result.</li>
 *   <li><strong>Your failures are yours to notice.</strong> If the record this observer
 *       keeps is a control rather than a convenience, set
 *       {@code onObservationFailure} — the framework cannot know whether a dropped
 *       {@code onTextDelta} matters and a dropped {@code onToolResult} does.</li>
 * </ul>
 *
 * <p>{@code GoapObserver} answers this question the same way, in the same words, since
 * #173. It used to answer the other way; if the two ever drift apart again, that is a
 * defect in whichever moved and not a distinction to preserve.
 */
public interface AgentObserver {

    /** A no-op observer. */
    AgentObserver NONE = new AgentObserver() {
    };

    /**
     * Called once when a run begins, with the identity every later callback repeats.
     *
     * <p>This and {@link #onFinish} are what bracket a run in a shared trace. A subagent's
     * rows arrive interleaved with its supervisor's — synchronously, so the child's calls
     * land before the {@code delegate} that caused them — and these two say where one run's
     * rows start and stop without the reader inferring it from anything.
     */
    default void onStart(AgentRun run, Goal goal) {
    }

    /**
     * Called with each incremental text fragment while a model turn streams in.
     * Only fires when the agent is built with streaming enabled; {@code delta} is a
     * fragment to concatenate, and {@link #onModelResponse} still delivers the
     * completed turn afterward.
     */
    default void onTextDelta(AgentRun run, int step, String delta) {
    }

    /** Called after each model turn completes. */
    default void onModelResponse(AgentRun run, int step, LlmResponse response) {
    }

    /**
     * Called before a tool call is gated, with the call the model proposed.
     *
     * <p>Before <em>gating</em>, so this fires for a call a gate goes on to deny, replace or
     * park. That is why it is the proposal and not the effective call: at this point there
     * is no effective call. A trajectory showing no tool calls for a turn that requested ten
     * is the "wiring looked right and did nothing" shape this framework has shipped before.
     *
     * <p>Named for which invocation it carries rather than for when it fires. It was
     * {@code onToolInvocation}, a name that said nothing about which of a call's two
     * invocations a reader is holding — and #131 was exactly a reader holding the wrong one.
     *
     * <p>What the gate settled on, and whether anything ran, are
     * {@link #onToolResult(AgentRun, int, ToolInvocation, ToolInvocation, ToolResult,
     * Disposition)}'s to report (#131, #181).
     */
    default void onToolProposed(AgentRun run, int step, ToolInvocation invocation) {
    }

    /**
     * Called once a tool call has been settled, with the call the model proposed and the
     * call the gate settled on.
     *
     * <p>{@code effective} is <strong>not</strong> what {@link #onToolProposed} was given.
     * That asymmetry is deliberate and is the whole of #131: at proposal time the proposal
     * is the truth, because nothing has gated it yet; once the call has been settled, what
     * the gate settled on is the truth, and a record that says otherwise is wrong about the
     * only question anybody asks it.
     *
     * <p>Measured before this, with a gate that narrows a path — a supported feature, and
     * what {@code ApprovalDecision.approveWithArguments} and every {@code allowWith} in the
     * repository produce:
     *
     * <pre>
     * observer/audit saw = [/etc/shadow]         &lt;- the model's proposal
     * tool actually ran  = [/tmp/harmless.txt]   &lt;- the gate's narrowing
     * </pre>
     *
     * <p>In the itops example the observer stream <em>is</em> the compliance trail, so a
     * reviewer reconstructing a run got the model's proposal — precisely the thing a
     * narrowing gate exists to overrule.
     *
     * <p><strong>What {@code effective} means when nothing ran.</strong> It is not "the call
     * the tool received", which is what this said first and is false
     * on four of the five paths that reach it. An unknown tool, a denied call, and a call
     * skipped because a sibling in the same turn parked all report the proposal while having
     * received nothing — an approximation, since there was no other call to report.
     *
     * <p>A <strong>park</strong> is the one that made the sentence a lie rather than an
     * approximation. A gate may narrow the arguments and <em>then</em> park, so the value
     * reported is a call that no tool has run. Measured, with a gate that narrows and parks:
     *
     * <pre>
     * tool actually ran = []
     * effective         = [/tmp/harmless.txt]
     * </pre>
     *
     * <p>Reporting the proposal there instead would be the worse repair, and #104 is why:
     * what a reviewer is shown has to be the call that would actually run, and
     * {@link dev.agentkit.core.reliability.GateResult#needsAPerson(
     * dev.agentkit.core.reliability.ApprovalNeeded, ToolInvocation)} exists precisely so a
     * narrowing survives the park. So the contract is the one the value can keep on every
     * path: the call as the <em>gate</em> settled it — what ran, or on a park what a
     * reviewer will be shown and what runs when they approve.
     *
     * <p><strong>Whether it ran is {@code disposition}'s to say (#181).</strong> It used to
     * be nobody's: {@code result.isError()} is true for a call a gate stopped <em>and</em>
     * for a call that ran and failed, so an observer could not tell a park from a tool that
     * threw. Measured across all seven ways a proposed call can end, printing every field
     * this callback carried, the seven collapsed onto one bit — see {@link Disposition},
     * which holds the table and the argument for why {@code provenance} is not a proxy for
     * it.
     *
     * <p>The cost of finding out was a fifth parameter on a callback that already had four,
     * so two smaller-looking shapes were measured first and both are worse:
     *
     * <ul>
     *   <li><strong>A component on {@code ToolResult}</strong>, which is what #181 proposed
     *       and guessed was "probably more correct". {@link Disposition}'s javadoc has the
     *       count that decided it: 273 of 283 construction sites belong to tool authors, who
     *       cannot answer this, and it would still not spare the durable path its own
     *       field.</li>
     *   <li><strong>Bundling {@code proposed}, {@code effective} and the disposition into
     *       one record.</strong> Tempting on parameter count and it changes every
     *       implementor's signature just the same, so it buys nothing this change does not
     *       already pay — while re-opening which invocation a row reports, which is #131's
     *       question and was settled deliberately. Worth doing on its own terms, or not at
     *       all.</li>
     * </ul>
     *
     * <p>An implementor written against the four-parameter version stops compiling, which is
     * the intended outcome and the same way #131 moved this interface: an overload would
     * have left those observers silently never called, and an observer that is silently
     * never called is the failure this whole interface exists to prevent.
     *
     * @param run       which run this row belongs to (#311) — a supervisor's step 3 and a
     *     subagent's step 3 are otherwise the same row
     * @param proposed  the call as the model emitted it
     * @param effective the call as the gate settled it — equal to {@code proposed} unless a
     *     gate replaced it
     * @param result    what the model is told, whether or not a tool produced it
     * @param disposition how far the call got — {@link Disposition#reachedTool()} is the
     *     "did this action happen" bit, and the constant says why not when it did not
     */
    default void onToolResult(AgentRun run, int step, ToolInvocation proposed,
                              ToolInvocation effective, ToolResult result,
                              Disposition disposition) {
    }

    /** Called once when a run ends, for any reason. */
    default void onFinish(AgentRun run, AgentResult result) {
    }
}
