package dev.agentkit.core.llm;

/**
 * Thrown by an {@link LlmClient} when a model call fails (transport error,
 * provider error, or an unmappable response).
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
