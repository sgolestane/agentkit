package dev.agentkit.otel;

import dev.agentkit.core.tool.ForwardingTool;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.Quoted;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.Objects;

/**
 * A {@link Tool} decorator that records each execution as an {@code execute_tool} span.
 *
 * <p>Arguments and results are <strong>not</strong> recorded. They routinely carry the
 * user's data — a query, a file, a customer record — and a trace exporter is not a place
 * to put it by default. Add what you need on the current span from inside your own tool,
 * where you know what is safe to emit.
 *
 * <p>Everything except {@link #execute} comes from {@link ForwardingTool}. It was nine
 * hand-written methods here until #296, each with a comment saying why forgetting it would
 * hurt — {@code spec()} discarded, {@code sideEffects()} reading UNKNOWN and dropping every
 * instrumented tool out of a rehearsal, and the two gate declarations reading {@code false}
 * and letting a blocking approver onto a durable worker. Instrumenting a tool must not
 * change what it says about itself; inheriting that is better than remembering it.
 */
public final class TracingTool extends ForwardingTool {

    private final Tracer tracer;
    private final Tool delegate;

    TracingTool(Tracer tracer, Tool delegate) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** Whether this wrapper came from the same telemetry instance as {@code other}. */
    boolean belongsTo(Tracer other) {
        return tracer.equals(other);
    }

    /** The tool this one wraps, and the one every unoverridden method forwards to. */
    @Override
    public Tool delegate() {
        return delegate;
    }

    /**
     * The span survives the binding, and so does the binding (#317).
     *
     * <p>{@code delegate().boundTo(run)} alone would hand the agent loop the
     * <em>untraced</em> tool to execute, so instrumenting a {@code delegate} would silently
     * stop producing spans for it — a decorator dropping its own decoration in the act of
     * forwarding. Rebuilding is one line and keeps both.
     */
    @Override
    protected Tool rebuiltAround(Tool bound) {
        return new TracingTool(tracer, bound);
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        Span span = tracer.spanBuilder(GenAi.OPERATION_EXECUTE_TOOL + " " + delegate.name())
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(GenAi.OPERATION_NAME, GenAi.OPERATION_EXECUTE_TOOL)
                .setAttribute(GenAi.TOOL_NAME, delegate.name())
                .setAttribute(GenAi.TOOL_CALL_ID, invocation.id())
                .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            ToolResult result = delegate.execute(invocation);
            // A tool that returns an error result has not thrown — the loop feeds the
            // message back to the model and continues — so this is an attribute rather
            // than a span status, which would misreport a handled failure as a fault.
            span.setAttribute(GenAi.AGENTKIT_TOOL_ERROR, result.isError());
            // Read from the delegate's declaration rather than the result, because
            // this wrapper runs before the runner attributes it — the result in hand
            // has not been through attributedTo yet.
            span.setAttribute(GenAi.AGENTKIT_TOOL_PROVENANCE,
                    result.attributedTo(delegate).provenance().name());
            return result;
        } catch (Throwable t) {
            // Throwable, not RuntimeException: an AssertionError out of a tool is still
            // a failed execution, and catching only the former left it as a green span.
            span.setAttribute(GenAi.ERROR_TYPE, t.getClass().getName());
            // Escaped, and recorded through the escaped wrapper. A span exporter renders
            // a status message and an exception into an operator's incident view exactly
            // as a log line does, and a tool that names the argument it could not satisfy
            // puts the model's own text there. The log site one frame out already goes
            // through Quoted; this is the second renderer of the same object (#98).
            span.setStatus(StatusCode.ERROR, t.getMessage() == null
                    ? t.getClass().getSimpleName() : Quoted.of(t.getMessage()));
            span.recordException(Quoted.failure(t));
            throw t;
        } finally {
            span.end();
        }
    }

    @Override
    public String toString() {
        return "TracingTool[" + delegate + "]";
    }
}
