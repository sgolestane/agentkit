package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.context.BoundedFailureTextEditor;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SimpleToolRegistry;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A durable run whose caller chose no context strategy still has a transcript bound (#243).
 *
 * <h2>The measurement</h2>
 *
 * <p>#151 measured that an unbounded transcript costs a run quadratically, because it is
 * re-sent every turn; #242 gave every in-process {@code Agent} the bound by default and left
 * {@code AgentWorkflowImpl} applying no {@link dev.agentkit.core.context.ContextStrategy} at
 * all — so the runner that lives long enough to accumulate was the one with no ceiling.
 * Counting characters a third party wrote in the transcript this workflow handed the model,
 * through the same one-tool-that-always-throws setup {@code DefaultContextStrategyTest} uses
 * in process:
 *
 * <pre>
 *         before this change            after
 * calls   last       cumulative         last    cumulative
 *     1      4,136        4,136        4,136         4,136
 *     8     33,088      148,896        4,136        45,496
 *    32    132,352    2,183,808        4,136       144,760
 *   256  1,058,816  136,057,856        4,136     1,071,224
 * </pre>
 *
 * <p>The "before" column is character for character the IDENTITY column of the in-process
 * table, which is the point: both runners had the defect and one of them was repaired.
 *
 * <h2>Why these sizes</h2>
 *
 * <p>These tests run at 3, 8 and 32 calls. 256 was measured once, by hand, and is not run
 * here: it costs six seconds and drives roughly a megabyte of conversation through history
 * per turn. #220's own tests wedged CI for 29 minutes by making a real runner throw
 * 200,000-character exceptions, and the size of a message past {@code ToolResult}'s
 * 4,000-character cap changes none of these figures — which is why the thrower here raises
 * 4,100 characters and not 200,000.
 *
 * <h2>What each test is for</h2>
 *
 * <p>Four separable properties, because a repair can have any one without the others: the
 * bound binds; it is a bound rather than a bigger number, which only a second size can tell
 * apart; it costs an ordinary run nothing, which is the whole argument for defaulting a
 * control on; and it is recorded in history as one named change, which is the only thing the
 * {@code Workflow.getVersion} marker actually buys and the only test that can tell the
 * marker from its absence.
 */
class DurableTranscriptBoundTest {

    private static final String TASK_QUEUE = "agentkit-transcript-bound";

    /** Rendered size of one failure: the framework's frame, a fence, and a 4,000-char cut. */
    private static final int ONE_FAILURE_CHARS = 4_136;

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    private static SimpleToolRegistry alwaysFails() {
        return new SimpleToolRegistry().register(
                FunctionTool.builder("flaky", "fails on every call")
                        .handler(invocation -> {
                            throw new IllegalStateException("x".repeat(4_100));
                        })
                        .build());
    }

    /**
     * As {@link #alwaysFails()}, but every failure is a different 4,100 characters.
     *
     * <p>Zero-padded so each one still renders at exactly {@link #ONE_FAILURE_CHARS} — the
     * fence's nonce is a hash and so is fixed width — which is what lets the test below count
     * how many whole failures the budget carries rather than how many characters it kept.
     * Identical failures cannot answer that question: {@code BoundedFailureTextEditor}
     * collapses a repeat whatever the budget is, so a run of the same failure measures 4,136
     * under any budget at all.
     */
    private static SimpleToolRegistry everyFailureDiffers() {
        return new SimpleToolRegistry().register(
                FunctionTool.builder("flaky", "fails on every call, differently")
                        .schema(Map.of("type", "object",
                                "properties", Map.of("n", Map.of("type", "integer"))))
                        .handler(invocation -> {
                            throw new IllegalStateException(
                                    String.format("%04d", Integer.parseInt(
                                            invocation.stringArgument("n")))
                                            + "x".repeat(4_096));
                        })
                        .build());
    }

    /**
     * Runs {@code calls} failing tool calls durably and returns what the model was sent.
     *
     * <p>The transcript is read off the {@link ScriptedLlm}'s own record rather than out of
     * history, so what is measured is the list the activity rebuilt an {@code LlmRequest}
     * from — the thing the bound is supposed to bound.
     */
    private List<LlmRequest> transcript(int calls) {
        ScriptedLlm llm = new ScriptedLlm();
        for (int i = 0; i < calls; i++) {
            llm.then(ScriptedLlm.toolUse("t" + i, "flaky", Map.of()));
        }
        llm.then(ScriptedLlm.text("giving up"));
        SimpleToolRegistry tools = start(llm);
        AgentRunResult result = TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE)
                .run(DurableAgentRun.of(Goal.of("go"), config(calls), tools.advertisedSpecs()));
        assertThat(result.output())
                .as("the run did not get as far as the turn these figures are taken from")
                .isEqualTo("giving up");
        return llm.requests();
    }

    private SimpleToolRegistry start(ScriptedLlm llm) {
        return start(llm, alwaysFails());
    }

    private SimpleToolRegistry start(ScriptedLlm llm, SimpleToolRegistry tools) {
        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(TemporalAgent.dataConverter())
                        .build())
                .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder()
                        .setWorkflowCacheSize(0)
                        .build())
                .build());
        Worker worker = env.newWorker(TASK_QUEUE);
        TemporalAgent.registerUngated(worker, llm, tools);
        env.start();
        return tools;
    }

    private static AgentConfig config(int calls) {
        return AgentConfig.builder("m").maxSteps(calls + 2).build();
    }

    /** Characters in {@code messages} that somebody other than the framework wrote. */
    private static long thirdPartyChars(List<Message> messages) {
        long total = 0;
        for (Message message : messages) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolResultBlock result && result.isError()
                        && result.provenance() != Provenance.FIRST_PARTY
                        && !isPlaceholder(result.content())) {
                    total += result.content().length();
                }
            }
        }
        return total;
    }

    private static boolean isPlaceholder(String content) {
        return content.equals(BoundedFailureTextEditor.REPEATED_PLACEHOLDER)
                || content.equals(BoundedFailureTextEditor.OVER_BUDGET_PLACEHOLDER);
    }

    private static long lastTranscript(List<LlmRequest> requests) {
        return thirdPartyChars(requests.get(requests.size() - 1).messages());
    }

    private static long cumulativeThirdPartyChars(List<LlmRequest> requests) {
        long total = 0;
        for (LlmRequest request : requests) {
            total += thirdPartyChars(request.messages());
        }
        return total;
    }

    @Test
    void aDurableRunWithNoStrategyChosenStillBoundsFailureText() {
        int calls = 8;

        List<LlmRequest> sent = transcript(calls);

        assertThat(lastTranscript(sent))
                .as("a durable run had no transcript bound at all, which measured 33,088"
                        + " characters here before this change")
                .isLessThanOrEqualTo(BoundedFailureTextEditor.DEFAULT_MAX_TOTAL_FAILURE_CHARS);
        assertThat(lastTranscript(sent)).isEqualTo(ONE_FAILURE_CHARS);
        assertThat(cumulativeThirdPartyChars(sent))
                .as("the transcript is re-sent every turn, so what an unbounded one costs is"
                        + " the sum over every request and not the size of the last; that sum"
                        + " measured 148,896 before this change")
                .isLessThan(148_896L / 2);
    }

    @Test
    void theBoundHoldsAsTheRunGetsLongerRatherThanGrowingWithIt() {
        // The property, as against the single figure above. Four times the failures cost the
        // unbounded transcript four times as much per request — 33,088 and 132,352 measured
        // here before this change — and, since it is re-sent every turn, sixteen times as
        // much in total. A repair that raised the budget rather than applying one would pass
        // the test above and fail this.
        long shortRun = lastTranscript(transcript(8));
        env.close();
        env = null;
        long longRun = lastTranscript(transcript(32));

        assertThat(longRun)
                .as("the bound grew with the run, which is not a bound")
                .isEqualTo(shortRun);
        assertThat(longRun)
                .isLessThanOrEqualTo(BoundedFailureTextEditor.DEFAULT_MAX_TOTAL_FAILURE_CHARS);
    }

    @Test
    void anOrdinaryDurableRunIsSentWhatItWouldHaveBeenSentBefore() {
        // The whole argument for defaulting a control on, and the test that fails for the
        // tempting stronger repair: collapsing repeats unconditionally, which #220 measured
        // and rejected because it spends legible error strings against a budget it is
        // nowhere near. Three failures is 12,408 characters, inside the 16,000 budget, and
        // BoundedFailureTextEditor.edit returns the same list instance rather than a
        // rewritten one — so a durable caller reading their own history back sees exactly
        // what they would have seen with no strategy applied at all.
        int calls = 3;

        List<LlmRequest> sent = transcript(calls);

        assertThat(lastTranscript(sent))
                .as("three ordinary failures were rewritten by a default nobody asked for")
                .isEqualTo(3L * ONE_FAILURE_CHARS);
        for (int i = 0; i < sent.size(); i++) {
            assertThat(sent.get(i).messages())
                    .as("request %d carried framework-written text in place of a failure that"
                            + " was inside the budget", i)
                    .allSatisfy(message -> assertThat(message.content()).noneSatisfy(
                            block -> assertThat(block).isInstanceOfSatisfying(
                                    ToolResultBlock.class,
                                    r -> assertThat(isPlaceholder(r.content())).isTrue())));
        }
    }

    @Test
    void theDurablePathBoundsToTheSameNumberTheInProcessDefaultDoes() {
        // Which budget, as against whether there is one. Every other test here runs the same
        // failure over and over, where the editor's collapse makes the answer 4,136 under any
        // budget at all — so a repair that applied a strategy with a different ceiling would
        // pass them. Eight distinct full-length failures is 33,088 characters, and 16,000
        // carries three of them whole, which is the arithmetic
        // BoundedFailureTextEditor.DEFAULT_MAX_TOTAL_FAILURE_CHARS is chosen by. Doubling the
        // budget to 32,000 makes this seven.
        int calls = 8;
        ScriptedLlm llm = new ScriptedLlm();
        for (int i = 0; i < calls; i++) {
            llm.then(ScriptedLlm.toolUse("t" + i, "flaky", Map.of("n", i)));
        }
        llm.then(ScriptedLlm.text("giving up"));
        SimpleToolRegistry tools = start(llm, everyFailureDiffers());

        AgentRunResult result = TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE)
                .run(DurableAgentRun.of(Goal.of("go"), config(calls), tools.advertisedSpecs()));

        assertThat(result.output()).isEqualTo("giving up");
        assertThat(lastTranscript(llm.requests()))
                .as("the durable path is bounded, but not to the number the in-process"
                        + " default is bounded to")
                .isEqualTo(3L * ONE_FAILURE_CHARS);
        assertThat(3L * ONE_FAILURE_CHARS)
                .as("three whole failures stopped fitting the budget they were sized against")
                .isLessThanOrEqualTo(BoundedFailureTextEditor.DEFAULT_MAX_TOTAL_FAILURE_CHARS);
    }

    @Test
    void theChangeIsRecordedInHistoryAsOneNamedMarker() {
        // What Workflow.getVersion actually buys, and the only test that can tell the marker
        // from its absence: the edit is a pure function of the message list, so a replay
        // reaches the same answer with or without it (see AgentWorkflowImpl.TRANSCRIPT_BOUNDED).
        // What the marker gives is a named change in history — an operator can see which runs
        // took it, and a revert has a version to pin.
        //
        // Exactly one, across a nine-turn run: getVersion is called inside the loop, beside
        // the branch it decides, and Temporal records a change id once per execution however
        // many times it is asked.
        ScriptedLlm llm = new ScriptedLlm();
        for (int i = 0; i < 8; i++) {
            llm.then(ScriptedLlm.toolUse("t" + i, "flaky", Map.of()));
        }
        llm.then(ScriptedLlm.text("giving up"));
        SimpleToolRegistry tools = start(llm);

        AgentWorkflow stub = env.getWorkflowClient().newWorkflowStub(AgentWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
        WorkflowExecution execution = io.temporal.client.WorkflowClient.start(stub::run,
                DurableAgentRun.of(Goal.of("go"), config(8), tools.advertisedSpecs()));
        WorkflowStub.fromTyped(stub).getResult(AgentRunResult.class);

        List<HistoryEvent> markers = env.getWorkflowExecutionHistory(execution).getHistory()
                .getEventsList().stream()
                .filter(HistoryEvent::hasMarkerRecordedEventAttributes)
                .filter(event -> event.getMarkerRecordedEventAttributes()
                        .getMarkerName().equals("Version"))
                .toList();

        assertThat(markers)
                .as("a nine-turn run recorded no version marker, or one per turn")
                .hasSize(1);
        // Against the literals, not against the constants. Both are wire identifiers: the
        // change id is what Temporal matches a run's recorded marker on, so renaming the
        // constant silently puts every run already carrying the old one back on
        // DEFAULT_VERSION — the exact revert this marker exists to prevent — and the version
        // number is what an operator pins a revert to. An assertion naming the constants
        // moves with them and would have caught neither; a mutation pass found that, which
        // is what a mutation pass is for.
        assertThat(detailOf(markers.get(0), "changeId"))
                .isEqualTo("\"transcript-bounded-by-default\"");
        assertThat(detailOf(markers.get(0), "version")).isEqualTo("1");
        assertThat(AgentWorkflowImpl.TRANSCRIPT_BOUNDED_V1)
                .as("the constant the branch reads and the version history records drifted"
                        + " apart")
                .isEqualTo(1);
    }

    /**
     * One detail out of a {@code Version} marker, as the raw JSON Temporal recorded.
     *
     * <p>Read off the payload rather than through the data converter, because the marker's
     * details are the SDK's own and not anything this repository configures — decoding them
     * with the agent's converter would assert against a spelling the SDK is free to change.
     * The quotes are left on for the same reason: they are what is in history.
     */
    private static String detailOf(HistoryEvent marker, String key) {
        return marker.getMarkerRecordedEventAttributes().getDetailsMap()
                .get(key).getPayloads(0).getData().toStringUtf8();
    }
}
