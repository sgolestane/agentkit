package dev.agentkit.core.llm;

/**
 * Provider-agnostic reason a model turn ended, normalised from the underlying
 * LLM provider's stop reason.
 */
public enum LlmStopReason {
    /** The model finished its turn naturally. */
    END_TURN,
    /** The model requested one or more tool calls. */
    TOOL_USE,
    /** The model hit the output token limit before finishing. */
    MAX_TOKENS,
    /** The model declined to respond for safety reasons. */
    REFUSAL,
    /** The turn paused (e.g. a long-running server-side tool) and can be resumed. */
    PAUSE,
    /** Any other or unknown stop reason. */
    OTHER
}
