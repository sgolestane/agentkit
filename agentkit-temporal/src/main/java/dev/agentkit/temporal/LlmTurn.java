package dev.agentkit.temporal;

import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import java.util.Objects;

/**
 * The serializable result of one LLM activity call.
 *
 * <p>A trimmed, converter-friendly view of {@code LlmResponse} that omits the
 * {@code Optional} raw-stop-reason field (the durable loop does not need it),
 * so the activity boundary carries only records, enums, and maps.
 *
 * @param message    the assistant message the model produced
 * @param stopReason the normalised reason the turn ended
 * @param usage      token accounting for the call
 */
public record LlmTurn(Message message, LlmStopReason stopReason, TokenUsage usage) {

    public LlmTurn {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(usage, "usage");
    }
}
