package dev.agentkit.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What a person's decision is worth: it happens once, it can be widened on purpose, and a
 * question they answer reaches the run as somebody else's words.
 *
 * <h2>Exactly once is the one that has to hold</h2>
 *
 * <p>A console with two tabs open shows the same card twice, and the two Approve buttons are a
 * double-submit waiting to happen. The action behind one of these is by definition hard to
 * reverse — that is why it was gated — so "the password was reset twice" is not a cosmetic
 * defect. The Workbench example has a test of the same name for the same reason.
 */
class ADecisionIsMadeOnceAndMeansSomethingTest {

    private final ChatStore store = new InMemoryChatStore();
    private final ChatEvents events = new ChatEvents();
    private ChatRuntime runtime;

    @AfterEach
    void stop() {
        if (runtime != null) {
            runtime.close();
        }
    }

    /** A model that proposes one gated call, then answers. */
    private static LlmClient proposing(String tool, Map<String, Object> arguments) {
        return new LlmClient() {
            private int calls;

            @Override
            public synchronized LlmResponse generate(LlmRequest request) {
                if (calls++ == 0) {
                    return ScriptedLlm.toolUse("t1", tool, arguments);
                }
                return ScriptedLlm.text("done");
            }
        };
    }

    private SimpleToolRegistry counting(AtomicInteger runs) {
        SimpleToolRegistry registry = new SimpleToolRegistry();
        registry.register(FunctionTool.builder("identity.reset", "Resets a password.")
                .schema(Map.of("type", "object", "properties",
                        Map.of("user", Map.of("type", "string"))))
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> {
                    runs.incrementAndGet();
                    return ToolResult.ok("reset");
                })
                .build());
        return registry;
    }

    private void startGated(AtomicInteger runs, java.util.function.Function<Tool, String> family,
            ChatRuntime.StandingDecisions standing) {
        runtime = new ChatRuntime(store, events, session -> session
                .agent(proposing("identity.reset", Map.of("user", "alice")), counting(runs),
                        AgentConfig.builder("m").maxSteps(4).build())
                .toolGate(ToolGates.requireApproval(invocation -> true, session.approver()))
                .build(), family, standing);
    }

    /** Collects every tool result a run produced. */
    private static dev.agentkit.core.agent.AgentObserver recording(List<ToolResult> into) {
        return new dev.agentkit.core.agent.AgentObserver() {
            @Override
            public void onToolResult(dev.agentkit.core.agent.AgentRun run, int step,
                    dev.agentkit.core.tool.ToolInvocation proposed,
                    dev.agentkit.core.tool.ToolInvocation effective, ToolResult result,
                    dev.agentkit.core.tool.Disposition disposition) {
                into.add(result);
            }
        };
    }

    private void waitForAQuestion() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && runtime.pending("acme").isEmpty()) {
            Thread.sleep(10);
        }
        assertThat(runtime.pending("acme")).as("nothing was ever asked").isNotEmpty();
    }

    private void waitUntilIdle(String conversationId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && runtime.isWorking(conversationId)) {
            Thread.sleep(10);
        }
    }

    @Test
    void twoTabsApprovingAtOnceRunTheToolExactlyOnce() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        startGated(runs, tool -> "identity", ChatRuntime.StandingDecisions.NONE);
        Conversation conversation = store.create("acme", "");
        runtime.say("acme", conversation.id(), "reset her password", List.of());
        waitForAQuestion();
        String approvalId = runtime.pending("acme").get(0).id();

        int tabs = 8;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tabs);
        List<Boolean> accepted = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < tabs; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    accepted.add(runtime.decide("acme", approvalId, ApprovalDecision.approve(),
                            "sid"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        waitUntilIdle(conversation.id());

        assertThat(accepted).filteredOn(Boolean::booleanValue)
                .as("only one press may release the run").hasSize(1);
        assertThat(runs.get()).as("the action itself happened once").isEqualTo(1);
    }

    @Test
    void aRejectedCallDoesNotHappenAndTheNoteIsOnTheRecord() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        startGated(runs, tool -> "identity", ChatRuntime.StandingDecisions.NONE);
        Conversation conversation = store.create("acme", "");
        Turn turn = runtime.say("acme", conversation.id(), "reset it", List.of());
        waitForAQuestion();

        runtime.decide("acme", runtime.pending("acme").get(0).id(),
                ApprovalDecision.deny("She has not asked for this."), "sid",
                "She has not asked for this.", false);
        waitUntilIdle(conversation.id());

        assertThat(runs.get()).isZero();
        // The note is the one place a person says why, and the next run is what it is for.
        assertThat(store.turn("acme", conversation.id(), turn.id()).orElseThrow()
                .stepsOf(Step.Kind.APPROVAL_REQUESTED))
                .anySatisfy(step -> assertThat(String.valueOf(step.detail().get("note")))
                        .contains("has not asked"));
    }

    @Test
    void aStandingDecisionIsFiledAgainstTheCapabilityRatherThanTheCall() throws Exception {
        // "Stop asking me about this" is about a family. Keyed on the call, it would be a
        // setting that never applies again — the same arguments do not come round twice.
        List<String> remembered = new ArrayList<>();
        AtomicInteger runs = new AtomicInteger();
        startGated(runs, tool -> "identity", (capability, approved, by, note) -> {
            remembered.add(capability + ':' + approved + ':' + by);
            return Optional.empty();
        });
        Conversation conversation = store.create("acme", "");
        runtime.say("acme", conversation.id(), "reset it", List.of());
        waitForAQuestion();

        runtime.decide("acme", runtime.pending("acme").get(0).id(), ApprovalDecision.approve(),
                "sid", "", true);
        waitUntilIdle(conversation.id());

        assertThat(remembered).containsExactly("identity:true:sid");
    }

    @Test
    void aStandingDecisionNobodyCanKeepSaysSoRatherThanPretending() throws Exception {
        // An operator who ticked "stop asking me" and was never asked again would believe a
        // thing that is not true. The default refuses in a sentence.
        AtomicInteger runs = new AtomicInteger();
        startGated(runs, tool -> "identity", ChatRuntime.StandingDecisions.NONE);
        Conversation conversation = store.create("acme", "");
        Turn turn = runtime.say("acme", conversation.id(), "reset it", List.of());
        waitForAQuestion();

        runtime.decide("acme", runtime.pending("acme").get(0).id(), ApprovalDecision.approve(),
                "sid", "", true);
        waitUntilIdle(conversation.id());

        assertThat(store.turn("acme", conversation.id(), turn.id()).orElseThrow()
                .stepsOf(Step.Kind.APPROVAL_REQUESTED))
                .anySatisfy(step -> assertThat(String.valueOf(step.detail().get("standingRefused")))
                        .contains("does not keep standing decisions"));
    }

    @Test
    void aDecisionWithNoCapabilityCannotBeMadeStanding() throws Exception {
        // Nothing to key it on. Filing it anywhere would be inventing a family.
        AtomicInteger runs = new AtomicInteger();
        List<String> remembered = new ArrayList<>();
        startGated(runs, tool -> "", (capability, approved, by, note) -> {
            remembered.add(capability);
            return Optional.empty();
        });
        Conversation conversation = store.create("acme", "");
        Turn turn = runtime.say("acme", conversation.id(), "reset it", List.of());
        waitForAQuestion();

        runtime.decide("acme", runtime.pending("acme").get(0).id(), ApprovalDecision.approve(),
                "sid", "", true);
        waitUntilIdle(conversation.id());

        assertThat(remembered).isEmpty();
        assertThat(store.turn("acme", conversation.id(), turn.id()).orElseThrow()
                .stepsOf(Step.Kind.APPROVAL_REQUESTED))
                .anySatisfy(step -> assertThat(String.valueOf(step.detail().get("standingRefused")))
                        .contains("applies only to this call"));
    }

    @Test
    void aQuestionTheAgentAsksReachesTheRunAsSomebodyElsesWords() throws Exception {
        // An operator can be wrong, hurried, or repeating what a requester told them. Their
        // answer is third-party text and travels fenced, exactly as a fetched page would.
        List<ToolResult> answers = new ArrayList<>();
        runtime = new ChatRuntime(store, events, session -> {
            SimpleToolRegistry registry = new SimpleToolRegistry();
            registry.register(ChatTools.askPerson(runtime, session, Duration.ofSeconds(10)));
            return session
                    .agent(proposing("ask_person", Map.of("question", "Which Alice?")), registry,
                            AgentConfig.builder("m").maxSteps(4).build())
                    .observer(recording(answers))
                    .build();
        });
        Conversation conversation = store.create("acme", "");
        runtime.say("acme", conversation.id(), "reset her password", List.of());
        waitForAQuestion();
        ChatRuntime.PendingDecision asked = runtime.pending("acme").get(0);
        assertThat(asked.kind()).isEqualTo(ChatRuntime.PendingDecision.Kind.QUESTION);
        assertThat(asked.question()).isEqualTo("Which Alice?");

        runtime.decide("acme", asked.id(),
                ApprovalDecision.approveWithArguments(Map.of("answer", "Alice Smith")),
                "sid", "", false);
        waitUntilIdle(conversation.id());

        assertThat(answers).isNotEmpty();
        ToolResult answer = answers.get(0);
        assertThat(answer.content()).contains("Alice Smith");
        assertThat(answer.provenance())
                .as("an operator's answer is text the run did not author")
                .isEqualTo(dev.agentkit.core.tool.Provenance.THIRD_PARTY);
        assertThat(answer.content()).as("and it is fenced, like any other body")
                .doesNotStartWith("Alice Smith");
    }

    @Test
    void aPersonWhoDeclinesToAnswerIsNotAnAnswer() throws Exception {
        // "I do not know" has to reach the run as an absence, not as the empty string — a run
        // handed "" would treat it as the answer and act on nothing.
        List<ToolResult> answers = new ArrayList<>();
        runtime = new ChatRuntime(store, events, session -> {
            SimpleToolRegistry registry = new SimpleToolRegistry();
            registry.register(ChatTools.askPerson(runtime, session, Duration.ofSeconds(10)));
            return session
                    .agent(proposing("ask_person", Map.of("question", "Which Alice?")), registry,
                            AgentConfig.builder("m").maxSteps(4).build())
                    .observer(recording(answers))
                    .build();
        });
        Conversation conversation = store.create("acme", "");
        runtime.say("acme", conversation.id(), "reset her password", List.of());
        waitForAQuestion();

        runtime.decide("acme", runtime.pending("acme").get(0).id(),
                ApprovalDecision.approveWithArguments(Map.of("answer", "")), "sid", "", false);
        waitUntilIdle(conversation.id());

        assertThat(answers).isNotEmpty();
        assertThat(answers.get(0).content()).contains("Nobody answered");
    }

    @Test
    void nobodyAnsweringIsNotAFailure() throws Exception {
        // The person closed the tab, or the question was not worth their afternoon. An agent
        // that treats silence as a failure and stops needs babysitting to finish anything.
        List<ToolResult> answers = new ArrayList<>();
        runtime = new ChatRuntime(store, events, session -> {
            SimpleToolRegistry registry = new SimpleToolRegistry();
            registry.register(ChatTools.askPerson(runtime, session, Duration.ofMillis(200)));
            return session
                    .agent(proposing("ask_person", Map.of("question", "Which Alice?")), registry,
                            AgentConfig.builder("m").maxSteps(4).build())
                    .observer(recording(answers))
                    .build();
        });
        Conversation conversation = store.create("acme", "");
        runtime.say("acme", conversation.id(), "reset her password", List.of());
        waitUntilIdle(conversation.id());

        assertThat(answers).isNotEmpty();
        assertThat(answers.get(0).isError()).isFalse();
        assertThat(answers.get(0).content()).contains("Nobody answered");
    }
}
