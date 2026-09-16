package dev.agentkit.otel;

import dev.agentkit.core.llm.DelegatingLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.StreamHandler;
import dev.agentkit.core.util.Quoted;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The {@link LlmClient} decorator produced by {@link AgentTelemetry#instrument}: every
 * call becomes a {@code chat} span, a duration sample, and — when the call returns — a
 * pair of token-usage samples.
 *
 * <p>Instrumenting at the client rather than at the agent loop is deliberate. The loop
 * only sees its own turns; compaction, verification, reflection, planning and critics
 * each call the model through their own client, and in a compacting run those calls can
 * cost more than the turns that triggered them. Wrap each one with its own {@code role}
 * and the trace shows where the run actually went.
 */
public final class TracingLlmClient implements DelegatingLlmClient {

    private final Tracer tracer;
    private final LongHistogram tokenUsage;
    private final DoubleHistogram operationDuration;
    private final String role;
    private final String provider;
    private final LlmClient delegate;

    TracingLlmClient(Tracer tracer, LongHistogram tokenUsage, DoubleHistogram operationDuration,
            String role, String provider, LlmClient delegate) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.tokenUsage = Objects.requireNonNull(tokenUsage, "tokenUsage");
        this.operationDuration = Objects.requireNonNull(operationDuration, "operationDuration");
        this.role = Objects.requireNonNull(role, "role");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public LlmClient delegate() {
        return delegate;
    }

    /** Whether this wrapper came from the same telemetry instance as {@code other}. */
    boolean belongsTo(Tracer other) {
        return tracer.equals(other);
    }

    /** The role its calls are attributed to. */
    public String role() {
        return role;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        return traced(request, false, () -> delegate.generate(request));
    }

    @Override
    public LlmResponse generate(LlmRequest request, StreamHandler handler) {
        // Forwarded rather than collapsed onto the blocking call, so a streaming-capable
        // delegate still delivers real deltas through a traced client.
        return traced(request, true, () -> delegate.generate(request, handler));
    }

    private LlmResponse traced(LlmRequest request, boolean streaming, Supplier<LlmResponse> call) {
        Span span = tracer.spanBuilder(GenAi.OPERATION_CHAT + " " + request.model())
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(GenAi.OPERATION_NAME, GenAi.OPERATION_CHAT)
                .setAttribute(GenAi.PROVIDER_NAME, provider)
                .setAttribute(GenAi.REQUEST_MODEL, request.model())
                .setAttribute(GenAi.REQUEST_MAX_TOKENS, (long) request.maxTokens())
                .setAttribute(GenAi.REQUEST_STREAM, streaming)
                .setAttribute(GenAi.AGENTKIT_ROLE, role)
                .startSpan();

        long startNanos = System.nanoTime();
        String errorType = null;
        // Made current so that anything the delegate itself instruments — an HTTP client,
        // a nested call — attaches here rather than to whatever ran before it.
        try (Scope ignored = span.makeCurrent()) {
            LlmResponse response = call.get();
            span.setAttribute(GenAi.USAGE_INPUT_TOKENS, response.usage().inputTokens());
            span.setAttribute(GenAi.USAGE_OUTPUT_TOKENS, response.usage().outputTokens());
            span.setAttribute(GenAi.RESPONSE_FINISH_REASONS, List.of(finishReason(response)));
            recordTokens(response, request);
            return response;
        } catch (Throwable t) {
            // Throwable, not RuntimeException: an OutOfMemoryError or an AssertionError
            // from the delegate is still a failed call, and catching only the former
            // left it recorded as a success — green span, no error.type, and a duration
            // sample indistinguishable from a healthy one.
            errorType = t.getClass().getName();
            span.setAttribute(GenAi.ERROR_TYPE, errorType);
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
            // Duration is recorded on both paths: a failing provider that is slow is the
            // thing you most want on a dashboard, and dropping those samples would make
            // the latency histogram describe only the happy path.
            operationDuration.record((System.nanoTime() - startNanos) / 1_000_000_000.0,
                    durationAttributes(request, errorType));
            span.end();
        }
    }

    private void recordTokens(LlmResponse response, LlmRequest request) {
        Attributes base = baseAttributes(request);
        tokenUsage.record(response.usage().inputTokens(),
                base.toBuilder().put(GenAi.TOKEN_TYPE, GenAi.TOKEN_TYPE_INPUT).build());
        tokenUsage.record(response.usage().outputTokens(),
                base.toBuilder().put(GenAi.TOKEN_TYPE, GenAi.TOKEN_TYPE_OUTPUT).build());
    }

    private Attributes durationAttributes(LlmRequest request, String errorType) {
        AttributesBuilder builder = baseAttributes(request).toBuilder();
        if (errorType != null) {
            builder.put(GenAi.ERROR_TYPE, errorType);
        }
        return builder.build();
    }

    private Attributes baseAttributes(LlmRequest request) {
        return Attributes.builder()
                .put(GenAi.OPERATION_NAME, GenAi.OPERATION_CHAT)
                .put(GenAi.PROVIDER_NAME, provider)
                .put(GenAi.REQUEST_MODEL, request.model())
                .put(GenAi.AGENTKIT_ROLE, role)
                .build();
    }

    private static String finishReason(LlmResponse response) {
        // The conventions want the provider's own wording, which LlmResponse keeps.
        // Falling back to the normalised reason loses real distinctions — an Anthropic
        // `refusal` and an OpenAI `content_filter` both normalise to OTHER — and for
        // OpenAI-shaped providers it is actively wrong, reporting `stop` as `end_turn`.
        return response.rawStopReason()
                .orElseGet(() -> response.stopReason().name().toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return "TracingLlmClient[role=" + role + ", delegate=" + delegate + "]";
    }
}
