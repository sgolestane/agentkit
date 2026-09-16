package dev.agentkit.core.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationTest {

    @Test
    void appendIsChainableAndTracksSize() {
        Conversation c = new Conversation()
                .append(Message.user("a"))
                .append(Message.assistant("b"));

        assertThat(c.size()).isEqualTo(2);
        assertThat(c.isEmpty()).isFalse();
        assertThat(c.last()).contains(Message.assistant("b"));
    }

    @Test
    void lastIsEmptyForNewConversation() {
        assertThat(new Conversation().last()).isEmpty();
    }

    @Test
    void messagesSnapshotIsIsolatedFromLaterAppends() {
        Conversation c = new Conversation().append(Message.user("a"));
        List<Message> snapshot = c.messages();

        c.append(Message.user("b"));

        assertThat(snapshot).hasSize(1);
        assertThat(c.messages()).hasSize(2);
    }

    @Test
    void messagesSnapshotIsUnmodifiable() {
        Conversation c = new Conversation().append(Message.user("a"));
        assertThatThrownBy(() -> c.messages().add(Message.user("b")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void replaceAllSwapsHistoryDefensively() {
        Conversation c = new Conversation().append(Message.user("a")).append(Message.user("b"));
        java.util.List<Message> replacement = new ArrayList<>(java.util.List.of(Message.user("x")));
        c.replaceAll(replacement);

        replacement.add(Message.user("y")); // must not leak in
        assertThat(c.size()).isEqualTo(1);
        assertThat(c.messages()).containsExactly(Message.user("x"));
    }

    @Test
    void constructorDefensivelyCopiesInitialList() {
        List<Message> initial = new ArrayList<>();
        initial.add(Message.user("a"));
        Conversation c = new Conversation(initial);

        initial.add(Message.user("b")); // must not affect the conversation

        assertThat(c.size()).isEqualTo(1);
    }
}
