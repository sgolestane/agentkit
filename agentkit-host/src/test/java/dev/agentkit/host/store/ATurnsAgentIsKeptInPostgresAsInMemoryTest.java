package dev.agentkit.host.store;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.chat.store.InMemoryChatStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/** In a conversation pinned to no agent, which agent each turn went to is kept, in Postgres as in memory. */
class ATurnsAgentIsKeptInPostgresAsInMemoryTest {

    @Test
    void inMemory() {
        check(new InMemoryChatStore(), "acme/sam@acme.example");
    }

    @Test
    void inPostgres() {
        check(new PostgresChatStore(TestDatabase.get()), TestDatabase.org() + "/sam@acme.example");
    }

    private static void check(ChatStore store, String tenant) {
        Conversation conversation = store.create(tenant, "routed", null);
        Conversation.Pin chosen = new Conversation.Pin("security-desk", "v1");

        Turn routed = store.begin(tenant, conversation.id(), "My laptop", List.of());
        store.route(tenant, conversation.id(), routed.id(), new Conversation.Pin("helpdesk", "v1"));
        Turn picked = store.begin(tenant, conversation.id(), "Look up Dana", List.of(), chosen);

        assertThat(store.conversation(tenant, conversation.id()).orElseThrow().agent()).isNull();
        assertThat(store.turn(tenant, conversation.id(), routed.id()).orElseThrow().agent())
                .isEqualTo(new Conversation.Pin("helpdesk", "v1"));
        assertThat(store.turn(tenant, conversation.id(), picked.id()).orElseThrow().agent()).isEqualTo(chosen);
        assertThat(store.turns(tenant, conversation.id())).extracting(Turn::agent)
                .containsExactly(new Conversation.Pin("helpdesk", "v1"), chosen);
    }
}
