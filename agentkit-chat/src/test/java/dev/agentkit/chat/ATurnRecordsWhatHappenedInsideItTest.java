package dev.agentkit.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.tool.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A turn is the unit: the prompt, everything that happened about it, and how it ended.
 *
 * <h2>Why the trace lives here rather than in a log</h2>
 *
 * <p>The console this replaces kept its trace as a {@code List<String>} built by two
 * witnesses and rendered under the bubble. It was the most useful thing in that UI and
 * nothing could sort it, filter it, total it, or tell a tool call from a model call. Steps
 * are that idea with a shape, and this is the test that says a turn actually carries them —
 * in order, across threads, with what ended it.
 */
class ATurnRecordsWhatHappenedInsideItTest {

    private final ChatStore store = new InMemoryChatStore();

    @Test
    void aTurnCarriesThePromptTheTraceTheViewsAndHowItEnded() {
        Conversation conversation = store.create("acme", "");
        Turn begun = store.begin("acme", conversation.id(), "how many are open?", List.of());

        assertThat(begun.state()).isEqualTo(Turn.State.QUEUED);
        assertThat(begun.ordinal()).isEqualTo(1);
        assertThat(begun.endedAt()).isNull();

        store.addStep("acme", conversation.id(), begun.id(), Step.Kind.MODEL_CALL, "opus",
                Map.of("messages", 2), 420, false);
        store.addStep("acme", conversation.id(), begun.id(), Step.Kind.TOOL_CALL,
                "tickets.inbox", Map.of("limit", 50), 31, false);
        store.show("acme", conversation.id(), begun.id(),
                View.table(View.Column.texts("category", "open"),
                        List.of(List.of("access", 12))));
        Turn ended = store.end("acme", conversation.id(), begun.id(), Turn.State.COMPLETED,
                "Twelve access requests are open.", "", new TokenUsage(120, 40)).orElseThrow();

        assertThat(ended.userText()).isEqualTo("how many are open?");
        assertThat(ended.answer()).isEqualTo("Twelve access requests are open.");
        assertThat(ended.state()).isEqualTo(Turn.State.COMPLETED);
        assertThat(ended.endedAt()).isNotNull();
        assertThat(ended.usage().inputTokens()).isEqualTo(120);
        assertThat(ended.steps()).extracting(Step::kind)
                .containsExactly(Step.Kind.MODEL_CALL, Step.Kind.TOOL_CALL);
        assertThat(ended.stepsOf(Step.Kind.TOOL_CALL)).singleElement()
                .satisfies(step -> {
                    assertThat(step.name()).isEqualTo("tickets.inbox");
                    assertThat(step.millis()).isEqualTo(31);
                });
        assertThat(ended.views()).singleElement()
                .satisfies(view -> assertThat(view.kind()).isEqualTo("table"));
    }

    @Test
    void aTurnThatStoppedToAskIsNotAFailedOne() {
        // The state that makes this an agent's console rather than a chat window. A run
        // waiting on a person has not failed and has not finished, and a UI that collapsed it
        // into either would undo the whole human-in-the-loop story — the person would be shown
        // an error to dismiss instead of a decision to make.
        Conversation conversation = store.create("acme", "");
        Turn turn = store.begin("acme", conversation.id(), "reset her password", List.of());

        Turn parked = store.end("acme", conversation.id(), turn.id(),
                Turn.State.WAITING_FOR_HUMAN, "",
                "Resetting a password is not reversible. Approve?", TokenUsage.ZERO)
                .orElseThrow();

        assertThat(parked.state()).isEqualTo(Turn.State.WAITING_FOR_HUMAN);
        assertThat(parked.state().isTerminal()).isTrue();
        assertThat(parked.detail()).contains("Approve?");
    }

    @Test
    void aTurnCannotEndTwiceAndTheFirstAnswerIsTheOneThatStands() {
        // A cancel racing a completion is a bug in the runtime, and the two orderings must not
        // give different transcripts. The first ending is the true one: it is what the person
        // was actually shown.
        Conversation conversation = store.create("acme", "");
        Turn turn = store.begin("acme", conversation.id(), "go", List.of());
        store.end("acme", conversation.id(), turn.id(), Turn.State.CANCELLED, "",
                "You stopped this.", TokenUsage.ZERO);

        assertThatThrownBy(() -> store.end("acme", conversation.id(), turn.id(),
                Turn.State.COMPLETED, "all done", "", TokenUsage.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already ended as CANCELLED");

        assertThat(store.turn("acme", conversation.id(), turn.id()).orElseThrow().state())
                .isEqualTo(Turn.State.CANCELLED);
    }

    @Test
    void aTurnDoesNotEndInARunningState() {
        Conversation conversation = store.create("acme", "");
        Turn turn = store.begin("acme", conversation.id(), "go", List.of());

        assertThatThrownBy(() -> store.end("acme", conversation.id(), turn.id(),
                Turn.State.RUNNING, "", "", TokenUsage.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal state");
    }

    @Test
    void ordinalsAreAssignedByTheStoreSoTwoMessagesAtOnceCannotCollide() throws Exception {
        // A console with a composer and a background worker can begin two turns at once. If
        // the caller numbered them, both would read the same size and both would be turn 4.
        Conversation conversation = store.create("acme", "");
        int concurrent = 24;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrent);
        List<Turn> begun = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < concurrent; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    begun.add(store.begin("acme", conversation.id(), "go", List.of()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();

        assertThat(begun).extracting(Turn::ordinal).doesNotHaveDuplicates();
        assertThat(begun).extracting(Turn::id).doesNotHaveDuplicates();
        assertThat(store.turns("acme", conversation.id())).hasSize(concurrent);
    }

    @Test
    void stepsAppendedFromManyThreadsAreAllThereAndCarryTheirOrder() throws Exception {
        // The real shape: a worker writing tool calls while an HTTP thread reads the
        // transcript. Read-modify-write from the caller would lose steps under exactly this
        // load, which is why addStep is a store method and not turn.with() plus a save.
        Conversation conversation = store.create("acme", "");
        Turn turn = store.begin("acme", conversation.id(), "go", List.of());
        int writers = 16;
        int each = 20;
        CountDownLatch done = new CountDownLatch(writers);
        for (int w = 0; w < writers; w++) {
            Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < each; i++) {
                        store.addStep("acme", conversation.id(), turn.id(),
                                Step.Kind.TOOL_CALL, "t", Map.of(), 1, false);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();

        Turn read = store.turn("acme", conversation.id(), turn.id()).orElseThrow();
        assertThat(read.steps()).hasSize(writers * each);
        assertThat(read.steps()).extracting(Step::sequence).doesNotHaveDuplicates();
        assertThat(read.steps()).isSortedAccordingTo(
                java.util.Comparator.comparingLong(Step::sequence));
    }

    @Test
    void aRenameIsNotLostToAStepLandingBesideIt() throws Exception {
        // A stress test, said out loud, because the defect it covers is a race and a race has
        // no deterministic spelling. `replace` used to read the conversation BEFORE taking its
        // lock and write a `touched` copy of it back afterwards, so a rename landing between
        // those two points was reverted — the title a person had just typed silently went back
        // to what it was. Both writes now happen under the same lock and re-read inside it.
        Conversation conversation = store.create("acme", "before");
        Turn turn = store.begin("acme", conversation.id(), "go", List.of());
        CountDownLatch done = new CountDownLatch(2);
        Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < 400; i++) {
                    store.addStep("acme", conversation.id(), turn.id(), Step.Kind.NOTE, "n",
                            Map.of(), 0, false);
                }
            } finally {
                done.countDown();
            }
        });
        Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < 400; i++) {
                    store.rename("acme", conversation.id(), "after-" + i);
                }
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();

        assertThat(store.conversation("acme", conversation.id()).orElseThrow().title())
                .isEqualTo("after-399");
    }

    @Test
    void aTurnHandedALiveListIsNotChangedWhenTheCallerKeepsFillingIt() {
        List<String> attachmentIds = new ArrayList<>(List.of("att-1"));
        Turn turn = Turn.beginning("t1", "c1", 1, "go", attachmentIds, java.time.Instant.EPOCH);

        attachmentIds.add("att-2");

        assertThat(turn.attachmentIds()).containsExactly("att-1");
    }
}
