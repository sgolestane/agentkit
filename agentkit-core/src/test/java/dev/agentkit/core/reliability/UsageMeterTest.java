package dev.agentkit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.context.ContextStrategies;
import dev.agentkit.core.context.SummarizingCompactor;
import dev.agentkit.core.llm.DelegatingLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.StreamHandler;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UsageMeterTest {

    private static LlmRequest request() {
        return LlmRequest.builder("m").addMessage(Message.user("x")).build();
    }

    private static LlmClient spending(TokenUsage each) {
        return request -> new LlmResponse(Message.of(Role.ASSISTANT, TextBlock.of("ok")),
                LlmStopReason.END_TURN, each, Optional.empty());
    }

    @Test
    void talliesCallsAndTokensPerRole() {
        UsageMeter meter = new UsageMeter();
        LlmClient a = meter.wrap("agent", spending(new TokenUsage(10, 5)));
        LlmClient b = meter.wrap("compaction", spending(new TokenUsage(1_000, 100)));

        a.generate(LlmRequest.builder("m").addMessage(Message.user("x")).build());
        a.generate(LlmRequest.builder("m").addMessage(Message.user("x")).build());
        b.generate(LlmRequest.builder("m").addMessage(Message.user("x")).build());

        assertThat(meter.forRole("agent").calls()).isEqualTo(2);
        assertThat(meter.forRole("agent").usage()).isEqualTo(new TokenUsage(20, 10));
        assertThat(meter.forRole("compaction").calls()).isEqualTo(1);
        assertThat(meter.calls()).isEqualTo(3);
        assertThat(meter.total()).isEqualTo(new TokenUsage(1_020, 110));
        assertThat(meter.byRole()).containsOnlyKeys("agent", "compaction");
    }

    @Test
    void anUnusedRoleIsZeroRatherThanAbsent() {
        UsageMeter meter = new UsageMeter();
        assertThat(meter.forRole("never-called")).isEqualTo(UsageMeter.Tally.ZERO);
        assertThat(meter.total()).isEqualTo(TokenUsage.ZERO);
        assertThat(meter.calls()).isZero();
    }

    @Test
    void aFailedCallIsNotTallied() {
        UsageMeter meter = new UsageMeter();
        LlmClient failing = meter.wrap(request -> {
            throw new LlmException("provider down");
        });

        assertThatThrownBy(() -> failing.generate(
                LlmRequest.builder("m").addMessage(Message.user("x")).build()))
                .isInstanceOf(LlmException.class);

        assertThat(meter.calls()).isZero(); // no response, nothing spent to record
    }

    @Test
    void resetClearsTheTally() {
        UsageMeter meter = new UsageMeter();
        LlmClient llm = meter.wrap(spending(new TokenUsage(5, 5)));
        llm.generate(LlmRequest.builder("m").addMessage(Message.user("x")).build());

        meter.reset();

        assertThat(meter.calls()).isZero();
        assertThat(meter.byRole()).isEmpty();
    }

    @Test
    void meteringForwardsTheStreamingCallSoDeltasStillArrive() {
        List<String> deltas = new ArrayList<>();
        LlmClient streaming = new LlmClient() {
            @Override
            public LlmResponse generate(LlmRequest request) {
                throw new AssertionError("the streaming overload must be forwarded, not collapsed");
            }

            @Override
            public LlmResponse generate(LlmRequest request, StreamHandler handler) {
                handler.onTextDelta("He");
                handler.onTextDelta("llo");
                return new LlmResponse(Message.of(Role.ASSISTANT, TextBlock.of("Hello")),
                        LlmStopReason.END_TURN, new TokenUsage(3, 2), Optional.empty());
            }
        };
        UsageMeter meter = new UsageMeter();

        meter.wrap(streaming).generate(
                LlmRequest.builder("m").addMessage(Message.user("x")).build(), deltas::add);

        assertThat(deltas).containsExactly("He", "llo"); // real deltas, not one lump
        assertThat(meter.total()).isEqualTo(new TokenUsage(3, 2)); // and still metered
    }

    @Test
    void summaryBreaksDownEveryRole() {
        UsageMeter meter = new UsageMeter();
        meter.wrap("agent", spending(new TokenUsage(10, 5)))
                .generate(LlmRequest.builder("m").addMessage(Message.user("x")).build());

        assertThat(meter.summary()).contains("agent").contains("1 call(s)")
                .contains("15 tokens").contains("TOTAL");
    }

    @Test
    void severalClientsMayShareARoleAndTheirTalliesAddUp() {
        UsageMeter meter = new UsageMeter();
        LlmClient one = meter.wrap("agent", spending(new TokenUsage(10, 0)));
        LlmClient two = meter.wrap("agent", spending(new TokenUsage(0, 7)));

        one.generate(request());
        two.generate(request());

        assertThat(meter.forRole("agent")).isEqualTo(new UsageMeter.Tally(2, new TokenUsage(10, 7)));
    }

    @Test
    void theDefaultRoleIsUsedWhenNoneIsGiven() {
        UsageMeter meter = new UsageMeter();
        meter.wrap(spending(new TokenUsage(1, 1))).generate(request());

        assertThat(meter.byRole()).containsOnlyKeys(UsageMeter.DEFAULT_ROLE);
    }

    @Test
    void rolesAreOrderedAndTheSnapshotIsImmutableAndDetached() {
        UsageMeter meter = new UsageMeter();
        meter.wrap("zeta", spending(new TokenUsage(1, 0))).generate(request());
        meter.wrap("alpha", spending(new TokenUsage(1, 0))).generate(request());

        var snapshot = meter.byRole();
        assertThat(snapshot.keySet()).containsExactly("alpha", "zeta"); // sorted
        assertThatThrownBy(() -> snapshot.put("x", UsageMeter.Tally.ZERO))
                .isInstanceOf(UnsupportedOperationException.class);

        // A later call must not mutate an already-taken snapshot.
        meter.wrap("alpha", spending(new TokenUsage(1, 0))).generate(request());
        assertThat(snapshot.get("alpha").calls()).isEqualTo(1);
        assertThat(meter.forRole("alpha").calls()).isEqualTo(2);
    }

    @Test
    void aBlankOrNullRoleIsRejected() {
        UsageMeter meter = new UsageMeter();
        assertThatThrownBy(() -> meter.wrap("  ", spending(TokenUsage.ZERO)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blank");
        assertThatThrownBy(() -> meter.wrap(null, spending(TokenUsage.ZERO)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> meter.wrap("agent", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void concurrentRecordingLosesNothingAndReadsAreSelfConsistent() throws Exception {
        // The riskiest claim in the class: one meter spanning concurrent subagents.
        // Also pins that a read's parts agree — the per-role lines and the total come
        // from one snapshot, so a report rendered mid-run cannot contradict itself.
        UsageMeter meter = new UsageMeter();
        int threads = 8;
        int perThread = 500;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String role = "r" + (t % 3); // roles deliberately shared across threads
            futures.add(pool.submit(() -> {
                LlmClient llm = meter.wrap(role, spending(new TokenUsage(1, 1)));
                start.await();
                for (int i = 0; i < perThread; i++) {
                    llm.generate(request());
                }
                return null;
            }));
        }
        start.countDown();
        // Read while writers are running; every read must be internally consistent.
        for (int i = 0; i < 200; i++) {
            var snapshot = meter.byRole();
            long lineSum = snapshot.values().stream().mapToLong(UsageMeter.Tally::calls).sum();
            long tokenSum = snapshot.values().stream()
                    .mapToLong(t -> t.usage().totalTokens()).sum();
            assertThat(lineSum * 2).isEqualTo(tokenSum); // 2 tokens per call, always
        }
        for (var f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(meter.calls()).isEqualTo((long) threads * perThread); // nothing lost
        assertThat(meter.total()).isEqualTo(
                new TokenUsage((long) threads * perThread, (long) threads * perThread));
        assertThat(meter.totalTally().calls()).isEqualTo(meter.calls());
    }

    @Test
    void unwrapSeesPastMeteringSoAClientCanStillBeInspected() {
        // A metered client must not disguise what it wraps: the durable worker guard
        // rejects a BudgetLlmClient, and metering one would otherwise smuggle it past.
        UsageMeter meter = new UsageMeter();
        LlmClient budgeted = new BudgetLlmClient(spending(TokenUsage.ZERO),
                TokenBudget.ofTotalTokens(100));

        LlmClient metered = meter.wrap("agent", meter.wrap("inner", budgeted)); // nested

        assertThat(DelegatingLlmClient.unwrap(metered)).isSameAs(budgeted);
        assertThat(DelegatingLlmClient.unwrap(budgeted)).isSameAs(budgeted); // not a decorator
    }

    @Test
    void twoWrappersOfTheSameClientAreDistinct() {
        // Identity equality: a stateful decorator should not be interchangeable with
        // another wrapping the same delegate.
        UsageMeter meter = new UsageMeter();
        LlmClient raw = spending(TokenUsage.ZERO);
        assertThat(meter.wrap("agent", raw)).isNotEqualTo(meter.wrap("agent", raw));
    }

    @Test
    void meteringCapturesTheCompactionSpendAgentResultCannotSee() {
        // The finding this class exists for: AgentResult.usage() counts only the loop's
        // turns, so a compacting agent under-reports its real spend — here by ~99%.
        String huge = "X".repeat(40_000);
        AtomicInteger toolCalls = new AtomicInteger();
        var tools = new SimpleToolRegistry().register(
                FunctionTool.builder("fetch", "fetches a big blob").handler(i -> {
                    toolCalls.incrementAndGet();
                    return ToolResult.ok(huge);
                }).build());

        // One underlying client, two roles: cheap agent turns, expensive summarisation.
        LlmClient raw = new LlmClient() {
            private int calls;

            @Override
            public LlmResponse generate(LlmRequest request) {
                if (request.tools().isEmpty()) { // the summariser advertises no tools
                    return new LlmResponse(Message.of(Role.ASSISTANT, TextBlock.of("SUMMARY")),
                            LlmStopReason.END_TURN, new TokenUsage(5_000, 500), Optional.empty());
                }
                if (++calls >= 5) {
                    return new LlmResponse(Message.of(Role.ASSISTANT, TextBlock.of("final")),
                            LlmStopReason.END_TURN, new TokenUsage(10, 10), Optional.empty());
                }
                return new LlmResponse(
                        Message.of(Role.ASSISTANT, ProposedCall.of("t" + calls, "fetch", Map.of())),
                        LlmStopReason.TOOL_USE, new TokenUsage(10, 10), Optional.empty());
            }
        };

        UsageMeter meter = new UsageMeter();
        LlmClient agentLlm = meter.wrap("agent", raw);
        LlmClient compactionLlm = meter.wrap("compaction", raw);

        var compactor = SummarizingCompactor.builder(compactionLlm, "m")
                .triggerTokens(1_000).keepRecentMessages(6).build();

        AgentResult result = Agent.builder(agentLlm, tools, AgentConfig.builder("m").maxSteps(5).build())
                .contextStrategy(ContextStrategies.compacting(compactor))
                .build()
                .run(Goal.of("go"));

        // Pinned, not just "positive": a change that reduced compaction to one trivial
        // call would otherwise keep this green while the point it makes evaporated.
        assertThat(meter.forRole("compaction").calls()).isEqualTo(1);
        assertThat(meter.forRole("compaction").usage()).isEqualTo(new TokenUsage(5_000, 500));
        assertThat(meter.forRole("agent").calls()).isEqualTo(5);

        // The loop's own accounting sees the agent turns and nothing else...
        assertThat(result.usage()).isEqualTo(meter.forRole("agent").usage());
        assertThat(result.usage().totalTokens()).isEqualTo(100);
        // ...so it reports under 2% of what the run actually spent.
        assertThat(meter.total().totalTokens()).isEqualTo(5_600);
        assertThat(result.usage().totalTokens() * 100.0 / meter.total().totalTokens())
                .isLessThan(2.0);
    }
}
