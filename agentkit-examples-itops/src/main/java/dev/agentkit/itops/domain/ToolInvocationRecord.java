package dev.agentkit.itops.domain;

import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.util.Frozen;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One tool call, with what was asked, what the supervisor said, and what came back.
 *
 * <h2>It carries the invocation, not the parts of one (#170)</h2>
 *
 * <p>This held {@code String toolName} plus {@code Map arguments}, built by
 * {@code AuditObserver.onToolResult} out of {@code effective.name()} and
 * {@code effective.arguments()} — one {@link ToolInvocation}, taken apart at the call site
 * and reassembled here. It now carries the invocation itself, which deletes the second copy
 * (see {@link ApprovalRequest} for the measured cost of that copy, which this record paid
 * too) and makes "the tool name and the arguments describe the same call" a fact about the
 * type rather than a property of one caller.
 *
 * <p><strong>Which call this holds is unchanged, and that matters more here than the
 * copy.</strong> {@code AuditObserver} passes {@code effective} — the call the gate settled
 * on — and it still does. #131 and #180 established that asymmetry deliberately:
 * {@code AgentObserver.onToolProposed} reports the proposal because nothing has gated it
 * yet, and {@code onToolResult} reports what the gate settled on because that is the only
 * answer to the question an auditor asks. Substituting the whole value for its parts must
 * not be a way to quietly re-answer that, so this record is handed the same
 * {@code effective} invocation it was handed before, and a test pins the settled call
 * against a narrowing gate.
 *
 * <h2>Why {@link Frozen#deeply} and not {@code Map.copyOf} (#132)</h2>
 *
 * <p>Inherited from {@code ToolInvocation} now, rather than restated. The same null-value
 * rejection {@code ApprovalRequest} documents was reached here through a worse door: this
 * record is built from an observer callback, and {@code Agent.executeTools} called the
 * observer <em>outside</em> the {@code catch (RuntimeException)} that {@code runTool} wraps
 * the gate and the tool in — so where the approval path merely failed to raise an approval,
 * this one threw after the tool had already run.
 *
 * <p>Measured with a scripted run and a single <code>{"email": null}</code>:
 *
 * <pre>
 * ESCAPED Agent.run: java.lang.NullPointerException
 * tool actually ran : true
 * audit rows written: []
 * onFinish called   : false
 * </pre>
 *
 * <p>The privileged action was performed, nothing recorded it, and the caller got an
 * exception rather than an {@code AgentResult}. An audit trail that disappears exactly when
 * the arguments are unusual is the least useful moment for it to disappear.
 *
 * <p>Copying nulls through fixed this instance. It did not fix the shape — any observer
 * that throws for any reason still killed a run after its tools had run, which was #166 and
 * is now absorbed by {@code Agent}'s observation guard.
 *
 * <p>Kept separately from the event stream even though every field also appears there,
 * because these are the rows an auditor queries — "every privileged group change this
 * month", "every call whose risk was escalated" — and a query like that should not have to
 * pattern-match over a heterogeneous event log.
 *
 * @param id            stable identifier for this row. Not the model's id for the call —
 *                      that is {@link #callId()}, and the two are different things: a row
 *                      identifies a record, a call id identifies a call
 * @param executionId   the run that made the call
 * @param invocation    the call as the gate settled it: its tool name, its arguments after
 *                      any supervisor edit, and the model's id for it
 * @param baselineRisk  the tool's declared risk
 * @param effectiveRisk the risk after the supervisor considered the arguments
 * @param verdict       how the call ended, as {@code Disposition.name()} — what the
 *                      supervisor decided when it decided anything, and otherwise how far
 *                      the call got. It held {@code "ERROR"}/{@code "OK"}, which is
 *                      {@link #error()} spelled a second way and is the same value for a
 *                      parked call and a tool that threw mid-write (#181)
 * @param approvalId    the approval that unblocked it, when there was one
 * @param idempotencyKey what made a repeat of this call safe
 * @param startedAt     when the call began
 * @param finishedAt    when it returned
 * @param error         {@code true} when the model was handed an error — which is not
 *                      the same as "the action did not happen", and is why
 *                      {@link #verdict()} exists in its own right
 * @param result        what the tool returned, verbatim
 */
public record ToolInvocationRecord(String id, String executionId, ToolInvocation invocation,
                                   Risk baselineRisk, Risk effectiveRisk, String verdict,
                                   String approvalId, String idempotencyKey, Instant startedAt,
                                   Instant finishedAt, boolean error, String result) {

    public ToolInvocationRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(startedAt, "startedAt");
    }

    /** Which tool, as the gate settled it. */
    public String toolName() {
        return invocation.name();
    }

    /** The arguments as sent, after any supervisor edit. */
    public Map<String, Object> arguments() {
        return invocation.arguments();
    }

    /**
     * The model's own id for this call.
     *
     * <p>Recovered by #170. It is what joins this row to the {@code TOOL_STARTED} event and
     * to the {@code ApprovalRequest} that parked the same call, none of which was possible
     * while the row held only a name and a map.
     */
    public String callId() {
        return invocation.id();
    }
}
