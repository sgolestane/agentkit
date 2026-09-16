package dev.agentkit.core.message;

import java.util.List;
import java.util.Objects;

/**
 * A single turn in a conversation: a {@link Role} plus an ordered list of
 * {@link ContentBlock content blocks}.
 *
 * <p>Instances are immutable; the content list is copied defensively and
 * exposed as an unmodifiable list.
 *
 * @param role    the author of the message; never {@code null}
 * @param content the ordered content blocks; never {@code null} or empty
 */
public record Message(Role role, List<ContentBlock> content) {

    public Message {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        content = List.copyOf(content);
        if (content.isEmpty()) {
            throw new IllegalArgumentException("A message must have at least one content block");
        }
    }

    /** Creates a message with a single content block. */
    public static Message of(Role role, ContentBlock block) {
        return new Message(role, List.of(block));
    }

    /** Creates a message from an ordered list of content blocks. */
    public static Message of(Role role, List<ContentBlock> blocks) {
        return new Message(role, blocks);
    }

    /** Creates a user message containing a single text block. */
    public static Message user(String text) {
        return of(Role.USER, TextBlock.of(text));
    }

    /** Creates an assistant message containing a single text block. */
    public static Message assistant(String text) {
        return of(Role.ASSISTANT, TextBlock.of(text));
    }

    /** Creates a system message containing a single text block. */
    public static Message system(String text) {
        return of(Role.SYSTEM, TextBlock.of(text));
    }

    /**
     * Returns the concatenated text of all {@link TextBlock} content blocks,
     * joined by newlines. Non-text blocks are ignored.
     */
    public String text() {
        return content.stream()
                .filter(TextBlock.class::isInstance)
                .map(b -> ((TextBlock) b).text())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }
}
