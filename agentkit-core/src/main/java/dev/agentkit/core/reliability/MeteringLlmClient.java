package dev.agentkit.core.reliability;

import dev.agentkit.core.llm.DelegatingLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.StreamHandler;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The {@link LlmClient} decorator produced by {@link UsageMeter#wrap}: it forwards
 * every call and reports what the call spent back to the meter.
 *
 * <p>Deliberately a named public type rather than an anonymous wrapper, and a
 * {@link DelegatingLlmClient}, so code that inspects a client can see past it — a
 * decorator that hides what it wraps would defeat checks like the one rejecting a
 * {@code BudgetLlmClient} on a Temporal worker. Identity equality (not a record's
 * component equality) is used for the same reason a stateful decorator should not be
 * interchangeable with another wrapping the same delegate.
 */
public final class MeteringLlmClient implements DelegatingLlmClient {

    private final UsageMeter meter;
    private final String role;
    private final LlmClient delegate;

    MeteringLlmClient(UsageMeter meter, String role, LlmClient delegate) {
        this.meter = Objects.requireNonNull(meter, "meter");
        this.role = Objects.requireNonNull(role, "role");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** The client this one wraps. */
    @Override
    public LlmClient delegate() {
        return delegate;
    }

    /** The role its calls are tallied under. */
    public String role() {
        return role;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        return metered(() -> delegate.generate(request));
    }

    @Override
    public LlmResponse generate(LlmRequest request, StreamHandler handler) {
        // Forwarded rather than collapsed onto the blocking call, so a streaming-capable
        // delegate still delivers real deltas through a metered client.
        return metered(() -> delegate.generate(request, handler));
    }

    private LlmResponse metered(Supplier<LlmResponse> call) {
        LlmResponse response = call.get();
        // Recorded only on a returned response — a failed call carries no usage to
        // record. Note that is a floor, not the truth: a provider may still bill an
        // attempt that generated tokens and then failed.
        meter.record(role, response.usage());
        return response;
    }

    @Override
    public String toString() {
        return "MeteringLlmClient[role=" + role + ", delegate=" + delegate + "]";
    }
}
