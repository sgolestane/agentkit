package dev.agentkit.core.context;

import dev.agentkit.core.message.Message;
import java.util.List;

/**
 * Transforms the running conversation history into the message list actually sent
 * to the model on a turn — the single hook through which context engineering
 * (editing, compaction, budgeting) is applied by the agent loop.
 */
@FunctionalInterface
public interface ContextStrategy {

    /** A strategy that sends the history through unchanged. */
    ContextStrategy IDENTITY = history -> history;

    /** Returns the message list to send to the model for {@code history}. */
    List<Message> prepare(List<Message> history);
}
