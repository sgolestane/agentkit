package dev.agentkit.core.context;

import dev.agentkit.core.message.Message;
import java.util.List;

/**
 * Summarises earlier conversation history into a compact form when it grows too
 * large, so a long-horizon run stays within the context window (contrast
 * {@link ContextEditor}, which prunes rather than summarises).
 */
@FunctionalInterface
public interface Compactor {

    /** A compactor that returns the history unchanged. */
    Compactor NONE = history -> history;

    /** Returns a possibly-compacted copy of {@code history}. */
    List<Message> compact(List<Message> history);
}
