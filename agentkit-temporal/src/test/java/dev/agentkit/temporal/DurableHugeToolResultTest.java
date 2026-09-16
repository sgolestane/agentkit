package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.ToolSpec;
import dev.agentkit.core.util.Cut;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A tool result too large for the wire used to stall a durable run forever (#137).
 *
 * <h2>The failure these tests are about</h2>
 *
 * <p>An activity result is written by the worker and read back by the <em>workflow</em>.
 * Nothing bounded {@code ToolResult.content} anywhere on that path, and the two halves have
 * different limits: the write always succeeded, and above Jackson's 20,000,000-character
 * ceiling the read did not. A failed read fails the workflow <em>task</em>, which Temporal
 * retries indefinitely — so the run neither finished nor failed. Measured against the code
 * before the fix, with one tool returning one string:
 *
 * <pre>
 *  8MB content | tool executions=1 | stopReason=COMPLETED
 * 21MB content | tool executions=1 | run never resolves (60s), workflow task attempt=2,3,4…
 * 32MB content | tool executions=1 | run never resolves (60s)
 * </pre>
 *
 * <p>with, on the stalling attempts, {@code DataConverterException: String value length
 * (20051112) exceeds the maximum allowed (20000000, from
 * StreamReadConstraints.getMaxStringLength()) (through reference chain:
 * dev.agentkit.temporal.ToolOutcome["content"])}.
 *
 * <h2>Why the end-to-end tests carry their own deadline</h2>
 *
 * <p>A regression here does not fail a test, it hangs one — the whole point of the bug is
 * that nothing ever resolves. {@link #completes} runs the workflow on another thread and
 * gives it a bounded wait, so the old behaviour shows up as a failed assertion naming the
 * stall rather than as a build that has to be killed.
 */
class DurableHugeToolResultTest {

    /**
     * Bigger than the 20,000,000-character ceiling that produced the stall, and bigger than
     * the 21 MB the issue measured, so the payload is past the cliff rather than near it.
     */
    private static final int PAST_THE_CLIFF = 21 * 1024 * 1024;

    private static final String TASK_QUEUE = "agentkit-huge-result-test";

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    // --- end to end, through Temporal ---------------------------------------

    @Test
    void aResultPastJacksonsCeilingCompletesTheRunInsteadOfStallingIt() {
        AtomicInteger executions = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(returns(executions, "x".repeat(PAST_THE_CLIFF)));

        String verdict = completes(callsThenStops(), tools);

        assertThat(verdict)
                .as("a %d-character tool result stalled the run: the activity wrote it, the"
                        + " workflow could not read it back, and Temporal retried that"
                        + " workflow task forever", PAST_THE_CLIFF)
                .isEqualTo("COMPLETED");
        assertThat(executions.get())
                .as("the tool body ran more than once for one model-proposed call")
                .isEqualTo(1);
    }

    @Test
    void theModelIsToldTheAnswerWasCut() {
        AtomicInteger executions = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(returns(executions, "x".repeat(PAST_THE_CLIFF)));
        ScriptedLlm llm = callsThenStops();

        assertThat(completes(llm, tools)).isEqualTo("COMPLETED");

        // The second request is the one carrying the tool result back.
        String toldTheModel = toolResultsIn(llm.requests().get(1));
        assertThat(toldTheModel)
                .as("text that simply stops reads to a model as text that ended, so a cut"
                        + " document looks like a whole one")
                .endsWith(Cut.MARKER);
        assertThat(toldTheModel.length())
                .isEqualTo(ToolActivitiesImpl.MAX_CONTENT_CHARS + Cut.MARKER.length());
    }

    // --- the boundary, and both readers -------------------------------------

    @Test
    void aResultExactlyAtTheBoundIsNotTouched() {
        String exact = "y".repeat(ToolActivitiesImpl.MAX_CONTENT_CHARS);

        ToolOutcome outcome = activityFor(exact).executeTool(call());

        assertThat(outcome.content())
                .as("an off-by-one here spends a marker, and fifteen characters of somebody's"
                        + " answer, on a result that already fit")
                .isEqualTo(exact);
        assertThat(outcome.content()).doesNotContain(Cut.MARKER);
    }

    @Test
    void aResultOneCharacterOverTheBoundIsCut() {
        String oneOver = "y".repeat(ToolActivitiesImpl.MAX_CONTENT_CHARS + 1);

        ToolOutcome outcome = activityFor(oneOver).executeTool(call());

        assertThat(outcome.content()).endsWith(Cut.MARKER);
        assertThat(outcome.content().length())
                .isEqualTo(ToolActivitiesImpl.MAX_CONTENT_CHARS + Cut.MARKER.length());
    }

    @Test
    void theOperatorIsToldWhatWasCutAndFromWhere() {
        // The in-band marker is a hint to the model and nothing more: any body can print
        // the same characters, so its presence proves nothing and its absence proves
        // nothing. Synthesizers.fenceOf's javadoc states the rule — "a cap nobody is told
        // about reads as 'everything was carried'" — and this is the unforgeable half of
        // it. Asserted on the operator's stream rather than on a flag, because a flag
        // nobody prints is not an operator being told.
        String huge = "y".repeat(ToolActivitiesImpl.MAX_CONTENT_CHARS + 4_321);

        String logged = whatTheOperatorSaw(() -> activityFor(huge).executeTool(call()));

        assertThat(logged)
                .as("the operator has to be able to tell which tool overran, and by how much")
                .contains("read_doc")
                .contains(String.valueOf(ToolActivitiesImpl.MAX_CONTENT_CHARS + 4_321))
                .contains(String.valueOf(ToolActivitiesImpl.MAX_CONTENT_CHARS));
    }

    @Test
    void aResultThatFitsIsNotAnnouncedAsCut() {
        // The other half of the boundary, and the one an off-by-one hides in. Cut.to's own
        // guard returns the text unchanged at exactly the bound, so a `<` where this code
        // says `<=` alters nothing a reader receives — measured, it survives every
        // assertion about content. What it does alter is the operator's stream, which
        // starts announcing a truncation on results that were carried whole. A warning
        // that fires when nothing happened is how an operator learns to stop reading it.
        String exact = "y".repeat(ToolActivitiesImpl.MAX_CONTENT_CHARS);

        String logged = whatTheOperatorSaw(() -> activityFor(exact).executeTool(call()));

        assertThat(logged).doesNotContain("cut to");
    }

    @Test
    void aCutResultKeepsItsErrorFlagAndItsProvenance() {
        // The trust floor is read off this result's provenance one line above the cut
        // (ToolActivitiesImpl.outcomeOf), so a cut that rebuilt the result with default
        // components would stop a third party's page lowering the floor for the rest of the
        // run (#122) — a security control switched off by the size of an answer.
        String huge = "z".repeat(ToolActivitiesImpl.MAX_CONTENT_CHARS + 10);
        SimpleToolRegistry tools = new SimpleToolRegistry().register(
                FunctionTool.builder("read_doc", "Reads a document")
                        .schema(Map.of("type", "object", "properties", Map.of()))
                        .handler(invocation ->
                                ToolResult.from(Provenance.THIRD_PARTY, huge).asError())
                        .build());
        // A real floor needs two distinct policies — TrustFloor refuses one wearing the
        // shape of two — so the tightened one is a second allow-all written separately.
        // What is under test is whether the trigger fires, not what it tightens to.
        dev.agentkit.core.reliability.TrustFloor floor =
                dev.agentkit.core.reliability.TrustFloor.afterThirdParty(
                        dev.agentkit.core.reliability.ToolGate.ALLOW_ALL,
                        (t, i) -> dev.agentkit.core.reliability.GateResult.allow());

        ToolOutcome outcome = new ToolActivitiesImpl(tools, floor).executeTool(call());

        assertThat(outcome.content()).endsWith(Cut.MARKER);
        assertThat(outcome.isError()).as("a cut turned a failure into a success").isTrue();
        assertThat(outcome.provenance())
                .as("a cut relabelled somebody else's words as nobody's")
                .isEqualTo(Provenance.THIRD_PARTY);
        assertThat(outcome.lowered())
                .as("a page long enough to be cut stopped lowering the trust floor")
                .isTrue();
    }

    @Test
    void aCutOutcomeReadsBackExactlyAsItWasRecorded() throws Exception {
        // The invariant the read side has to hold: a replay is a re-read of bytes already
        // in history, so whatever a workflow reads back has to be what was recorded. This
        // is what catches a transform placed on the read side — an NFKC pass, a re-fence, a
        // second cut — that is not the identity on text that has already been through it.
        //
        // What it does NOT catch, said here because the measurement was made: duplicating
        // #137's own cap into ToolOutcome's constructor. Cut.to appends MARKER after the
        // cut point, so re-cutting takes the same first max characters and re-appends the
        // same marker; it is a fixed point, and that mutant passes every test in this
        // class. The reason the cap is not there is the operator's line, which a
        // constructor cannot write usefully — it cannot name the tool, and it would
        // announce one truncation once per workflow task forever.
        //
        // The payload is astral, and the leading ASCII character puts the pairs on odd
        // offsets so the bound lands on a high surrogate. That is the one input where
        // Cut.to is not a fixed point on the first re-application, so it is the strongest
        // input this assertion has.
        String astral = "a" + "\uD834\uDD1E".repeat(ToolActivitiesImpl.MAX_CONTENT_CHARS);
        ToolOutcome recorded = activityFor(astral).executeTool(call());
        com.fasterxml.jackson.databind.ObjectMapper mapper = DurableJson.objectMapper();

        ToolOutcome replayed = mapper.readValue(mapper.writeValueAsString(recorded),
                ToolOutcome.class);

        assertThat(replayed.content())
                .as("a replay is a re-read of the same bytes, so it has to give back the"
                        + " same string — anything transforming content on the read side"
                        + " runs once per workflow task, not once per result")
                .isEqualTo(recorded.content());
        assertThat(replayed.content()).endsWith(Cut.MARKER);
        assertThat(replayed.content().indexOf(Cut.MARKER))
                .as("the marker was cut into a second marker, which is what a read-side cap"
                        + " does to text it cannot split cleanly")
                .isEqualTo(replayed.content().lastIndexOf(Cut.MARKER));
    }

    // --- the divergence, pinned deliberately --------------------------------

    @Test
    void theInProcessRunnerIsDeliberatelyNotBoundedAndThisPinsIt() {
        // #137 chose the runner over the type, and this records the consequence rather than
        // leaving it to be discovered: the same tool hands the model the whole string in
        // process and a cut one durably. The cap is a property of a wire and this runner has
        // none — an oversized result here is refused by the model client, loudly and once.
        // If a later change moves the bound into ToolResult, this test is the one that says
        // so out loud instead of the two runners quietly agreeing by accident.
        String oneOver = "y".repeat(ToolActivitiesImpl.MAX_CONTENT_CHARS + 1);
        AtomicInteger executions = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(returns(executions, oneOver));
        ScriptedLlm llm = callsThenStops();
        Agent agent = new Agent(llm, tools, AgentConfig.builder("m").maxSteps(5).build());

        agent.run(Goal.of("read the document"));

        assertThat(toolResultsIn(llm.requests().get(1)))
                .as("the in-process runner started bounding too, which is a decision and"
                        + " belongs in ToolResult with an argument, not here by accident")
                .hasSize(oneOver.length())
                .doesNotContain(Cut.MARKER);
    }

    // --- harness ------------------------------------------------------------

    /** A tool that records that it ran and returns {@code payload} verbatim. */
    private static FunctionTool returns(AtomicInteger executions, String payload) {
        return FunctionTool.builder("read_doc", "Reads a document")
                .schema(Map.of("type", "object", "properties", Map.of()))
                .handler(invocation -> {
                    executions.incrementAndGet();
                    return ToolResult.ok(payload);
                })
                .build();
    }

    private static ToolInvocation call() {
        return new ToolInvocation("t1", "read_doc", Map.of());
    }

    /** The activity implementation alone, with no Temporal around it. */
    private static ToolActivitiesImpl activityFor(String payload) {
        return new ToolActivitiesImpl(
                new SimpleToolRegistry().register(returns(new AtomicInteger(), payload)));
    }

    private static ScriptedLlm callsThenStops() {
        return new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "read_doc", Map.of()))
                .then(ScriptedLlm.text("done"));
    }

    /**
     * The operator's stream while {@code work} runs.
     *
     * <p>The assumption is about the <em>plumbing</em>, not about the line under test, and
     * the difference is the whole reason this is a method. Assuming on the line itself —
     * "skip unless the log contains ToolActivitiesImpl" — makes the test that proves the
     * operator is told <strong>skip</strong> rather than fail when nobody is told, which is
     * exactly the mutant it exists to kill. Measured: with the cap removed, the operator
     * test reported {@code Skipped: 1} and the build stayed green. A sentinel written
     * through the same logging backend separates "capture does not work here" from "the
     * code said nothing".
     */
    private static String whatTheOperatorSaw(Runnable work) {
        String sentinel = "operator-stream-probe-" + System.nanoTime();
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.err;
        String logged;
        try {
            System.setErr(new java.io.PrintStream(captured, true,
                    java.nio.charset.StandardCharsets.UTF_8));
            org.slf4j.LoggerFactory.getLogger(DurableHugeToolResultTest.class).warn(sentinel);
            work.run();
        } finally {
            System.setErr(original);
            logged = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(logged.contains(sentinel),
                "the test logger did not write to the redirected System.err, so this cannot"
                        + " observe the line the operator would read");
        return logged;
    }

    /** Every tool result block in {@code request}, joined — {@code Message.text} sees none. */
    private static String toolResultsIn(LlmRequest request) {
        return request.messages().stream()
                .flatMap(m -> m.content().stream())
                .filter(block -> block instanceof ToolResultBlock)
                .map(block -> ((ToolResultBlock) block).content())
                .reduce("", String::concat);
    }

    /**
     * Runs the workflow with a deadline and reports its stop reason, or the stall.
     *
     * <p>The deadline is the load-bearing part: the defect under test is a run that never
     * resolves, so a plain blocking call would hang the build instead of failing a test.
     */
    private String completes(LlmClient llm, ToolRegistry tools) {
        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(TemporalAgent.dataConverter())
                        .build())
                .build());
        Worker worker = env.newWorker(TASK_QUEUE);
        TemporalAgent.register(worker, llm, tools);
        env.start();
        List<ToolSpec> specs = ((SimpleToolRegistry) tools).advertisedSpecs();
        CompletableFuture<String> run = CompletableFuture.supplyAsync(() ->
                TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE)
                        .run(DurableAgentRun.of(Goal.of("read the document"),
                                AgentConfig.builder("m").maxSteps(5).build(), specs))
                        .stopReason().name());
        try {
            return run.get(90, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return "STALLED (still retrying the workflow task after 90s)";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            return "THREW " + e.getCause();
        }
    }
}
