package dev.agentkit.host.models;

import dev.agentkit.core.llm.TokenUsage;

/** What model calls spent: how many, their tokens, and their estimated cost in US dollars. */
public record Spend(long calls, long inputTokens, long outputTokens, double usd) {

    public static final Spend ZERO = new Spend(0, 0, 0, 0);

    /** One call's spend. */
    public static Spend of(TokenUsage usage, double usd) {
        return new Spend(1, usage.inputTokens(), usage.outputTokens(), usd);
    }

    public long tokens() {
        return inputTokens + outputTokens;
    }

    public Spend plus(Spend other) {
        return new Spend(calls + other.calls, inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                usd + other.usd);
    }
}
