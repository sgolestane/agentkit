package dev.agentkit.core.reliability;

import dev.agentkit.core.llm.DelegatingLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.StreamHandler;
import dev.agentkit.core.util.Quoted;
import java.util.Objects;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link LlmClient} decorator that retries transient {@link LlmException}s with
 * exponential backoff, so a flaky network or a rate-limit blip does not abort an
 * unsupervised run.
 *
 * <p>The {@code sleeper} is injectable (defaulting to {@link Thread#sleep}) so
 * tests can exercise backoff without real delays. An interrupt during a backoff
 * sleep restores the interrupt flag and rethrows the last error.
 *
 * <p>Retry applies to the streaming path as well. Because a retried call restarts
 * from scratch, a failure that occurs <em>after</em> some deltas have already been
 * emitted causes the handler to see those earlier fragments again on the retry —
 * the final {@link LlmResponse} is still correct, but a live view may briefly
 * replay text. Most transient failures occur before any delta, so this is rare.
 */
public final class RetryingLlmClient implements DelegatingLlmClient {

    private static final Logger log = LoggerFactory.getLogger(RetryingLlmClient.class);

    private final LlmClient delegate;
    private final RetryPolicy policy;
    private final LongConsumer sleeper;

    public RetryingLlmClient(LlmClient delegate, RetryPolicy policy) {
        this(delegate, policy, RetryingLlmClient::sleep);
    }

    RetryingLlmClient(LlmClient delegate, RetryPolicy policy, LongConsumer sleeper) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /** The client this one retries. */
    @Override
    public LlmClient delegate() {
        return delegate;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        return withRetry(() -> delegate.generate(request));
    }

    @Override
    public LlmResponse generate(LlmRequest request, StreamHandler handler) {
        return withRetry(() -> delegate.generate(request, handler));
    }

    private LlmResponse withRetry(Supplier<LlmResponse> call) {
        LlmException last = null;
        for (int attempt = 0; attempt <= policy.maxRetries(); attempt++) {
            try {
                return call.get();
            } catch (LlmException e) {
                last = e;
                if (attempt == policy.maxRetries()) {
                    break;
                }
                long delay = policy.delayForAttempt(attempt + 1);
                log.warn("LLM call failed (attempt {}/{}); retrying in {}ms: {}",
                        attempt + 1, policy.maxRetries() + 1, delay, Quoted.of(e.getMessage()));
                sleeper.accept(delay);
            }
        }
        throw new LlmException("LLM call failed after " + (policy.maxRetries() + 1) + " attempt(s)", last);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while backing off before retry", e);
        }
    }
}
