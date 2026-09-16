package dev.agentkit.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.chat.store.InMemoryChatStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every read is scoped, including the ones whose id could not be guessed.
 *
 * <h2>Why this is a test and not a comment</h2>
 *
 * <p>The demos run one tenant, so nothing exercises the second one and every one of these
 * filters could be deleted with the rest of the suite still green. That is exactly the
 * condition under which scoping rots: the first read written without it works, so the next
 * one is written the same way, and the day a second tenant exists the audit is every method
 * in the store.
 *
 * <p>The lookups by minted id are the ones worth arguing about. {@code conv-7} is not
 * guessable, so scoping them looks like ceremony. It is not: unguessable is not
 * unauthorised, and a store where some reads check and others do not is one where the next
 * reader cannot tell which kind they are looking at. {@code content} is the sharpest case —
 * the bytes are the thing worth stealing, and it goes through {@code attachment} rather than
 * spelling the check a second time.
 */
class AConversationIsScopedByTenantTest {

    private final ChatStore store = new InMemoryChatStore();

    @Test
    void oneTenantsConversationIsInvisibleToAnother() {
        Conversation mine = store.create("acme", "mine");
        store.begin("acme", mine.id(), "hello", List.of());

        assertThat(store.conversation("globex", mine.id())).isEmpty();
        assertThat(store.conversations("globex")).isEmpty();
        assertThat(store.turns("globex", mine.id())).isEmpty();
        assertThat(store.rename("globex", mine.id(), "theirs")).isEmpty();
        assertThat(store.delete("globex", mine.id())).isFalse();

        // …and none of that disturbed the owner.
        assertThat(store.conversation("acme", mine.id())).isPresent();
        assertThat(store.conversations("acme")).hasSize(1);
        assertThat(store.turns("acme", mine.id())).hasSize(1);
    }

    @Test
    void aTurnCannotBeReachedThroughTheWrongConversationEither() {
        Conversation one = store.create("acme", "one");
        Conversation two = store.create("acme", "two");
        Turn turn = store.begin("acme", one.id(), "hello", List.of());

        // Same tenant, wrong conversation. The id is real and the answer is still no, because
        // a turn belongs to a conversation and "which turn is this" has to mean one thing.
        assertThat(store.turn("acme", two.id(), turn.id())).isEmpty();
        assertThat(store.turn("acme", one.id(), turn.id())).isPresent();
    }

    @Test
    void theBytesOfAnAttachmentAreScopedTheSameWayItsHandleIs() {
        Conversation mine = store.create("acme", "mine");
        Attachment attached = store.attach("acme", mine.id(), "log.txt", "text/plain",
                "secret".getBytes(StandardCharsets.UTF_8));

        assertThat(store.attachment("globex", attached.id())).isEmpty();
        assertThat(store.content("globex", attached.id())).isEmpty();
        assertThat(store.attachments("globex", mine.id())).isEmpty();
        assertThat(store.content("acme", attached.id()))
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .contains("secret");
    }

    @Test
    void aWriteIntoAConversationThatIsNotYoursIsRefusedRatherThanIgnored() {
        Conversation mine = store.create("acme", "mine");

        // Refused loudly, unlike the reads: a caller that asked to write somewhere and got
        // silence would carry on believing it had. There is nothing to hand back that means
        // "this did not happen", so it throws.
        assertThatThrownBy(() -> store.begin("globex", mine.id(), "hello", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No such conversation");
        assertThatThrownBy(() -> store.attach("globex", mine.id(), "x", "text/plain",
                new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No such conversation");
    }

    @Test
    void anAppendIntoSomebodyElsesTurnChangesNothingAndSaysSo() {
        Conversation mine = store.create("acme", "mine");
        Turn turn = store.begin("acme", mine.id(), "hello", List.of());

        assertThat(store.addStep("globex", mine.id(), turn.id(), Step.Kind.NOTE, "n",
                java.util.Map.of(), 0, false)).isEmpty();
        assertThat(store.end("globex", mine.id(), turn.id(), Turn.State.COMPLETED, "hi", "",
                null)).isEmpty();

        assertThat(store.turn("acme", mine.id(), turn.id()))
                .get()
                .satisfies(unchanged -> {
                    assertThat(unchanged.steps()).isEmpty();
                    assertThat(unchanged.state()).isEqualTo(Turn.State.QUEUED);
                });
    }

    @Test
    void deletingAConversationTakesItsTurnsAndItsFilesWithIt() {
        Conversation mine = store.create("acme", "mine");
        Conversation other = store.create("acme", "other");
        store.begin("acme", mine.id(), "hello", List.of());
        Attachment attached = store.attach("acme", mine.id(), "log.txt", "text/plain",
                new byte[] {1});
        Attachment survivor = store.attach("acme", other.id(), "keep.txt", "text/plain",
                new byte[] {2});

        assertThat(store.delete("acme", mine.id())).isTrue();

        assertThat(store.conversation("acme", mine.id())).isEmpty();
        assertThat(store.turns("acme", mine.id())).isEmpty();
        assertThat(store.attachment("acme", attached.id())).isEmpty();
        assertThat(store.content("acme", attached.id())).isEmpty();
        // The other conversation's upload is untouched, which is the half a sweep gets wrong.
        assertThat(store.attachment("acme", survivor.id())).isPresent();
    }

    @Test
    void conversationsComeBackMostRecentlyTouchedFirst() {
        Conversation first = store.create("acme", "first");
        Conversation second = store.create("acme", "second");
        store.begin("acme", first.id(), "something happened here", List.of());

        assertThat(store.conversations("acme"))
                .extracting(Conversation::id)
                .containsExactly(first.id(), second.id());
    }
}
