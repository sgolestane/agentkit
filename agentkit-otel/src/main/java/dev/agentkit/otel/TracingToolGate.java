package dev.agentkit.otel;

import dev.agentkit.core.reliability.ForwardingToolGate;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.Quoted;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A {@link ToolGate} decorator that records each decision as its own span.
 *
 * <p>Without it, gating is invisible. The agent loop evaluates the gate <em>before</em>
 * calling {@code Tool.execute}, so {@link TracingTool} — which wraps execution — never
 * sees a denied call at all: the trace shows a model turn asking for a tool and then
 * nothing, as though the request evaporated. And a human-in-the-loop approver that blocks
 * for minutes puts that time nowhere, which is the opposite of useful for the one feature
 * whose latency is measured in human attention.
 *
 * <p>The gate span is a <em>sibling</em> of {@code execute_tool}, not its parent, because
 * that is the real shape: the gate decides, finishes, and only then does the tool run.
 * A denied call therefore produces a gate span with no execution span beside it.
 *
 * <p>No reason text is recorded. A denial reason is written by your gate, but it is
 * written <em>about</em> an invocation and routinely quotes it ("cannot delete
 * /home/ada/taxes.csv"), so it is the user's data by another route — the same reason this
 * module records no prompts. Add it yourself on {@link Span#current()} from inside your
 * gate, where you know what is safe to emit.
 */
public final class TracingToolGate extends ForwardingToolGate {

    /**
     * How much of a failure message a span status carries.
     *
     * <p>An operator gets the whole thing from the recorded exception; the status is the
     * one-line summary an incident view shows, and the text can be a model's.
     */
    private static final int MAX_STATUS_CHARS = 300;

    private final Tracer tracer;
    private final ToolGate delegate;

    TracingToolGate(Tracer tracer, ToolGate delegate) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** Whether this wrapper came from the same telemetry instance as {@code other}. */
    boolean belongsTo(Tracer other) {
        return tracer.equals(other);
    }

    /** The gate this one wraps. */
    @Override
    public ToolGate delegate() {
        return delegate;
    }

    /**
     * The one thing this decorates. The read-only guarantee is inherited from
     * {@link ForwardingToolGate}, which is the point of extending it: an earlier version of
     * this class implemented {@code ToolGate} directly, dropped what it was handed, and
     * turned a wrapped read-only gate into deny-everything — so turning tracing on changed
     * the policy being traced.
     */
    @Override
    public GateResult evaluate(Tool tool, ToolInvocation invocation) {
        return traced(invocation, () -> delegate.evaluate(tool, invocation));
    }

    private GateResult traced(ToolInvocation invocation, Supplier<GateResult> decision) {
        Span span = tracer.spanBuilder(GenAi.OPERATION_GATE_TOOL + " " + invocation.name())
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(GenAi.TOOL_NAME, invocation.name())
                .setAttribute(GenAi.TOOL_CALL_ID, invocation.id())
                .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            GateResult result = decision.get();
            span.setAttribute(GenAi.AGENTKIT_GATE_ALLOWED, result.allowed());
            // A gate may approve with edited arguments — a human narrowing a delete to one
            // path. Worth knowing the model's proposal is not what ran.
            span.setAttribute(GenAi.AGENTKIT_GATE_REPLACED, result.replacement().isPresent());
            // A denial is the gate working, not a fault: the loop turns it into an error
            // result the model reacts to. Marking the span ERROR would fill a dashboard
            // with alarms every time a guardrail did its job.
            stoppedBy(result, invocation)
                    .ifPresent(why -> recordStop(span, why, result, invocation));
            return result;
        } catch (Throwable t) {
            // The outcome goes on here too, and that is the whole of #121's second half.
            // A composed gate raises the refusal from *inside* evaluate — ToolGates.allOf
            // applies effectiveFor between members — so control jumps here and the three
            // lines above never ran. The first version of this change set the attribute
            // only on the returning path, so a dashboard counting refusals missed every
            // one that happened inside an allOf, which is the repo's own recommended way
            // to compose gates. It unified the status code and claimed it had unified the
            // traces.
            //
            // allowed and replaced stay unset here, deliberately: there is no decision to
            // describe. The composite never returned one.
            span.setAttribute(GenAi.AGENTKIT_GATE_OUTCOME,
                    t instanceof GateResult.RefusedSubstitution
                            ? GenAi.GATE_OUTCOME_REFUSED : GenAi.GATE_OUTCOME_FAILED);
            span.setAttribute(GenAi.ERROR_TYPE, t.getClass().getName());
            // Escaped, and recorded through the escaped wrapper. A span exporter renders
            // a status message and an exception into an operator's incident view exactly
            // as a log line does, and a tool that names the argument it could not satisfy
            // puts the model's own text there. The log site one frame out already goes
            // through Quoted; this is the second renderer of the same object (#98). Cut for
            // the reason recordStop states: the text can be a model's.
            span.setStatus(StatusCode.ERROR, Cut.to(t.getMessage() == null
                    ? t.getClass().getSimpleName() : Quoted.of(t.getMessage()), MAX_STATUS_CHARS));
            span.recordException(Quoted.failure(t));
            throw t;
        } finally {
            span.end();
        }
    }

    /**
     * What the decision comes to, asked of the same method the runner will ask (#121).
     *
     * <p>The span used to close on the gate's answer, and the answer is not the outcome. A
     * replacement that renames the tool or renumbers the call is refused <em>after</em>
     * {@code evaluate} returns, at the runner, so a trace recorded
     * {@code gate.allowed=true}, {@code gate.replaced=true} and then nothing — the exact
     * shape this class's javadoc opens by calling the problem it exists to solve. An
     * operator reading a gate that allowed with a replacement, followed by silence, has
     * been told the opposite of what happened.
     *
     * <p>Asked rather than guessed. {@code GateResult.effectiveFor} is the one spelling of
     * the rule — four runners call it, and {@code ToolGates.allOf} between members — and it is a pure function of the
     * decision and the proposal — so calling it here costs two string comparisons and
     * cannot come to a different answer than the runner does. Re-implementing the test
     * would be a third runner of a rule that exists because there were two.
     *
     * <p><strong>It does not rethrow.</strong> The refusal must surface where the runner
     * raises it, inside the runner's own {@code try}, and a decorator that changed when an
     * exception left {@code evaluate} would be changing the policy it was asked to observe
     * — which is the mistake an earlier version of this class made in a different form,
     * recorded above.
     *
     * <p>Marked {@code ERROR}, unlike a denial or a park. Both of those are a guardrail
     * doing its job; a
     * replacement the runner will not honour is a mistake in the gate, and it already
     * produced an {@code ERROR} span whenever the same gate was composed with
     * {@link dev.agentkit.core.reliability.ToolGates#allOf}, because {@code allOf} applies
     * {@code effectiveFor} between members and the throw then happens inside this span.
     * Same policy, same mistake, two different traces depending on composition — so the
     * uncomposed case is brought to the composed one rather than the other way round.
     */
    private static Optional<String> stoppedBy(GateResult result, ToolInvocation invocation) {
        if (!result.allowed()) {
            // Parked and denied are both "the call did not run", and they are not the same
            // fact about what happens next: one is finished and one is a question somebody
            // still owes an answer on. Collapsing them would leave an operator unable to
            // see a queue building up, which is the failure mode a human-in-the-loop policy
            // actually has.
            return Optional.of(result.awaiting().isPresent()
                    ? GenAi.GATE_OUTCOME_PARKED
                    : GenAi.GATE_OUTCOME_DENIED);
        }
        try {
            result.effectiveFor(invocation);
            // Nothing. This gate did not stop the call, and whether anything else will is
            // not a question it can answer — see AGENTKIT_GATE_OUTCOME for why saying
            // "allowed" here was the bug rather than the fix.
            return Optional.empty();
        } catch (GateResult.RefusedSubstitution refused) {
            // The refusal's own type, not RuntimeException. Catching the supertype was
            // correct today — effectiveFor can raise nothing else — and it would have
            // relabelled any future validation failure as "the gate's replacement was
            // rejected", on a span an operator reads to find out what the gate did.
            return Optional.of(GenAi.GATE_OUTCOME_REFUSED);
        }
    }

    /**
     * Marks the span for a gate that stopped the call, and does it the same way on both
     * paths that can reach here.
     *
     * <p>{@code ERROR} for a refused substitution and a throw, not for a denial. A denial is
     * a guardrail doing its job and marking it would fill a dashboard with alarms every time
     * one worked; a replacement the runner will not honour is a mistake in the gate, and it
     * already produced an {@code ERROR} span whenever the same gate was composed with
     * {@code ToolGates.allOf}, because {@code allOf} applies {@code effectiveFor} between
     * members so the throw lands inside this span.
     *
     * <p>The message is cut. It quotes the proposed call's id, which nothing validates — a
     * model chooses it — and the pattern that reaches this branch is the one
     * {@code GateResult.effectiveFor}'s javadoc names as the upgrade hazard: a gate that
     * stamps every call with an audit id. Measured before the cut: a hostile id produced a
     * 204,000-character span status where the previous version set none at all, and
     * {@code Quoted.of} ran twice over the same text because the message arrives already
     * escaped.
     */
    private static void recordStop(Span span, String why, GateResult result,
                                   ToolInvocation invocation) {
        span.setAttribute(GenAi.AGENTKIT_GATE_OUTCOME, why);
        if (GenAi.GATE_OUTCOME_DENIED.equals(why) || GenAi.GATE_OUTCOME_PARKED.equals(why)) {
            // Neither is an error. A denial is a guardrail doing its job and a park is a
            // policy asking the question it was written to ask; marking either would fill a
            // dashboard with alarms every time the control worked. Reaching refusalFor with
            // a parked result would also throw the "unreachable" guard, since a parked
            // result carries no replacement to refuse.
            return;
        }
        RuntimeException refusal = refusalFor(result, invocation);
        span.setAttribute(GenAi.ERROR_TYPE, refusal.getClass().getName());
        span.setStatus(StatusCode.ERROR, Cut.to(String.valueOf(refusal.getMessage()), MAX_STATUS_CHARS));
        span.recordException(Quoted.failure(refusal));
    }

    /** The refusal itself, for the message — asked once more rather than threaded through. */
    private static RuntimeException refusalFor(GateResult result, ToolInvocation invocation) {
        try {
            result.effectiveFor(invocation);
            throw new IllegalStateException("unreachable: this gate stopped the call");
        } catch (GateResult.RefusedSubstitution refused) {
            return refused;
        }
    }

    @Override
    public String toString() {
        return "TracingToolGate[" + delegate + "]";
    }
}
