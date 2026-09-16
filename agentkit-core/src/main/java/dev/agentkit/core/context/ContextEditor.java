package dev.agentkit.core.context;

import dev.agentkit.core.message.Message;
import java.util.List;

/**
 * Prunes stale content from a conversation history <em>in place of</em>
 * summarising it (contrast {@link Compactor}). Editing removes bulk — typically
 * old tool results — while preserving the message/tool-call structure, so the
 * transcript stays valid and cheap without a model call.
 *
 * <p>Two are shipped and they compose through {@link #andThen}:
 * {@link ClearToolResultsEditor} drops old results by age, and
 * {@link BoundedFailureTextEditor} bounds how much of the transcript is failure text
 * somebody else wrote and collapses a failure the run has already read (#151).
 */
@FunctionalInterface
public interface ContextEditor {

    /** An editor that returns the history unchanged. */
    ContextEditor NONE = history -> history;

    /** Returns a possibly-pruned copy of {@code history}. */
    List<Message> edit(List<Message> history);

    /**
     * This editor, then {@code next}.
     *
     * <p>{@link ContextStrategies#of} composes an editor with a {@link Compactor} and there
     * was no way to compose an editor with another one, so a deployment wanting both shipped
     * editors had to hand-write the lambda — and the order they run in is a decision worth
     * making rather than one worth retyping. Editing is by definition order-sensitive: what
     * the first pass leaves is what the second sees.
     *
     * @param next the editor to run over this one's output; never {@code null}
     */
    default ContextEditor andThen(ContextEditor next) {
        java.util.Objects.requireNonNull(next, "next");
        return history -> next.edit(edit(history));
    }
}
