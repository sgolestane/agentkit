package dev.agentkit.core.llm;

/**
 * Token accounting for a single model call.
 *
 * @param inputTokens  tokens consumed by the prompt
 * @param outputTokens tokens generated in the response
 */
public record TokenUsage(long inputTokens, long outputTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0);

    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must be >= 0");
        }
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    /** Returns a new usage that is the element-wise sum of this and {@code other}. */
    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }
}
