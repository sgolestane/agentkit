package dev.agentkit.core.message;

/**
 * The author of a {@link Message} in a conversation.
 */
public enum Role {
    /** Input from the user or the orchestrating application. */
    USER,
    /** Output produced by the model. */
    ASSISTANT,
    /** Operator/system-level instruction. */
    SYSTEM
}
