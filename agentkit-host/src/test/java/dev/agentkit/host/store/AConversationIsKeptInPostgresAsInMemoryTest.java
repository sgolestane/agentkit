package dev.agentkit.host.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.chat.Attachment;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Step;
import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.tool.View;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The Postgres chat store gives the answers the in-memory one does — every case here runs against both — and what only
 * a database can: the conversation as it was left after a restart, every row keyed by organization, and steps appended
 * from many threads at once, none lost.
 */
class AConversationIsKeptInPostgresAsInMemoryTest {

    /** A clock a second ahead at every look, so "most recently touched" has one answer. */
    static Clock ticking() {
        AtomicLong seconds = new AtomicLong();
        return new Clock() {
            @Override
            public Instant instant() {
                return Instant.parse("2026-09-18T12:00:00Z").plusSeconds(seconds.incrementAndGet());
            }

            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }
        };
    }

    static Stream<Arguments> stores() {
        return Stream.of(
                Arguments.of("in memory", (Function<Clock, ChatStore>) InMemoryChatStore::new),
                Arguments.of("postgres", (Function<Clock, ChatStore>) clock -> new PostgresChatStore(TestDatabase.get(), clock)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void aConversationIsItsTenantsAlone(String name, Function<Clock, ChatStore> make) {
        ChatStore store = make.apply(ticking());
        String org = TestDatabase.org();
        String priya = org + "/priya@acme.example";
        String dana = org + "/dana@acme.example";
        Conversation mine = store.create(priya, "Mine", new Conversation.Pin("helpdesk", "abc123"));

        assertThat(store.conversation(priya, mine.id())).contains(mine);
        assertThat(store.conversation(dana, mine.id())).isEmpty();
        assertThat(store.conversations(dana)).isEmpty();
        assertThat(store.rename(dana, mine.id(), "Theirs")).isEmpty();
        assertThat(store.delete(dana, mine.id())).isFalse();
        assertThat(store.turns(dana, mine.id())).isEmpty();
        assertThatThrownBy(() -> store.begin(dana, mine.id(), "hi", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.conversation(priya, mine.id()).orElseThrow().agent())
                .isEqualTo(new Conversation.Pin("helpdesk", "abc123"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void aTurnIsNumberedTracedAndEndedOnce(String name, Function<Clock, ChatStore> make) {
        ChatStore store = make.apply(ticking());
        String tenant = TestDatabase.org() + "/priya@acme.example";
        Conversation older = store.create(tenant, "Older");
        Conversation conversation = store.create(tenant, "Talk");

        Turn first = store.begin(tenant, conversation.id(), "hello", List.of());
        Turn second = store.begin(tenant, older.id(), "and you", List.of());
        store.markRunning(tenant, older.id(), second.id());
        store.addStep(tenant, older.id(), second.id(), Step.Kind.NOTE, "plan", Map.of("steps", List.of("a", "b")), 5, false);
        store.addStep(tenant, older.id(), second.id(), Step.Kind.TOOL_CALL, "lookup", Map.of("tool", "lookup"), 7, true);
        store.show(tenant, older.id(), second.id(), View.markdown("**Plan**"));
        Turn ended = store.end(tenant, older.id(), second.id(), Turn.State.COMPLETED, "done", null,
                new TokenUsage(10, 5)).orElseThrow();

        assertThat(first.ordinal()).isEqualTo(1);
        assertThat(store.begin(tenant, conversation.id(), "again", List.of()).ordinal()).isEqualTo(2);
        assertThat(store.turns(tenant, older.id())).singleElement().isEqualTo(ended);
        assertThat(ended.state()).isEqualTo(Turn.State.COMPLETED);
        assertThat(ended.steps()).extracting(Step::name).containsExactly("plan", "lookup");
        assertThat(ended.steps().get(0).sequence()).isLessThan(ended.steps().get(1).sequence());
        assertThat(ended.steps().get(0).detail()).containsEntry("steps", List.of("a", "b"));
        assertThat(ended.steps().get(1).failed()).isTrue();
        assertThat(ended.views()).hasSize(1);
        assertThatThrownBy(() -> store.end(tenant, older.id(), second.id(), Turn.State.FAILED, "no", "x", null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.turn(tenant, older.id(), second.id())).contains(ended);
        // The conversation touched last comes first: the one "again" was said in.
        assertThat(store.conversations(tenant)).extracting(Conversation::id).containsExactly(conversation.id(), older.id());
        assertThat(store.rename(tenant, conversation.id(), "Renamed").orElseThrow().title()).isEqualTo("Renamed");
        assertThat(store.conversations(tenant)).extracting(Conversation::title).containsExactly("Renamed", "Older");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void anAttachmentIsHeldAndGoesWithItsConversation(String name, Function<Clock, ChatStore> make) {
        ChatStore store = make.apply(ticking());
        String tenant = TestDatabase.org() + "/priya@acme.example";
        String other = TestDatabase.org() + "/priya@acme.example";
        Conversation conversation = store.create(tenant, "Files");
        Attachment first = store.attach(tenant, conversation.id(), "a.txt", "text/plain", "one".getBytes());
        Attachment second = store.attach(tenant, conversation.id(), "b.bin", "application/octet-stream", new byte[] {0, 1, 2});
        Turn turn = store.begin(tenant, conversation.id(), "see files", List.of(first.id(), second.id()));

        assertThat(store.attachments(tenant, conversation.id())).containsExactly(first, second);
        assertThat(store.content(tenant, second.id())).hasValueSatisfying(b -> assertThat(b).containsExactly(0, 1, 2));
        assertThat(store.attachment(other, first.id())).isEmpty();
        assertThat(store.content(other, first.id())).isEmpty();
        assertThat(turn.attachmentIds()).containsExactly(first.id(), second.id());

        assertThat(store.delete(tenant, conversation.id())).isTrue();
        assertThat(store.conversation(tenant, conversation.id())).isEmpty();
        assertThat(store.turns(tenant, conversation.id())).isEmpty();
        assertThat(store.attachment(tenant, first.id())).isEmpty();
        assertThat(store.content(tenant, second.id())).isEmpty();
    }

    @Test
    void aRestartFindsEverythingAsItWasLeftAndEveryRowNamesItsOrganization() throws Exception {
        Database database = TestDatabase.get();
        String org = TestDatabase.org();
        String tenant = org + "/priya@acme.example";
        PostgresChatStore before = new PostgresChatStore(database, ticking());
        Conversation conversation = before.create(tenant, "Kept", new Conversation.Pin("onboarding", "v1"));
        Turn turn = before.begin(tenant, conversation.id(), "hello", List.of());
        before.attach(tenant, conversation.id(), "a.txt", "text/plain", "x".getBytes());
        Turn ended = before.end(tenant, conversation.id(), turn.id(), Turn.State.COMPLETED, "hi", null, null)
                .orElseThrow();

        PostgresChatStore after = new PostgresChatStore(database);
        assertThat(after.conversations(tenant)).singleElement().satisfies(c -> {
            assertThat(c.title()).isEqualTo("Kept");
            assertThat(c.agent()).isEqualTo(new Conversation.Pin("onboarding", "v1"));
        });
        assertThat(after.turns(tenant, conversation.id())).containsExactly(ended);
        assertThat(after.begin(tenant, conversation.id(), "again", List.of()).ordinal()).isEqualTo(2);

        for (String table : List.of("chat_conversation", "chat_turn", "chat_attachment")) {
            List<String> orgs = database.read(connection -> {
                try (PreparedStatement select = connection.prepareStatement(
                        "select distinct org_id from " + table + " where tenant_id = ?")) {
                    select.setString(1, tenant);
                    try (ResultSet rows = select.executeQuery()) {
                        List<String> found = new ArrayList<>();
                        while (rows.next()) {
                            found.add(rows.getString(1));
                        }
                        return found;
                    }
                }
            });
            assertThat(orgs).as(table).containsExactly(org);
        }
    }

    @Test
    void stepsAppendedFromManyThreadsAtOnceAreAllKept() throws Exception {
        PostgresChatStore store = new PostgresChatStore(TestDatabase.get());
        String tenant = TestDatabase.org() + "/priya@acme.example";
        Conversation conversation = store.create(tenant, "Busy");
        Turn turn = store.begin(tenant, conversation.id(), "go", List.of());

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> writes = new ArrayList<>();
        for (int thread = 0; thread < 8; thread++) {
            int t = thread;
            writes.add(pool.submit(() -> {
                for (int i = 0; i < 20; i++) {
                    store.addStep(tenant, conversation.id(), turn.id(), Step.Kind.NOTE, t + "-" + i, Map.of(), 0, false);
                }
                store.rename(tenant, conversation.id(), "Renamed by " + t);
            }));
        }
        for (Future<?> write : writes) {
            write.get();
        }
        pool.shutdown();

        List<Step> steps = store.turn(tenant, conversation.id(), turn.id()).orElseThrow().steps();
        assertThat(steps).hasSize(160);
        assertThat(steps).extracting(Step::sequence).isSorted().doesNotHaveDuplicates();
        assertThat(store.conversation(tenant, conversation.id()).orElseThrow().title()).startsWith("Renamed by ");
    }
}
