package dev.agentkit.core.context;

import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ToolResultBlock;
import java.util.ArrayList;
import java.util.List;

/**
 * Clears the content of tool results older than a recent window, replacing each
 * with a short placeholder. This reclaims the biggest source of context bloat in
 * tool-heavy agents (large tool outputs the model no longer needs) while keeping
 * the {@code tool_use}/{@code tool_result} pairing intact.
 */
public final class ClearToolResultsEditor implements ContextEditor {

    /** Default placeholder substituted for a cleared tool result. */
    public static final String DEFAULT_PLACEHOLDER = "[earlier tool result cleared to save context]";

    private final int keepRecentMessages;
    private final String placeholder;

    public ClearToolResultsEditor(int keepRecentMessages) {
        this(keepRecentMessages, DEFAULT_PLACEHOLDER);
    }

    public ClearToolResultsEditor(int keepRecentMessages, String placeholder) {
        if (keepRecentMessages < 0) {
            throw new IllegalArgumentException("keepRecentMessages must be >= 0");
        }
        this.keepRecentMessages = keepRecentMessages;
        this.placeholder = java.util.Objects.requireNonNull(placeholder, "placeholder");
    }

    @Override
    public List<Message> edit(List<Message> history) {
        int cutoff = history.size() - keepRecentMessages;
        if (cutoff <= 0) {
            return history;
        }
        List<Message> result = new ArrayList<>(history.size());
        for (int i = 0; i < history.size(); i++) {
            Message message = history.get(i);
            result.add(i < cutoff ? clearToolResults(message) : message);
        }
        return result;
    }

    private Message clearToolResults(Message message) {
        boolean changed = false;
        List<ContentBlock> blocks = new ArrayList<>(message.content().size());
        for (ContentBlock block : message.content()) {
            if (block instanceof ToolResultBlock r
                    && r.content().length() > placeholder.length()
                    && !r.content().equals(placeholder)) {
                // The placeholder replaces the content, not the attribution. This
                // edit replaces the run's own transcript in place, so dropping the
                // label here destroys the record of what the run has read — which
                // is the one thing a caller cannot reconstruct afterwards.
                blocks.add(new ToolResultBlock(r.toolUseId(), placeholder, r.isError(),
                        r.provenance()));
                changed = true;
            } else {
                blocks.add(block);
            }
        }
        return changed ? Message.of(message.role(), blocks) : message;
    }
}
