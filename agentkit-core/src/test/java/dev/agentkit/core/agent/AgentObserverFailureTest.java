package dev.agentkit.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.Observations;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * An observer that throws must not decide whether a run completes (#166).
 *
 * <h2>Why one test per callback</h2>
 *
 * <p>Because the defect was eight unguarded dispatch sites, and the natural way to fix that
 * by hand is to guard the ones you can see. Two of the eight live on the reused-tool-call-id
 * path, which nothing else in the suite exercises, so a single "an observer that throws does
 * not kill the run" test would have passed with those two still bare.
 *
 * <p>Measured before the fix, with an observer doing exactly what the itops audit observer
 * did — {@code Map.copyOf} on arguments carrying a JSON null:
 *
 * <pre>
 * ESCAPED Agent.run: java.lang.NullPointerException
 * tool actually ran : true
 * audit rows written: []
 * onFinish called   : false
 * </pre>
 *
 * <p>The tool ran, nothing recorded it, and the caller got an exception rather than an
 * {@code AgentResult}. That is the shape these tests pin, one callback at a time.
 */
class AgentObserverFailureTest {

    /** Which callback the observer under test blows up in. */
    private enum Callback { ON_START, ON_TEXT_DELTA, ON_MODEL_RESPONSE, ON_TOOL_INVOCATION,
        ON_TOOL_RESULT, ON_FINISH }

    /** An observer that throws from the callbacks it is told to, and records the rest. */
    private static final class Exploding implements AgentObserver {
        private final Set<Callback> throwsFrom;
        private final List<String> reached = new ArrayList<>();

        Exploding(Callback... from) {
            this.throwsFrom = from.length == 0 ? EnumSet.noneOf(Callback.class)
                    : EnumSet.copyOf(List.of(from));
        }

        private void at(Callback callback) {
            reached.add(callback.name());
            if (throwsFrom.contains(callback)) {
                throw new IllegalStateException("observer failed at " + callback);
            }
        }

        @Override public void onStart(AgentRun run, Goal goal) {
            at(Callback.ON_START);
        }

        @Override public void onTextDelta(AgentRun run, int step, String delta) {
            at(Callback.ON_TEXT_DELTA);
        }

        @Override public void onModelResponse(AgentRun run, int step,
                                              dev.agentkit.core.llm.LlmResponse r) {
            at(Callback.ON_MODEL_RESPONSE);
        }

        @Override public void onToolProposed(AgentRun run, int step, ToolInvocation invocation) {
            at(Callback.ON_TOOL_INVOCATION);
        }

        @Override public void onToolResult(AgentRun run, int step, ToolInvocation proposed,
                ToolInvocation inv, ToolResult result,
                dev.agentkit.core.tool.Disposition disposition) {
            at(Callback.ON_TOOL_RESULT);
        }

        @Override public void onFinish(AgentRun run, AgentResult result) {
            at(Callback.ON_FINISH);
        }
    }

    /** A tool that records whether it ran, so "did the action happen" is answerable. */
    private record Recorder(List<ToolInvocation> entered) implements Tool {
        public String name() {
            return "publish";
        }

        public String description() {
            return "publishes text";
        }

        public Map<String, Object> inputSchema() {
            return Map.of("type", "object");
        }

        public ToolResult execute(ToolInvocation invocation) {
            entered.add(invocation);
            return ToolResult.ok("published");
        }
    }

    private static Agent agentWith(Exploding observer, List<ToolInvocation> entered,
                                   LlmResponse... script) {
        SimpleToolRegistry tools = new SimpleToolRegistry().register(new Recorder(entered));
        // Streaming, so onTextDelta is dispatched at all. It is the eighth guarded site and
        // the only one inside a lambda handed to the LLM client, and it was guarded and not
        // tested in the first draft of this change: removing that guard left all 1,000 core
        // tests green. LlmClient's default streaming implementation emits the assembled
        // text as one delta, which is enough to reach the dispatch.
        return Agent.builder(new FakeLlmClient(script), tools,
                        AgentConfig.builder("m").maxSteps(4).build())
                .observer(observer)
                .streaming(true)
                .build();
    }

    private static LlmResponse[] callsThenStops() {
        return new LlmResponse[] {
                FakeLlmClient.toolUse("call-1", "publish", Map.of("text", "x")),
                FakeLlmClient.text("done")};
    }

    @Test
    void aThrowFromAnyCallbackStillReturnsAResultAndStillFinishes() {
        for (Callback failing : Callback.values()) {
            List<ToolInvocation> entered = new ArrayList<>();
            Exploding observer = new Exploding(failing);

            AgentResult result = agentWith(observer, entered, callsThenStops())
                    .run(Goal.of("publish something"));

            assertThat(result)
                    .as("%s threw and the run produced no result at all", failing)
                    .isNotNull();
            assertThat(result.stopReason())
                    .as("%s threw and changed how the run ended", failing)
                    .isEqualTo(StopReason.COMPLETED);
            assertThat(observer.reached)
                    .as("%s threw and onFinish never fired, so an observer holding per-run"
                            + " state is left with a run it never sees the end of", failing)
                    .contains(Callback.ON_FINISH.name());
            // The positive control for the loop itself. Without it a callback the script
            // never reaches passes vacuously — which is exactly what ON_TEXT_DELTA did
            // before this test began driving a streaming agent.
            assertThat(observer.reached)
                    .as("%s was never dispatched, so this iteration asserted nothing about"
                            + " whether its guard exists", failing)
                    .contains(failing.name());
        }
    }

    @Test
    void theDeploymentIsToldWhichCallbackFailedAndWhatItThrew() {
        // The half that is a decision rather than a bug fix, and this is its second shape.
        //
        // The first put a failure COUNT on AgentResult, arguing from Synthesizers.fenceOf's
        // "a cap nobody is told about reads as everything was carried". That argument does
        // not reach here: all three consumers of Spotlight.Bounded.cut() resolve it with a
        // log line or a line of prompt text, and none puts it in a returned result. The
        // count also shipped with no reader — AgentRunResult.from dropped it, and
        // AgentTelemetry never set it.
        //
        // Two measurements retired it outright. onFinish is where an audit observer writes
        // its closing record, and it fails after the result exists, so the count could not
        // answer the one question it was for. And onTextDelta fires per streamed chunk: a
        // run with 800 deltas reported 800 failures, next to which one missing audit row
        // also counted 1.
        //
        // A handler has neither problem, and gives the deployment the two things a count
        // could not: which callback, and what it threw.
        List<String> told = new ArrayList<>();
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(new Recorder(entered));
        Agent agent = Agent.builder(new FakeLlmClient(callsThenStops()), tools,
                        AgentConfig.builder("m").maxSteps(4).build())
                .observer(new Exploding(Callback.ON_TOOL_RESULT))
                .onObservationFailure((callback, failure) ->
                        told.add(callback + ":" + failure.getClass().getSimpleName()))
                .build();

        AgentResult result = agent.run(Goal.of("publish something"));

        assertThat(told)
                .as("the deployment was not told which callback failed, so an audit"
                        + " component could not repair the row it failed to write")
                .containsExactly("onToolResult:IllegalStateException");
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
    }

    @Test
    void aHandlerThatThrowsDoesNotPutTheDefectBackOneLevelUp() {
        // The obvious way to reintroduce #166: guard the observer, then let whatever is
        // told about the failure take the run down instead.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(new Recorder(entered));
        Agent agent = Agent.builder(new FakeLlmClient(callsThenStops()), tools,
                        AgentConfig.builder("m").maxSteps(4).build())
                .observer(new Exploding(Callback.ON_TOOL_RESULT))
                .onObservationFailure((callback, failure) -> {
                    throw new IllegalStateException("the handler failed too");
                })
                .build();

        AgentResult result = agent.run(Goal.of("publish something"));

        assertThat(result.stopReason())
                .as("a throwing failure handler took the run down, which is the defect this"
                        + " whole change is about, one level up")
                .isEqualTo(StopReason.COMPLETED);
        assertThat(entered).hasSize(1);
    }

    @Test
    void anErrorIsAbsorbedToo() {
        // The Throwable-not-RuntimeException decision, which this change argues at length
        // in three places and pinned nowhere: every observer in this file throws
        // IllegalStateException, so narrowing the catch to RuntimeException left all 1,000
        // core tests green.
        //
        // It matters because the failures that motivate the guard are Errors. An observer
        // allocating per call is where a StackOverflowError turns up, and a broken observer
        // class is exactly a NoClassDefFoundError. Frozen's javadoc already records that
        // shape reaching Agent.run as a run lost with no result and no onFinish — though
        // note Frozen's own fix was to stop PRODUCING the Error rather than to catch one,
        // so it is the failure mode this cites, not the remedy.
        List<String> told = new ArrayList<>();
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(new Recorder(entered));
        Agent agent = Agent.builder(new FakeLlmClient(callsThenStops()), tools,
                        AgentConfig.builder("m").maxSteps(4).build())
                .observer(new AgentObserver() {
                    @Override public void onToolResult(AgentRun run, int step,
                                                       ToolInvocation proposed,
                            ToolInvocation inv, ToolResult result,
                            dev.agentkit.core.tool.Disposition disposition) {
                        throw new StackOverflowError("observer recursed");
                    }
                })
                .onObservationFailure((callback, failure) ->
                        told.add(callback + ":" + failure.getClass().getSimpleName()))
                .build();

        AgentResult result = agent.run(Goal.of("publish something"));

        assertThat(result.stopReason())
                .as("an Error from an observer took the run down, which is the case the"
                        + " wide catch exists for")
                .isEqualTo(StopReason.COMPLETED);
        assertThat(told).containsExactly("onToolResult:StackOverflowError");
        assertThat(entered).hasSize(1);
    }

    @Test
    void aLostCancellationIsPutBackOnTheThread() {
        // Absorbing is otherwise a cancellation regression. An AgentObserver cannot declare
        // InterruptedException, so an observer doing blocking work has to wrap it — and
        // Thread.sleep has already cleared the flag by the time it throws. Measured before
        // the fix: flag true going in, false coming out, and a run that would keep calling
        // the model and running tools after its caller had cancelled it.
        Thread.interrupted();
        Thread.currentThread().interrupt();

        Observations.ran("onToolResult", () -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new IllegalStateException("observer interrupted", e);
            }
        }, (callback, failure) -> { });

        boolean stillInterrupted = Thread.interrupted();
        assertThat(stillInterrupted)
                .as("the interrupt was absorbed with the exception, so cancellation was"
                        + " silently disarmed")
                .isTrue();
    }

    @Test
    void anObserverThatThrowsAfterTheToolRanDoesNotUndoTheTool() {
        // The specific asymmetry that made this worse than an ordinary crash. onToolResult
        // fires after runTool has returned, so by the time it throws the side effect has
        // happened. A run that dies here leaves the action done and unrecorded.
        List<ToolInvocation> entered = new ArrayList<>();

        AgentResult result = agentWith(new Exploding(Callback.ON_TOOL_RESULT), entered,
                callsThenStops()).run(Goal.of("publish something"));

        assertThat(entered)
                .as("the tool should still have run — the observer is not a gate")
                .hasSize(1);
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
    }

    @Test
    void theTwoCallbacksOnTheReusedIdPathAreGuardedToo() {
        // These are the ones a hand-written guard misses: nothing else in the suite drives
        // this path, so a fix that covered the six obvious sites would look complete.
        // A model reusing a tool-call id gets no tool run, and the observer is told about
        // every call anyway — which is where it throws.
        List<ToolInvocation> entered = new ArrayList<>();
        Exploding observer = new Exploding(Callback.ON_TOOL_INVOCATION,
                Callback.ON_TOOL_RESULT);
        SimpleToolRegistry tools = new SimpleToolRegistry().register(new Recorder(entered));
        LlmResponse reusedIds = LlmResponse.of(
                dev.agentkit.core.message.Message.of(dev.agentkit.core.message.Role.ASSISTANT,
                        List.of(
                                dev.agentkit.core.message.ProposedCall.of("dup", "publish",
                                        Map.of("text", "a")),
                                dev.agentkit.core.message.ProposedCall.of("dup", "publish",
                                        Map.of("text", "b")))),
                dev.agentkit.core.llm.LlmStopReason.TOOL_USE,
                dev.agentkit.core.llm.TokenUsage.ZERO);
        Agent agent = new Agent(new FakeLlmClient(reusedIds), tools,
                AgentConfig.builder("m").maxSteps(4).build(), observer);

        AgentResult result = agent.run(Goal.of("publish something"));

        assertThat(entered)
                .as("a reused id must run no tool; if this fails the test is measuring"
                        + " something other than the path it names")
                .isEmpty();
        assertThat(result.stopReason())
                .as("two calls times two callbacks threw on the reused-id path and one of"
                        + " those dispatch sites is unguarded")
                .isEqualTo(StopReason.ERROR);
    }
}
