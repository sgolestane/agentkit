package dev.agentkit.core.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An append-only, ordered sequence of {@link Message messages} representing the
 * running history of an agent interaction.
 *
 * <p>This class is a mutable container by design — the agent loop appends turns
 * as the interaction progresses. It is not thread-safe; a single agent loop is
 * expected to own its conversation.
 */
public final class Conversation {

    private final List<Message> messages = new ArrayList<>();

    public Conversation() {
    }

    public Conversation(List<Message> initial) {
        Objects.requireNonNull(initial, "initial");
        initial.forEach(this::append);
    }

    /** Appends a message and returns this conversation for chaining. */
    public Conversation append(Message message) {
        messages.add(Objects.requireNonNull(message, "message"));
        return this;
    }

    /**
     * Replaces the entire history with {@code newMessages} (defensively copied).
     * Used by context strategies to persist an edited/compacted transcript so the
     * transformation is not recomputed on every subsequent turn.
     */
    public Conversation replaceAll(List<Message> newMessages) {
        Objects.requireNonNull(newMessages, "newMessages");
        List<Message> copy = List.copyOf(newMessages);
        messages.clear();
        messages.addAll(copy);
        return this;
    }

    /**
     * Returns an unmodifiable snapshot of the messages. The returned list is a
     * copy: subsequent {@link #append(Message)} calls do not affect it.
     */
    public List<Message> messages() {
        return List.copyOf(messages);
    }

    public int size() {
        return messages.size();
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    /** Returns the most recently appended message, or empty if none. */
    public Optional<Message> last() {
        return messages.isEmpty()
                ? Optional.empty()
                : Optional.of(messages.get(messages.size() - 1));
    }
}
