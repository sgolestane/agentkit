package dev.agentkit.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The loop does no more work on an interrupted thread, and does not eat the flag (#183).
 *
 * <h2>What was measured before the fix</h2>
 *
 * <p>{@code Agent.run} on a dedicated thread, one tool that increments a counter, sets the
 * interrupt flag and throws. Three trials, identical:
 *
 * <pre>
 * ran=1 flagAtLlmCall=[false, true] flagAtEnd=true stopReason=COMPLETED steps=2
 * </pre>
 *
 * <p>The tool ran once — nothing re-delivers in process — but the flag reached the next
 * model call and then the caller, under a run reporting success. {@code FakeLlmClient} does
 * not care; a real client does. Measured separately, {@code HttpClient.send} against a
 * loopback server that answers immediately, on a thread carrying the flag: three throws of
 * {@code InterruptedException} out of three, before any I/O.
 *
 * <h2>Why every wait here is bounded</h2>
 *
 * <p>These tests run the agent on a thread of their own, because the property under test is
 * about the flag on the thread {@code run} occupies and JUnit's own thread is shared. Each
 * one is a daemon and every join is bounded, so a regression that hangs the loop fails a
 * named assertion instead of the suite: a thread parked in a syscall does not answer an
 * interrupt, and there is no reclaiming it.
 */
class AgentInterruptTest {

    private static final long BOUND_MILLIS = 10_000;

    private static final AgentConfig CONFIG = AgentConfig.builder("m").maxSteps(5).build();

    /** The tool from the measurement: it runs, litters, and throws. */
    private static final class Littering implements Tool {
        final AtomicInteger ran = new AtomicInteger();

        @Override public String name() {
            return "publish";
        }

        @Override public String description() {
            return "publishes something";
        }

        @Override public Map<String, Object> inputSchema() {
            return Map.of();
        }

        @Override public ToolResult execute(ToolInvocation invocation) {
            ran.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("boom");
        }
    }

    /** A well-behaved tool, for the control. */
    private static final class Quiet implements Tool {
        @Override public String name() {
            return "publish";
        }

        @Override public String description() {
            return "publishes something";
        }

        @Override public Map<String, Object> inputSchema() {
            return Map.of();
        }

        @Override public ToolResult execute(ToolInvocation invocation) {
            return ToolResult.ok("published");
        }
    }

    /** Records the flag as each model call sees it, then answers from a script. */
    private static final class Watching implements LlmClient {
        private final FakeLlmClient delegate;
        final List<Boolean> flagAtCall = new ArrayList<>();

        Watching(LlmResponse... responses) {
            this.delegate = new FakeLlmClient(responses);
        }

        @Override public LlmResponse generate(LlmRequest request) {
            flagAtCall.add(Thread.currentThread().isInterrupted());
            return delegate.generate(request);
        }
    }

    /**
     * What a real client does on an interrupted thread, without the socket.
     *
     * <p>{@code JdkHttpTransport.post} catches the {@code InterruptedException} that
     * {@code HttpClient.send} throws, re-sets the flag and wraps it in an
     * {@link LlmException} — so before #183 this turn ended the run as {@code ERROR},
     * naming an interruption the request had nothing to do with.
     */
    private static final class InterruptibleTransport implements LlmClient {
        private final FakeLlmClient delegate;
        final AtomicInteger entered = new AtomicInteger();

        InterruptibleTransport(LlmResponse... responses) {
            this.delegate = new FakeLlmClient(responses);
        }

        @Override public LlmResponse generate(LlmRequest request) {
            entered.incrementAndGet();
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new LlmException("HTTP request was interrupted",
                        new InterruptedException());
            }
            return delegate.generate(request);
        }
    }

    /** An observer that records what {@code onFinish} was handed and what thread it got. */
    private static final class Finishing implements AgentObserver {
        final AtomicInteger finishes = new AtomicInteger();
        volatile Boolean flagInsideOnFinish;
        volatile AgentResult finished;

        @Override public void onFinish(AgentRun run, AgentResult result) {
            finishes.incrementAndGet();
            flagInsideOnFinish = Thread.currentThread().isInterrupted();
            finished = result;
        }
    }

    /** One run on its own thread: what it returned, and the flag it left behind. */
    private record Ran(AgentResult result, boolean flagAtEnd) {}

    private static Ran runOnItsOwnThread(Agent agent, boolean interruptFirst) {
        AgentResult[] result = new AgentResult[1];
        boolean[] flagAtEnd = new boolean[1];
        Throwable[] escaped = new Throwable[1];
        Thread thread = new Thread(() -> {
            if (interruptFirst) {
                Thread.currentThread().interrupt();
            }
            try {
                result[0] = agent.run(Goal.of("go"));
            } catch (Throwable t) {
                escaped[0] = t;
            }
            flagAtEnd[0] = Thread.currentThread().isInterrupted();
        }, "agent-interrupt-test");
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(BOUND_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("the test thread was interrupted while waiting", e);
        }
        assertThat(thread.isAlive())
                .as("the run finished within %d ms", BOUND_MILLIS).isFalse();
        assertThat(escaped[0]).as("nothing escaped Agent.run").isNull();
        return new Ran(result[0], flagAtEnd[0]);
    }

    private static LlmResponse toolUseAfterSaying(String text, String id, String name) {
        return LlmResponse.of(
                Message.of(Role.ASSISTANT,
                        List.<ContentBlock>of(TextBlock.of(text),
                                ProposedCall.of(id, name, Map.of()))),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO);
    }

    @Test
    void aToolsStrayFlagStopsTheRunInsteadOfReachingTheNextModelCall() {
        Littering tool = new Littering();
        Watching llm = new Watching(
                FakeLlmClient.toolUse("t1", "publish", Map.of()),
                FakeLlmClient.text("done"));
        Agent agent = new Agent(llm, new SimpleToolRegistry().register(tool), CONFIG);

        Ran ran = runOnItsOwnThread(agent, false);

        assertThat(tool.ran.get()).as("the tool ran exactly once").isEqualTo(1);
        assertThat(llm.flagAtCall)
                .as("the model was called once, on a clean thread; the second call — which"
                        + " measured [false, true] before #183 — never happens")
                .containsExactly(false);
        assertThat(ran.result().stopReason()).isEqualTo(StopReason.CANCELLED);
        assertThat(ran.result().steps()).as("the step that ran is still counted").isEqualTo(1);
    }

    @Test
    void theFlagIsHandedBackSoACallersOwnCancellationIsNotSwallowed() {
        Littering tool = new Littering();
        Watching llm = new Watching(
                FakeLlmClient.toolUse("t1", "publish", Map.of()),
                FakeLlmClient.text("done"));
        Agent agent = new Agent(llm, new SimpleToolRegistry().register(tool), CONFIG);

        Ran ran = runOnItsOwnThread(agent, false);

        assertThat(ran.flagAtEnd())
                .as("restored before returning: the loop cannot tell a tool's litter from a"
                        + " caller's cancellation, and clearing would lose the cancellation")
                .isTrue();
    }

    @Test
    void aCallerWhoInterruptsBeforeTheRunGetsNoModelCallAtAll() {
        Watching llm = new Watching(FakeLlmClient.text("done"));
        Agent agent = new Agent(llm, new SimpleToolRegistry().register(new Quiet()), CONFIG);

        Ran ran = runOnItsOwnThread(agent, true);

        assertThat(llm.flagAtCall).as("the model is never called").isEmpty();
        assertThat(ran.result().stopReason()).isEqualTo(StopReason.CANCELLED);
        assertThat(ran.result().steps()).isZero();
        assertThat(ran.flagAtEnd()).as("still the caller's, still set").isTrue();
    }

    @Test
    void onFinishStillFiresAndSeesACleanThread() {
        Littering tool = new Littering();
        Finishing observer = new Finishing();
        Watching llm = new Watching(
                FakeLlmClient.toolUse("t1", "publish", Map.of()),
                FakeLlmClient.text("done"));
        Agent agent = Agent.builder(llm, new SimpleToolRegistry().register(tool), CONFIG)
                .observer(observer)
                .build();

        Ran ran = runOnItsOwnThread(agent, false);

        assertThat(observer.finishes.get())
                .as("an observer holding per-run state still sees the end of the run (#166)")
                .isEqualTo(1);
        assertThat(observer.flagInsideOnFinish)
                .as("cleared before the dispatch, so an observer doing blocking work in"
                        + " onFinish does not hit the broken I/O this fix exists to avoid")
                .isFalse();
        assertThat(observer.finished.stopReason()).isEqualTo(StopReason.CANCELLED);
        assertThat(ran.flagAtEnd()).as("and put back afterwards, not before").isTrue();
    }

    @Test
    void theModelsLastTextSurvivesTheStop() {
        Littering tool = new Littering();
        Watching llm = new Watching(
                toolUseAfterSaying("publishing the report now", "t1", "publish"),
                FakeLlmClient.text("done"));
        Agent agent = new Agent(llm, new SimpleToolRegistry().register(tool), CONFIG);

        Ran ran = runOnItsOwnThread(agent, false);

        assertThat(ran.result().output())
                .as("an ordinary stop keeps what the run had established")
                .isEqualTo("publishing the report now");
        assertThat(ran.result().error()).isEmpty();
    }

    @Test
    void theNextTurnIsNeverMadeToPayForTheFlagWithARealTransport() {
        Littering tool = new Littering();
        InterruptibleTransport llm = new InterruptibleTransport(
                FakeLlmClient.toolUse("t1", "publish", Map.of()),
                FakeLlmClient.text("done"));
        Agent agent = new Agent(llm, new SimpleToolRegistry().register(tool), CONFIG);

        Ran ran = runOnItsOwnThread(agent, false);

        assertThat(llm.entered.get())
                .as("the client is entered once; a second entry is the turn that fails for a"
                        + " reason unrelated to the request")
                .isEqualTo(1);
        assertThat(ran.result().stopReason())
                .as("CANCELLED, not ERROR wrapping an InterruptedException from an HTTP send")
                .isEqualTo(StopReason.CANCELLED);
    }

    @Test
    void aRunOnACleanThreadIsUnaffected() {
        Finishing observer = new Finishing();
        Watching llm = new Watching(
                FakeLlmClient.toolUse("t1", "publish", Map.of()),
                FakeLlmClient.text("done"));
        Agent agent = Agent.builder(llm, new SimpleToolRegistry().register(new Quiet()), CONFIG)
                .observer(observer)
                .build();

        Ran ran = runOnItsOwnThread(agent, false);

        assertThat(ran.result().stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(ran.result().output()).isEqualTo("done");
        assertThat(ran.result().steps()).isEqualTo(2);
        assertThat(llm.flagAtCall)
                .as("both turns run on a clean thread").containsExactly(false, false);
        assertThat(ran.flagAtEnd())
                .as("nothing set the flag, so nothing hands one back").isFalse();
    }
}
