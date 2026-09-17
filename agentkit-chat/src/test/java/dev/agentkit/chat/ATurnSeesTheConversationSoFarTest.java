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
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.tool.SimpleToolRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A turn is its own run, so "INC-4211" typed in answer to "which incident?" used to reach a model that had never
 * asked. Each turn is now given the conversation's recent finished turns — message and answer, fenced — after its
 * own text.
 */
class ATurnSeesTheConversationSoFarTest {

    private final ChatStore store = new InMemoryChatStore();
    private final ChatEvents events = new ChatEvents();
    private final List<LlmRequest> seen = new CopyOnWriteArrayList<>();
    private final AtomicInteger answers = new AtomicInteger();
    private ChatRuntime runtime;

    @AfterEach
    void stop() {
        if (runtime != null) {
            runtime.close();
        }
    }

    private void start(ChatRuntime.History history) {
        LlmClient recording = request -> {
            seen.add(request);
            return LlmResponse.of(Message.assistant("answer " + answers.incrementAndGet()), LlmStopReason.END_TURN,
                    TokenUsage.ZERO);
        };
        runtime = new ChatRuntime(store, events, session -> session
                .agent(recording, new SimpleToolRegistry(), AgentConfig.builder("m").maxSteps(2).build())
                .build(), tool -> "", ChatRuntime.StandingDecisions.NONE, history);
    }

    @Test
    void theSecondTurnIsGivenTheFirstAfterItsOwnText() throws Exception {
        start(ChatRuntime.History.DEFAULT);
        Conversation conversation = store.create("acme", "");

        say(conversation, "I need read access to payments-prod.");
        say(conversation, "It's for INC-4211.");

        List<ContentBlock> first = seen.get(0).messages().getFirst().content();
        assertThat(first).hasSize(1);

        List<ContentBlock> second = seen.get(1).messages().getFirst().content();
        assertThat(((TextBlock) second.get(0)).text()).contains("It's for INC-4211.").doesNotContain("payments-prod");
        String earlier = ((TextBlock) second.get(1)).text();
        assertThat(earlier).contains("Earlier in this conversation")
                .contains("kind=\"advisory\"")
                .contains("Person: I need read access to payments-prod.")
                .contains("Assistant: answer 1");
        // The goal is still only this turn's message: it is what gets logged, compared and observed.
        assertThat(earlier.indexOf("I need read access")).isGreaterThan(earlier.indexOf("<untrusted"));
    }

    @Test
    void onlyTheMostRecentTurnsAreGivenAndEachIsBounded() throws Exception {
        start(new ChatRuntime.History(2, 40));
        Conversation conversation = store.create("acme", "");

        say(conversation, "one");
        say(conversation, "two");
        say(conversation, "three " + "x".repeat(200));
        say(conversation, "four");

        String earlier = ((TextBlock) seen.get(3).messages().getFirst().content().get(1)).text();
        assertThat(earlier).doesNotContain("Person: one").contains("Person: two").contains("Person: three")
                .doesNotContain("x".repeat(100));
    }

    @Test
    void noneMeansEachTurnSeesOnlyItsOwnMessage() throws Exception {
        start(ChatRuntime.History.NONE);
        Conversation conversation = store.create("acme", "");

        say(conversation, "first");
        say(conversation, "second");

        assertThat(seen.get(1).messages().getFirst().content()).hasSize(1);
        assertThat(seen.stream().flatMap(r -> r.messages().stream()).flatMap(m -> m.content().stream())
                .filter(TextBlock.class::isInstance).map(b -> ((TextBlock) b).text()).collect(Collectors.joining()))
                .doesNotContain("Earlier in this conversation");
    }

    @Test
    void anotherConversationIsNotGiven() throws Exception {
        start(ChatRuntime.History.DEFAULT);
        Conversation one = store.create("acme", "");
        Conversation two = store.create("acme", "");

        say(one, "secret plan for one");
        say(two, "hello");

        assertThat(seen.get(1).messages().getFirst().content()).hasSize(1);
    }

    private void say(Conversation conversation, String text) throws InterruptedException {
        Turn turn = runtime.say("acme", conversation.id(), text, List.of());
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (store.turn("acme", conversation.id(), turn.id()).map(t -> t.state().isTerminal()).orElse(false)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("turn did not finish");
    }
}
