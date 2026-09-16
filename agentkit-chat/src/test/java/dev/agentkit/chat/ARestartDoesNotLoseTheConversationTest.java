package dev.agentkit.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.chat.store.FileChatStore;
import dev.agentkit.chat.store.Ids;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.tool.View;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The transcript survives the process, which is the only reason the file store exists.
 *
 * <h2>Why this matters more than it looks</h2>
 *
 * <p>The knowledge base and the lesson book are both built on the idea that work accumulates:
 * a person corrects the agent once and the correction holds. A console that forgets the
 * conversation on restart makes that a claim nobody can check — the answer is still in the
 * memory store, and the reasoning that produced it is gone.
 *
 * <p>The unreadable-file case is the one that decides whether this is usable. A console is
 * stopped with Ctrl-C in the middle of a run more often than any other way, and the failure
 * that matters is not a lost turn but a half-written one that will not parse — which, if
 * loading were strict, would take the whole conversation with it on the next boot.
 */
class ARestartDoesNotLoseTheConversationTest {

    @Test
    void everythingWrittenIsThereAfterARestart(@TempDir Path directory) {
        ChatStore before = new FileChatStore(directory);
        Conversation conversation = before.create("acme", "the queue");
        Turn turn = before.begin("acme", conversation.id(), "how many are open?", List.of());
        before.addStep("acme", conversation.id(), turn.id(), Step.Kind.MODEL_CALL, "opus",
                Map.of("messages", 2), 420, false);
        before.addStep("acme", conversation.id(), turn.id(), Step.Kind.TOOL_CALL,
                "tickets.inbox", Map.of("limit", 50), 31, false);
        before.show("acme", conversation.id(), turn.id(),
                View.table(View.Column.texts("category", "open"),
                        List.of(List.of("access", 12))));
        before.end("acme", conversation.id(), turn.id(), Turn.State.COMPLETED,
                "Twelve access requests are open.", "", new TokenUsage(120, 40));
        Attachment attached = before.attach("acme", conversation.id(), "tickets.csv",
                "text/csv", "key,summary\nINC-1,laptop".getBytes(StandardCharsets.UTF_8));

        ChatStore after = new FileChatStore(directory);

        assertThat(after.conversations("acme")).extracting(Conversation::title)
                .containsExactly("the queue");
        Turn restored = after.turns("acme", conversation.id()).get(0);
        assertThat(restored.userText()).isEqualTo("how many are open?");
        assertThat(restored.answer()).isEqualTo("Twelve access requests are open.");
        assertThat(restored.state()).isEqualTo(Turn.State.COMPLETED);
        assertThat(restored.usage().inputTokens()).isEqualTo(120);
        assertThat(restored.steps()).extracting(Step::name)
                .containsExactly("opus", "tickets.inbox");
        assertThat(restored.steps()).isSortedAccordingTo(
                java.util.Comparator.comparingLong(Step::sequence));
        // The view survives whole, values and all — a table that came back as an empty shell
        // would be a transcript that cannot be re-read.
        assertThat(restored.views()).singleElement().satisfies(view -> {
            assertThat(view.kind()).isEqualTo("table");
            assertThat(view.data().get("rows")).isEqualTo(List.of(List.of("access", 12)));
        });
        assertThat(after.content("acme", attached.id()))
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .contains("key,summary\nINC-1,laptop");
        assertThat(after.attachments("acme", conversation.id()))
                .extracting(Attachment::name).containsExactly("tickets.csv");
    }

    @Test
    void aRunningTurnIsStillRunningAfterARestart(@TempDir Path directory) {
        // The console was killed mid-run. The turn must come back as it was rather than as
        // completed, so whatever resumes it — or reports it — knows it never finished.
        ChatStore before = new FileChatStore(directory);
        Conversation conversation = before.create("acme", "");
        Turn turn = before.begin("acme", conversation.id(), "go", List.of());
        before.addStep("acme", conversation.id(), turn.id(), Step.Kind.TOOL_CALL, "t",
                Map.of(), 1, false);

        Turn restored = new FileChatStore(directory)
                .turn("acme", conversation.id(), turn.id()).orElseThrow();

        assertThat(restored.state()).isEqualTo(Turn.State.QUEUED);
        assertThat(restored.endedAt()).isNull();
        assertThat(restored.steps()).hasSize(1);
    }

    @Test
    void aRestartDoesNotMintAnIdThatAlreadyExists(@TempDir Path directory)
            throws IOException {
        // The counter behind `conv-1` is a process-local AtomicLong starting at zero, and
        // everything on disk was counted in a previous process. Before this, the first
        // conversation created after a restart was minted `conv-1`, replaced the loaded one in
        // the map, and then the write-through overwrote its file. Not a duplicate — the older
        // conversation, its turns and its trace were gone, and nothing said so.
        //
        // Aimed at the id the store is about to mint, rather than hoping for a clash. The
        // counter is shared across this JVM, so a test that merely creates two conversations
        // and restarts proves nothing: by the time it runs the counter is already past
        // anything on disk. The file has to be planted at exactly the next number.
        //
        // Probed AFTER the setup, not before — the first spelling of this asked for the number
        // first and then spent it on the very conversation it was about to move, so the plant
        // landed one behind and the collision never happened. The test passed with the fix
        // removed, which is the only reason it was found.
        ChatStore before = new FileChatStore(directory);
        Conversation planted = before.create("acme", "do not lose me");
        before.begin("acme", planted.id(), "nor this", List.of());
        long counter = Long.parseLong(Ids.next("probe").substring("probe-".length()));
        String willBeMinted = "conv-" + (counter + 1);

        Path conversations = directory.resolve("conversations");
        String moved = Files.readString(conversations.resolve(planted.id() + ".json"))
                .replace(planted.id(), willBeMinted);
        Files.delete(conversations.resolve(planted.id() + ".json"));
        Files.writeString(conversations.resolve(willBeMinted + ".json"), moved);

        ChatStore after = new FileChatStore(directory);
        Conversation fresh = after.create("acme", "after the restart");

        assertThat(fresh.id()).isNotEqualTo(willBeMinted);
        assertThat(after.conversations("acme")).extracting(Conversation::title)
                .containsExactlyInAnyOrder("do not lose me", "after the restart");
        assertThat(after.turns("acme", willBeMinted)).extracting(Turn::userText)
                .containsExactly("nor this");
    }

    @Test
    void stepSequencesDoNotRepeatAfterARestart(@TempDir Path directory) {
        // The same counter problem one level down: the trace is ordered by sequence, so a
        // restart that began again at 1 would interleave a resumed turn's new steps with its
        // old ones and the trace would read in the wrong order.
        ChatStore before = new FileChatStore(directory);
        Conversation conversation = before.create("acme", "");
        Turn turn = before.begin("acme", conversation.id(), "go", List.of());
        for (int i = 0; i < 5; i++) {
            before.addStep("acme", conversation.id(), turn.id(), Step.Kind.TOOL_CALL,
                    "before-" + i, Map.of(), 1, false);
        }

        ChatStore after = new FileChatStore(directory);
        for (int i = 0; i < 5; i++) {
            after.addStep("acme", conversation.id(), turn.id(), Step.Kind.TOOL_CALL,
                    "after-" + i, Map.of(), 1, false);
        }

        Turn restored = after.turn("acme", conversation.id(), turn.id()).orElseThrow();
        assertThat(restored.steps()).hasSize(10);
        assertThat(restored.steps()).extracting(Step::sequence).doesNotHaveDuplicates();
        assertThat(restored.steps()).isSortedAccordingTo(
                java.util.Comparator.comparingLong(Step::sequence));
        assertThat(restored.steps()).extracting(Step::name)
                .containsExactly("before-0", "before-1", "before-2", "before-3", "before-4",
                        "after-0", "after-1", "after-2", "after-3", "after-4");
    }

    @Test
    void oneUnreadableFileCostsOneConversationRatherThanAllOfThem(@TempDir Path directory)
            throws IOException {
        ChatStore before = new FileChatStore(directory);
        Conversation good = before.create("acme", "readable");
        before.begin("acme", good.id(), "hello", List.of());
        Conversation broken = before.create("acme", "half-written");

        // What a kill -9 during a write would have left, had the write not been atomic.
        Files.writeString(directory.resolve("conversations").resolve(broken.id() + ".json"),
                "{\"conversation\":{\"id\":\"conv-9\",\"tenan");

        ChatStore after = new FileChatStore(directory);

        assertThat(after.conversations("acme")).extracting(Conversation::title)
                .containsExactly("readable");
        assertThat(after.turns("acme", good.id())).hasSize(1);
    }

    @Test
    void aWriteIsMovedIntoPlaceRatherThanMadeInPlace(@TempDir Path directory)
            throws IOException {
        // The property behind the test above: at no point is the real file a partial one. A
        // temporary is written and then moved, so a reader either sees the previous whole
        // version or the new whole version.
        ChatStore store = new FileChatStore(directory);
        Conversation conversation = store.create("acme", "x");
        for (int i = 0; i < 40; i++) {
            store.begin("acme", conversation.id(), "turn " + i, List.of());
        }

        try (var files = Files.list(directory.resolve("conversations"))) {
            // No temporaries left behind, and exactly one file per conversation.
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactly(conversation.id() + ".json");
        }
    }

    @Test
    void anIdThatWouldNameAnotherFileIsRefusedRatherThanResolved(@TempDir Path directory) {
        // Ids this module mints could not do this. Ids arriving from an HTTP path parameter or
        // a tool argument can, and `conversations/../../etc/passwd.json` is a read of an
        // arbitrary file. Checked where a path is built, which is the only place it is true.
        assertThat(Ids.isSafe("conv-12")).isTrue();
        assertThat(Ids.isSafe("../../etc/passwd")).isFalse();
        assertThat(Ids.isSafe("..")).isFalse();
        assertThat(Ids.isSafe("a.b")).isFalse();
        assertThat(Ids.isSafe("")).isFalse();
        assertThat(Ids.isSafe(null)).isFalse();

        assertThatThrownBy(() -> Ids.requireSafe("../../etc/passwd", "A conversation id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names a file");

        // And a lookup with one is simply not found, because the store never gets that far.
        ChatStore store = new FileChatStore(directory);
        assertThat(store.conversation("acme", "../../etc/passwd")).isEmpty();
        assertThat(store.turns("acme", "../../etc/passwd")).isEmpty();
    }

    @Test
    void aDeletedConversationLeavesNothingOnDisk(@TempDir Path directory) throws IOException {
        ChatStore store = new FileChatStore(directory);
        Conversation going = store.create("acme", "going");
        Conversation staying = store.create("acme", "staying");
        store.begin("acme", going.id(), "hello", List.of());
        store.attach("acme", going.id(), "log.txt", "text/plain", new byte[] {1});
        Attachment survivor = store.attach("acme", staying.id(), "keep.txt", "text/plain",
                new byte[] {2});

        assertThat(store.delete("acme", going.id())).isTrue();

        try (var files = Files.list(directory.resolve("conversations"))) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactly(staying.id() + ".json");
        }
        try (var files = Files.list(directory.resolve("attachments"))) {
            // The sweep asks "is this id still known?", not "does this tenant own it?" — the
            // owner has just been deleted, so the scoped question would answer "no" for every
            // file and take the other conversation's upload with it.
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder(survivor.id() + ".json", survivor.id() + ".bin");
        }
        assertThat(new FileChatStore(directory).attachment("acme", survivor.id())).isPresent();
    }

    @Test
    void aFilenameFromTheUploaderIsShownAsANameRatherThanAPath(@TempDir Path directory) {
        ChatStore store = new FileChatStore(directory);
        Conversation conversation = store.create("acme", "");

        assertThat(store.attach("acme", conversation.id(), "../../etc/passwd", "text/plain",
                new byte[] {1}).name()).isEqualTo("passwd");
        assertThat(store.attach("acme", conversation.id(), "C:\\Users\\me\\notes.txt",
                "text/plain", new byte[] {1}).name()).isEqualTo("notes.txt");
        assertThat(store.attach("acme", conversation.id(), "..", "text/plain", new byte[] {1})
                .name()).isEqualTo("attachment");
        assertThat(store.attach("acme", conversation.id(), "   ", "text/plain", new byte[] {1})
                .name()).isEqualTo("attachment");
    }
}
