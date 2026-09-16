package dev.agentkit.core.llm;

import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import java.util.Objects;
import java.util.Optional;

/**
 * A provider-agnostic response from an {@link LlmClient}.
 *
 * @param message       the assistant message produced by the model; its role
 *                      must be {@link Role#ASSISTANT}
 * @param stopReason    the normalised reason the turn ended; never {@code null}
 * @param usage         token accounting for the call; never {@code null}
 * @param rawStopReason the provider's original stop-reason string, if available;
 *                      never {@code null} (may be empty). Retained so callers can
 *                      inspect provider-specific reasons that normalise to
 *                      {@link LlmStopReason#OTHER}.
 */
public record LlmResponse(Message message, LlmStopReason stopReason, TokenUsage usage,
                          Optional<String> rawStopReason) {

    public LlmResponse {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(rawStopReason, "rawStopReason");
        if (message.role() != Role.ASSISTANT) {
            throw new IllegalArgumentException(
                    "LLM response message must have role ASSISTANT, was " + message.role());
        }
    }

    /** Creates a response without a raw provider stop-reason string. */
    public static LlmResponse of(Message message, LlmStopReason stopReason, TokenUsage usage) {
        return new LlmResponse(message, stopReason, usage, Optional.empty());
    }

    /** Whether the model requested tool execution. */
    public boolean requestsTools() {
        return stopReason == LlmStopReason.TOOL_USE;
    }
}
