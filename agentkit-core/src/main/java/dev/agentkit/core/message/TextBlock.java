package dev.agentkit.core.message;

import java.util.Objects;

/**
 * Plain text content authored by the user or the model.
 *
 * @param text the text content; never {@code null}
 */
public record TextBlock(String text) implements ContentBlock {

    public TextBlock {
        Objects.requireNonNull(text, "text");
    }

    public static TextBlock of(String text) {
        return new TextBlock(text);
    }
}
