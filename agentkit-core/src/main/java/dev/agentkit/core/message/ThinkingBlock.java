package dev.agentkit.core.message;

import java.util.Objects;

/**
 * A reasoning/thinking block produced by the model.
 *
 * <p>The {@code signature} is an opaque, provider-supplied token that must be
 * preserved verbatim when replaying the block to the same model on a later turn.
 * It may be empty when the provider omits reasoning content.
 *
 * @param thinking  the (possibly summarised or empty) reasoning text
 * @param signature opaque provider signature; never {@code null}, may be empty
 */
public record ThinkingBlock(String thinking, String signature) implements ContentBlock {

    public ThinkingBlock {
        Objects.requireNonNull(thinking, "thinking");
        Objects.requireNonNull(signature, "signature");
    }

    public static ThinkingBlock of(String thinking) {
        return new ThinkingBlock(thinking, "");
    }
}
