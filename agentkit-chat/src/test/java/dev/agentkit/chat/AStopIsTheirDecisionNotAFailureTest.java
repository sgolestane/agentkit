package dev.agentkit.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.tool.SimpleToolRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pressing Stop is something a person did, not something that went wrong.
 *
 * <h2>Why the distinction is worth a test</h2>
 *
 * <p>It would be easy, and wrong, to record a stopped turn as {@code FAILED}. The transcript is
 * the record of what happened, and a transcript that calls a person's decision an error is wrong
 * about who did what — somebody reading it later sees a console that broke rather than an
 * operator who changed their mind. {@code ChatRuntime} says so in a comment; this is the test.
 *
 * <p>The other half is the money. A person stops a conversation with three messages queued
 * behind the running one, and the two behind it must not each spend a model call on their way
 * to being cancelled. Stopping something that has not begun is the whole point of being able to
 * stop it.
 */
class AStopIsTheirDecisionNotAFailureTest {

    private final ChatStore store = new InMemoryChatStore();
    private final ChatEvents events = new ChatEvents();

    private ChatRuntime runtime;

    @AfterEach
    void stop() {
        if (runtime != null) {
            runtime.close();
        }
    }

    /** A model that blocks until released, counting how many times it was reached. */
    private static final class HeldModel implements LlmClient {
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmResponse generate(LlmRequest request) {
            calls.incrementAndGet();
            reached.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException stopped) {
                // Thrown, not swallowed, because that is what an interrupted HTTP client
                // does. The first draft of this restored the flag and returned a cheerful
                // answer, and the turn came back COMPLETED — correctly: ChatRuntime keeps a
                // real answer that landed as the stop arrived, on the grounds that throwing
                // away work it actually did would be the console lying. A fixture that
                // manufactures that race on every cancel is testing the wrong branch.
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the model call was interrupted", stopped);
            }
            return LlmResponse.of(Message.assistant("Done."), LlmStopReason.END_TURN,
                    TokenUsage.ZERO);
        }
    }

    private HeldModel startWith() {
        HeldModel model = new HeldModel();
        runtime = new ChatRuntime(store, events, session -> session
                .agent(model, new SimpleToolRegistry(),
                        AgentConfig.builder("m").maxSteps(2).build())
                .build());
        return model;
    }

    @Test
    void aStoppedTurnIsRecordedAsTheirsRatherThanAsAnError() throws Exception {
        HeldModel model = startWith();
        Conversation conversation = store.create("acme", "");
        Turn turn = runtime.say("acme", conversation.id(), "do the thing", List.of());
        assertThat(model.reached.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(runtime.cancel("acme", conversation.id())).isTrue();
        Turn ended = await(conversation.id(), turn.id());

        assertThat(ended.state()).isEqualTo(Turn.State.CANCELLED);
        assertThat(ended.state().isTerminal()).isTrue();
        // The sentence a person reads. "That failed" would be the console blaming itself for
        // something they chose.
        assertThat(ended.detail()).isEqualTo("You stopped this.");
        assertThat(ended.answer()).isEmpty();
    }

    @Test
    void theTurnsQueuedBehindItSpendNothingOnTheirWayToBeingStopped() throws Exception {
        HeldModel model = startWith();
        Conversation conversation = store.create("acme", "");
        Turn first = runtime.say("acme", conversation.id(), "one", List.of());
        Turn second = runtime.say("acme", conversation.id(), "two", List.of());
        Turn third = runtime.say("acme", conversation.id(), "three", List.of());
        assertThat(model.reached.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(second.state()).isEqualTo(Turn.State.QUEUED);

        runtime.cancel("acme", conversation.id());
        for (Turn queued : List.of(first, second, third)) {
            await(conversation.id(), queued.id());
        }

        // The whole point of stopping something that has not begun: a cancelled turn that
        // still made its model call has cost money for an answer nobody will read.
        assertThat(model.calls.get()).isEqualTo(1);
        assertThat(await(conversation.id(), second.id()).detail())
                .isEqualTo("You stopped this before it started.");
        assertThat(await(conversation.id(), third.id()).state())
                .isEqualTo(Turn.State.CANCELLED);
    }

    @Test
    void aStopNeverLetsTheNextTurnSlipThroughAndSpendACall() throws Exception {
        // #382, and the reason this test drags the cancel walk out on purpose.
        //
        // The bug was a RACE. Stopping the running turn interrupted its worker; the worker's
        // finally ended that turn and the per-conversation executor picked the next one up
        // immediately — while the cancel walk was still on its way to it. By the time the walk
        // arrived, that turn had a worker thread, so the branch taken was `worker.interrupt()`
        // — and an interrupt does not unwind a thread that is not in an interruptible wait.
        // The turn's first act is a model call. It was cancelled correctly and it had already
        // spent one, about one time in three on CI.
        //
        // ONE TIME IN THREE IS NOT A TEST. Repeating the plain scenario twenty times did not
        // fail once against the broken code on this machine — measured — because unwinding a
        // worker and starting the next turn takes longer than the walk's next iteration, and
        // only a loaded machine reorders them. A regression guard that only fires on somebody
        // else's hardware is not a guard.
        //
        // So the walk is slowed instead of the race being waited for: a store whose turn list
        // pauses on every element. That is enough for the executor to win every time.
        //
        // Verified by restoring the original loop — a single pass, iterating the store's list
        // lazily and cancelling as it went. Against that, this fails with THREE model calls
        // rather than one: both queued turns started and called the model before the walk
        // reached them. Against the fix it passes, and passes for a reason that does not
        // depend on who wins, because every turn is marked before any is interrupted.
        //
        // The slowing has to be on the LAZY walk to mean anything. An intermediate version of
        // this fix materialised the list first and then looped, which by itself closed the
        // window on this machine and made the guard pass against code that still had the bug.
        Pausing pausing = new Pausing(Duration.ofMillis(60));
        HeldModel model = new HeldModel();
        runtime = new ChatRuntime(pausing, events, session -> session
                .agent(model, new SimpleToolRegistry(),
                        AgentConfig.builder("m").maxSteps(2).build())
                .build());
        Conversation conversation = pausing.create("acme", "");
        List<Turn> turns = List.of(
                runtime.say("acme", conversation.id(), "one", List.of()),
                runtime.say("acme", conversation.id(), "two", List.of()),
                runtime.say("acme", conversation.id(), "three", List.of()));
        assertThat(model.reached.await(10, TimeUnit.SECONDS)).isTrue();

        runtime.cancel("acme", conversation.id());
        for (Turn queued : turns) {
            awaitIn(pausing, conversation.id(), queued.id());
        }

        assertThat(model.calls.get())
                .as("a turn that was cancelled before it started still called the model")
                .isEqualTo(1);
    }

    /**
     * A store whose list of turns is slow to walk.
     *
     * <p>A subclass rather than a delegate, so everything except the one method is the real
     * store's. It exists to widen one window: only the walk that {@code cancel} performs is
     * stretched, and stretching it is what turns a timing bug into a repeatable one.
     */
    private static final class Pausing extends InMemoryChatStore {
        private final Duration perElement;

        Pausing(Duration perElement) {
            this.perElement = perElement;
        }

        @Override
        public List<Turn> turns(String tenantId, String conversationId) {
            List<Turn> real = super.turns(tenantId, conversationId);
            return new java.util.AbstractList<>() {
                @Override
                public Turn get(int index) {
                    try {
                        Thread.sleep(perElement.toMillis());
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                    }
                    return real.get(index);
                }

                @Override
                public int size() {
                    return real.size();
                }
            };
        }
    }

    @Test
    void stoppingOneConversationLeavesAnotherAlone() throws Exception {
        HeldModel model = startWith();
        Conversation busy = store.create("acme", "");
        Conversation other = store.create("acme", "");
        Turn theirs = runtime.say("acme", busy.id(), "go", List.of());
        assertThat(model.reached.await(10, TimeUnit.SECONDS)).isTrue();

        // Nothing is running there, so there is nothing to stop — and saying so is the answer,
        // not an error.
        assertThat(runtime.cancel("acme", other.id())).isFalse();
        assertThat(runtime.cancel("acme", busy.id())).isTrue();

        assertThat(await(busy.id(), theirs.id()).state()).isEqualTo(Turn.State.CANCELLED);
        assertThat(store.turns("acme", other.id())).isEmpty();
    }

    @Test
    void aTurnParkedOnAQuestionIsStoppedRatherThanLeftWaitingForever() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        runtime = new ChatRuntime(store, events, session -> session
                .agent(proposing(), gated(ran),
                        AgentConfig.builder("m").maxSteps(4).build())
                .toolGate(ToolGates.requireApproval(invocation -> true, session.approver()))
                .build());
        Conversation conversation = store.create("acme", "");
        Turn turn = runtime.say("acme", conversation.id(), "reset it", List.of());
        awaitAQuestion();

        runtime.cancel("acme", conversation.id());
        Turn ended = await(conversation.id(), turn.id());

        // A run blocked on an approval is blocked on a future nobody will now complete. The
        // interrupt abandons the question as well as the run — a stop that left the thread
        // parked would leak one per undecided question, and the person would see a turn that
        // says it stopped and a console that never came back.
        assertThat(ended.state()).isEqualTo(Turn.State.CANCELLED);
        assertThat(ran.get()).as("the gated call must not have run").isZero();
        assertThat(runtime.pending("acme")).isEmpty();
    }

    @Test
    void stoppingSomethingAlreadyFinishedIsNotAnErrorAndDoesNotUndoIt() throws Exception {
        HeldModel model = startWith();
        Conversation conversation = store.create("acme", "");
        Turn turn = runtime.say("acme", conversation.id(), "go", List.of());
        assertThat(model.reached.await(10, TimeUnit.SECONDS)).isTrue();
        model.release.countDown();
        Turn ended = await(conversation.id(), turn.id());
        assertThat(ended.state()).isEqualTo(Turn.State.COMPLETED);

        assertThat(runtime.cancel("acme", conversation.id())).isFalse();

        // The answer exists and the person is about to be shown it. Throwing it away because a
        // stop arrived a moment later would be the console lying about work it actually did.
        Turn afterwards = store.turn("acme", conversation.id(), turn.id()).orElseThrow();
        assertThat(afterwards.state()).isEqualTo(Turn.State.COMPLETED);
        assertThat(afterwards.answer()).isEqualTo("Done.");
    }

    // --- fixtures ---------------------------------------------------------------------

    private static LlmClient proposing() {
        return new LlmClient() {
            private int calls;

            @Override
            public LlmResponse generate(LlmRequest request) {
                if (calls++ == 0) {
                    return LlmResponse.of(Message.of(dev.agentkit.core.message.Role.ASSISTANT,
                                    dev.agentkit.core.message.ProposedCall.of("c1",
                                            "identity.reset",
                                            java.util.Map.of("user", "alice"))),
                            LlmStopReason.TOOL_USE, TokenUsage.ZERO);
                }
                return LlmResponse.of(Message.assistant("done"), LlmStopReason.END_TURN,
                        TokenUsage.ZERO);
            }
        };
    }

    private static SimpleToolRegistry gated(AtomicInteger ran) {
        SimpleToolRegistry registry = new SimpleToolRegistry();
        registry.register(dev.agentkit.core.tool.FunctionTool
                .builder("identity.reset", "Resets a password.")
                .schema(java.util.Map.of("type", "object", "properties",
                        java.util.Map.of("user", java.util.Map.of("type", "string"))))
                .sideEffects(dev.agentkit.core.tool.SideEffects.EXTERNAL)
                .handler(invocation -> {
                    ran.incrementAndGet();
                    return dev.agentkit.core.tool.ToolResult.ok("reset");
                })
                .build());
        return registry;
    }

    private void awaitAQuestion() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline && runtime.pending("acme").isEmpty()) {
            Thread.sleep(10);
        }
        assertThat(runtime.pending("acme")).as("nothing was ever asked").isNotEmpty();
    }

    private Turn awaitIn(ChatStore where, String conversationId, String turnId)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Turn turn = where.turn("acme", conversationId, turnId).orElseThrow();
            if (turn.state().isTerminal()) {
                return turn;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the turn never ended");
    }

    private Turn await(String conversationId, String turnId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Turn turn = store.turn("acme", conversationId, turnId).orElseThrow();
            if (turn.state().isTerminal()) {
                return turn;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the turn never ended");
    }
}
