package dev.agentkit.anthropic;

import com.anthropic.models.messages.CacheControlEphemeral;

/**
 * Prompt-caching policy for {@link AnthropicLlmClient}.
 *
 * <p>When enabled, the client marks the stable request prefix (tool definitions +
 * system prompt) and the growing conversation with {@code cache_control}
 * breakpoints. Because an agent loop re-sends that prefix on every turn, the
 * cached span is re-read at roughly a tenth of the input price instead of being
 * paid for in full each step — a large cost and latency win over a multi-turn run.
 *
 * <p>Caching is a pure cost/latency optimization with no effect on the model's
 * output. Below the model's minimum cacheable prefix size the breakpoints silently
 * do nothing (no error, no write premium). Explicit breakpoints work on both the
 * first-party API and Amazon Bedrock.
 */
public enum CachePolicy {

    /** No caching (the default). */
    NONE,

    /** Cache with the default 5-minute TTL — the right choice for most agent loops. */
    EPHEMERAL_5M,

    /**
     * Cache with a 1-hour TTL. The write premium is higher, so this pays off only
     * for bursty traffic with gaps longer than five minutes between turns.
     */
    EPHEMERAL_1H;

    /** Whether this policy adds cache breakpoints. */
    boolean enabled() {
        return this != NONE;
    }

    /**
     * The SDK cache-control marker for this policy.
     *
     * @throws IllegalStateException if called on {@link #NONE} (guard with {@link #enabled()})
     */
    CacheControlEphemeral cacheControl() {
        return switch (this) {
            case NONE -> throw new IllegalStateException("NONE has no cache control");
            case EPHEMERAL_5M ->
                    CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_5M).build();
            case EPHEMERAL_1H ->
                    CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build();
        };
    }
}
