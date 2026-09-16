package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.reliability.BudgetLlmClient;
import dev.agentkit.core.reliability.ModelPricing;
import dev.agentkit.core.reliability.RetryPolicy;
import dev.agentkit.core.reliability.RetryingLlmClient;
import dev.agentkit.core.reliability.TokenBudget;
import dev.agentkit.core.reliability.UsageMeter;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.ToolSpec;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the durable agent loop in Temporal's in-memory test environment (no
 * external server): a simple completion, a tool round-trip through the activity
 * boundary, step bounding, refusal, and — the durability payoff — replay after a
 * transient activity failure without re-executing completed activities.
 */
class AgentWorkflowTest {

    private static final String TASK_QUEUE = "agentkit-test";

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    private void start(LlmClient llm, ToolRegistry tools) {
        Worker worker = newEnvAndWorker();
        TemporalAgent.registerUngated(worker, llm, tools);
        env.start();
    }

    /** As {@link #start}, but with a hand-built tool activity (to script activity-level failures). */
    private void startWithToolActivities(LlmClient llm, ToolActivities toolActivities) {
        startWithToolActivities(llm, toolActivities, true);
    }

    /**
     * As {@link #startWithToolActivities(LlmClient, ToolActivities)}, with control over
     * time skipping. A test that races a real-time event (cancelling a blocked activity)
     * must disable it, or the environment can skip straight past the activity's
     * start-to-close timeout before the event lands.
     */
    private void startWithToolActivities(LlmClient llm, ToolActivities toolActivities,
                                         boolean timeSkipping) {
        // Goes through TemporalAgent.register too, so these tests cannot drift from
        // the wiring the public entry point uses.
        TemporalAgent.register(newEnvAndWorker(timeSkipping), llm, toolActivities);
        env.start();
    }

    /** Creates {@link #env} and a worker on the shared task queue, without registering. */
    private Worker newEnvAndWorker() {
        return newEnvAndWorker(true);
    }

    private Worker newEnvAndWorker(boolean timeSkipping) {
        // Disable the sticky workflow cache so every workflow task replays from
        // history. This makes the memoization assertions meaningful: if a completed
        // activity were re-executed on replay, its invocation count would balloon.
        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(TemporalAgent.dataConverter())
                        .build())
                .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder()
                        .setWorkflowCacheSize(0)
                        .build())
                .setUseTimeskipping(timeSkipping)
                .build());
        return env.newWorker(TASK_QUEUE);
    }

    private AgentRunResult run(DurableAgentRun run) {
        return TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE).run(run);
    }

    private static AgentConfig config(int maxSteps) {
        return AgentConfig.builder("m").maxSteps(maxSteps).build();
    }

    private static FunctionTool echoTool(AtomicInteger counter) {
        return FunctionTool.builder("echo", "Echoes its text argument")
                .schema(Map.of("type", "object",
                        "properties", Map.of("text", Map.of("type", "string")),
                        "required", List.of("text")))
                .handler(inv -> {
                    counter.incrementAndGet();
                    return ToolResult.ok("echoed:" + inv.stringArgument("text"));
                })
                .build();
    }

    @Test
    void completesASimpleTextRun() {
        ScriptedLlm llm = new ScriptedLlm().then(ScriptedLlm.text("the answer"));
        start(llm, new SimpleToolRegistry());

        AgentRunResult result = run(DurableAgentRun.of(Goal.of("do it"), config(3)));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("the answer");
        assertThat(result.steps()).isEqualTo(1);
        assertThat(llm.callCount()).isEqualTo(1);
    }

    @Test
    void executesAToolThroughAnActivityThenCompletes() {
        AtomicInteger toolCalls = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(echoTool(toolCalls));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "echo", Map.of("text", "hi")))
                .then(ScriptedLlm.text("done"));
        start(llm, tools);

        List<ToolSpec> specs = tools.advertisedSpecs();
        AgentRunResult result = run(DurableAgentRun.of(Goal.of("use the tool"), config(5), specs));

        assertThat(result.output()).isEqualTo("done");
        assertThat(result.steps()).isEqualTo(2);
        assertThat(toolCalls.get()).isEqualTo(1);
    }

    @Test
    void replaysAfterTransientLlmFailureWithoutRerunningCompletedActivities() {
        AtomicInteger toolCalls = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(echoTool(toolCalls));
        // Turn 1: tool_use (succeeds, memoized). Tool runs (succeeds, memoized).
        // Turn 2: the LLM activity fails once, then succeeds on Temporal's retry.
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "echo", Map.of("text", "hi")))
                .fail()
                .then(ScriptedLlm.text("done"));
        start(llm, tools);

        AgentRunResult result = run(
                DurableAgentRun.of(Goal.of("do it"), config(5), tools.advertisedSpecs()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("done");
        // With the sticky cache disabled (see start()), each workflow task replays
        // from history. 3 generate calls = turn1 (once) + turn2 (fail + retry); if the
        // completed turn-1 LLM activity were re-run on those replays the count would be
        // far higher. Likewise the tool ran exactly once despite the later retry+replay.
        assertThat(llm.callCount()).isEqualTo(3);
        assertThat(toolCalls.get()).isEqualTo(1);
    }

    @Test
    void aggregatesTokenUsageAcrossTurns() {
        AtomicInteger toolCalls = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(echoTool(toolCalls));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUseWithUsage("t1", "echo", Map.of("text", "hi"),
                        new TokenUsage(10, 5)))
                .then(ScriptedLlm.textWithUsage("done", new TokenUsage(3, 2)));
        start(llm, tools);

        AgentRunResult result = run(
                DurableAgentRun.of(Goal.of("g"), config(5), tools.advertisedSpecs()));

        assertThat(result.usage()).isEqualTo(new TokenUsage(13, 7));
    }

    @Test
    void maxTokensStopsWithOutputTruncated() {
        ScriptedLlm llm = new ScriptedLlm().then(ScriptedLlm.maxTokens("truncated..."));
        start(llm, new SimpleToolRegistry());

        AgentRunResult result = run(DurableAgentRun.of(Goal.of("g"), config(3)));

        assertThat(result.stopReason()).isEqualTo(StopReason.OUTPUT_TRUNCATED);
        assertThat(result.output()).isEqualTo("truncated...");
    }

    @Test
    void pauseStopsWithPaused() {
        ScriptedLlm llm = new ScriptedLlm().then(ScriptedLlm.pause("waiting"));
        start(llm, new SimpleToolRegistry());

        AgentRunResult result = run(DurableAgentRun.of(Goal.of("g"), config(3)));

        assertThat(result.stopReason()).isEqualTo(StopReason.PAUSED);
    }

    @Test
    void toolErrorResultFlowsBackAndTheLoopContinues() {
        AtomicInteger calls = new AtomicInteger();
        FunctionTool failing = FunctionTool.builder("boom", "always fails")
                .handler(inv -> {
                    calls.incrementAndGet();
                    return ToolResult.error("kaboom");
                })
                .build();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(failing);
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "boom", Map.of()))
                .then(ScriptedLlm.text("recovered"));
        start(llm, tools);

        AgentRunResult result = run(
                DurableAgentRun.of(Goal.of("g"), config(5), tools.advertisedSpecs()));

        assertThat(result.output()).isEqualTo("recovered");
        assertThat(calls.get()).isEqualTo(1); // the failing tool ran, run continued
    }

    @Test
    void unknownToolBecomesAnErrorResultNotAFailure() {
        // The model asks for a tool that isn't registered; the activity returns an
        // error result the model reacts to, rather than crashing the run.
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "ghost", Map.of()))
                .then(ScriptedLlm.text("handled"));
        start(llm, new SimpleToolRegistry());

        AgentRunResult result = run(DurableAgentRun.of(Goal.of("g"), config(5)));

        assertThat(result.output()).isEqualTo("handled");
    }

    @Test
    void runsMultipleToolUsesInOneTurn() {
        AtomicInteger calls = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(echoTool(calls));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.multiProposedCall(List.of(
                        ProposedCall.of("t1", "echo", Map.of("text", "a")),
                        ProposedCall.of("t2", "echo", Map.of("text", "b")))))
                .then(ScriptedLlm.text("both done"));
        start(llm, tools);

        AgentRunResult result = run(
                DurableAgentRun.of(Goal.of("g"), config(5), tools.advertisedSpecs()));

        assertThat(result.output()).isEqualTo("both done");
        assertThat(calls.get()).isEqualTo(2); // both tool_use blocks executed
    }

    @Test
    void deliversGoalParametersSystemPromptAndOptionsToTheModel() {
        ScriptedLlm llm = new ScriptedLlm().then(ScriptedLlm.text("ok"));
        start(llm, new SimpleToolRegistry());

        Goal goal = new Goal("summarize", Map.of("docId", "42"));
        var cfg = AgentConfig.builder("m").maxSteps(2)
                .systemPrompt("You are terse.").option("effort", "high").build();
        run(new DurableAgentRun(goal, cfg, List.of(), DurableAgentOptions.defaults(), null));

        var request = llm.requests().get(0);
        assertThat(request.system()).get(as(STRING)).startsWith("You are terse.");
        assertThat(request.options()).containsEntry("effort", "high");
        // The rendered goal (description + parameters) reached the model.
        assertThat(request.messages().get(0).text()).contains("summarize").contains("docId: 42");
    }

    @Test
    void theDurablePathExplainsFencedContentJustAsTheInProcessLoopDoes() {
        // The same AgentConfig used to produce a system prompt with the clause in-process
        // and without it here, while every fencing collaborator kept emitting fences —
        // which is the "a fence the prompt never explains is decoration" failure, shipped
        // on one of the two runners.
        ScriptedLlm llm = new ScriptedLlm().then(ScriptedLlm.text("ok"));
        start(llm, new SimpleToolRegistry());

        run(new DurableAgentRun(Goal.of("go"),
                AgentConfig.builder("m").maxSteps(2).systemPrompt("You are terse.").build(),
                List.of(), DurableAgentOptions.defaults(), null));

        assertThat(llm.requests().get(0).system()).get(as(STRING))
                .startsWith("You are terse.").contains(Spotlight.INSTRUCTION);
    }

    @Test
    void theDurablePathHonoursTheOptOutJustAsTheInProcessLoopDoes() {
        ScriptedLlm llm = new ScriptedLlm().then(ScriptedLlm.text("ok"));
        start(llm, new SimpleToolRegistry());

        run(new DurableAgentRun(Goal.of("go"),
                AgentConfig.builder("m").maxSteps(2).systemPrompt("You are terse.")
                        .explainFencedContent(false).build(),
                List.of(), DurableAgentOptions.defaults(), null));

        assertThat(llm.requests().get(0).system()).get(as(STRING))
                .isEqualTo("You are terse.").doesNotContain(Spotlight.INSTRUCTION);
    }

    @Test
    void aRunStartedBeforeTheFlagExistedStillGetsTheClauseOnReplay() {
        // A durable run outlives the code that started it. The flag is boxed and absent
        // means the default, because a primitive would have deserialized to false and
        // dropped the clause on replay while the activities went on fencing — the "fence
        // nobody explains" failure, arriving with no user action.
        AgentConfig fromOldPayload = fromJson(
                "{\"model\":\"m\",\"systemPrompt\":\"You are terse.\",\"maxSteps\":2,"
                + "\"maxTokens\":4096,\"options\":{}}");

        assertThat(fromOldPayload.explainsFencedContent()).isTrue();

        ScriptedLlm llm = new ScriptedLlm().then(ScriptedLlm.text("ok"));
        start(llm, new SimpleToolRegistry());
        run(new DurableAgentRun(Goal.of("go"), fromOldPayload,
                List.of(), DurableAgentOptions.defaults(), null));

        assertThat(llm.requests().get(0).system()).get(as(STRING))
                .contains(Spotlight.INSTRUCTION);
    }

    private static AgentConfig fromJson(String json) {
        try {
            return DurableJson.objectMapper().readValue(json, AgentConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void llmExhaustionYieldsAnErrorResultWithPartialProgress() {
        AtomicInteger toolCalls = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(echoTool(toolCalls));
        // Turn 1 succeeds (a tool call); turn 2 fails every attempt.
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "echo", Map.of("text", "hi")))
                .fail().fail();
        start(llm, tools);

        DurableAgentOptions opts = new DurableAgentOptions(60, 2, 60, 3); // llmMaxAttempts=2
        AgentRunResult result = run(new DurableAgentRun(
                Goal.of("g"), config(5), tools.advertisedSpecs(), opts, null));

        assertThat(result.stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(result.errorMessage()).isNotEmpty();
        assertThat(result.steps()).isEqualTo(1); // the first successful turn counted
        assertThat(toolCalls.get()).isEqualTo(1);
    }

    @Test
    void stopsAtMaxSteps() {
        AtomicInteger toolCalls = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(echoTool(toolCalls));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "echo", Map.of("text", "a")))
                .then(ScriptedLlm.toolUse("t2", "echo", Map.of("text", "b")));
        start(llm, tools);

        AgentRunResult result = run(
                DurableAgentRun.of(Goal.of("loop"), config(2), tools.advertisedSpecs()));

        assertThat(result.stopReason()).isEqualTo(StopReason.MAX_STEPS);
        assertThat(result.steps()).isEqualTo(2);
        assertThat(toolCalls.get()).isEqualTo(2);
    }

    @Test
    void refusalStopsTheRun() {
        ScriptedLlm llm = new ScriptedLlm().then(ScriptedLlm.refusal("cannot help"));
        start(llm, new SimpleToolRegistry());

        AgentRunResult result = run(DurableAgentRun.of(Goal.of("do it"), config(3)));

        assertThat(result.stopReason()).isEqualTo(StopReason.REFUSED);
        assertThat(result.output()).isEqualTo("cannot help");
    }

    // --- workflow-enforced token budget -------------------------------------

    @Test
    void anExhaustedBudgetStopsTheRunWithBudgetExhausted() {
        AtomicInteger toolCalls = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(echoTool(toolCalls));
        // Turn 1 spends the whole 100-token budget and asks for a tool.
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUseWithUsage("t1", "echo", Map.of("text", "a"),
                        new TokenUsage(50, 50)))
                .then(ScriptedLlm.text("never reached"));
        start(llm, tools);

        AgentRunResult result = run(DurableAgentRun.of(Goal.of("g"), config(5),
                tools.advertisedSpecs()).withBudget(TokenBudget.ofTotalTokens(100)));

        // Mirrors BudgetLlmClient: the turn that reaches the cap completes, the NEXT
        // one is refused — so one step ran and a second model call never happened.
        assertThat(result.stopReason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(result.steps()).isEqualTo(1);
        assertThat(result.usage().totalTokens()).isEqualTo(100);
        assertThat(llm.callCount()).isEqualTo(1);
        // The exhausting turn's tool still ran before the refusal, as in-process.
        assertThat(toolCalls.get()).isEqualTo(1);
        assertThat(result.errorMessage()).isEmpty(); // a budget stop is not an error
    }

    @Test
    void aCostCapAlsoStopsADurableRun() {
        // Exercises the pricing-backed path end-to-end: ModelPricing must survive the
        // workflow boundary and still price the spend inside the loop.
        AtomicInteger toolCalls = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(echoTool(toolCalls));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUseWithUsage("t1", "echo", Map.of("text", "a"),
                        new TokenUsage(1_000_000, 0)))          // $5.00 at the pricing below
                .then(ScriptedLlm.text("never reached"));
        start(llm, tools);

        AgentRunResult result = run(DurableAgentRun.of(Goal.of("g"), config(5), tools.advertisedSpecs())
                .withBudget(TokenBudget.ofCostUsd(2.50, ModelPricing.of(5.00, 25.00))));

        assertThat(result.stopReason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(result.steps()).isEqualTo(1);
        assertThat(llm.callCount()).isEqualTo(1);
    }

    @Test
    void aBudgetWithHeadroomDoesNotStopTheRun() {
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.textWithUsage("done", new TokenUsage(5, 5)));
        start(llm, new SimpleToolRegistry());

        AgentRunResult result = run(DurableAgentRun.of(Goal.of("g"), config(3))
                .withBudget(TokenBudget.ofTotalTokens(1000)));

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(result.output()).isEqualTo("done");
    }

    @Test
    void theBudgetIsPerRunNotSharedAcrossRunsOnTheSameWorker() {
        // Regression: a BudgetLlmClient registered on the worker would carry run 1's
        // spend into run 2, so run 2 would die at step 0 having made no model call.
        // A workflow-enforced budget gives every run its own FULL allowance, so run 2
        // must get two turns of its own before its own budget refuses a third.
        AtomicInteger toolCalls = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(echoTool(toolCalls));
        ScriptedLlm llm = new ScriptedLlm()
                // run 1: one turn spends the whole cap.
                .then(ScriptedLlm.textWithUsage("first", new TokenUsage(50, 50)))
                // run 2: two turns, the second of which crosses its own cap.
                .then(ScriptedLlm.toolUseWithUsage("t1", "echo", Map.of("text", "a"),
                        new TokenUsage(20, 20)))
                .then(ScriptedLlm.toolUseWithUsage("t2", "echo", Map.of("text", "b"),
                        new TokenUsage(40, 40)));
        start(llm, tools);

        AgentRunResult first = run(DurableAgentRun.of(Goal.of("run one"), config(5))
                .withBudget(TokenBudget.ofTotalTokens(100)));
        AgentRunResult second = run(DurableAgentRun.of(Goal.of("run two"), config(5),
                        tools.advertisedSpecs())
                .withBudget(TokenBudget.ofTotalTokens(100)));

        assertThat(first.output()).isEqualTo("first");
        // Run 2 spent its own fresh 100, across two turns, before being refused —
        // proving it received a full allowance rather than inheriting run 1's spend.
        assertThat(second.stopReason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(second.steps()).isEqualTo(2);
        assertThat(second.usage().totalTokens()).isEqualTo(120);
        assertThat(llm.callCount()).isEqualTo(3); // 1 for run 1, 2 for run 2
    }

    @Test
    void aRunWithNoBudgetIsUncapped() {
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.textWithUsage("plenty spent", new TokenUsage(9_000, 9_000)));
        start(llm, new SimpleToolRegistry());

        AgentRunResult result = run(DurableAgentRun.of(Goal.of("g"), config(3)));

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
    }

    @Test
    void maxStepsWinsOverAnExhaustedBudgetAtTheBoundary() {
        // Both loops check `steps < maxSteps` before the budget guard, so a run that
        // exhausts its budget on its final permitted step reports MAX_STEPS. Pinning
        // the precedence so it cannot drift between the two implementations.
        AtomicInteger toolCalls = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(echoTool(toolCalls));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUseWithUsage("t1", "echo", Map.of("text", "a"),
                        new TokenUsage(500, 500)));
        start(llm, tools);

        AgentRunResult result = run(DurableAgentRun.of(Goal.of("g"), config(1),
                        tools.advertisedSpecs())
                .withBudget(TokenBudget.ofTotalTokens(100)));

        assertThat(result.stopReason()).isEqualTo(StopReason.MAX_STEPS);
    }

    @Test
    void registeringABudgetLlmClientIsRejected() {
        // The tally is instance state shared by every workflow the worker serves, so
        // this must fail loudly at wiring time rather than silently poison later runs.
        Worker worker = newEnvAndWorker();
        LlmClient budgeted = new BudgetLlmClient(
                new ScriptedLlm(), TokenBudget.ofTotalTokens(100));

        assertThatThrownBy(() -> TemporalAgent.registerUngated(worker, budgeted, new SimpleToolRegistry()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BudgetLlmClient")
                .hasMessageContaining("withBudget");
    }

    @Test
    void aBudgetLlmClientHiddenInsideDecoratorsIsStillRejected() {
        // The guard's whole value is that it survives decoration. It previously peeled
        // metering layers by name, so a retry or tracing wrapper walked straight past
        // it — and nothing tested that, because every test passed a bare client.
        Worker worker = newEnvAndWorker();
        LlmClient budgeted = new BudgetLlmClient(new ScriptedLlm(), TokenBudget.ofTotalTokens(100));

        for (LlmClient wrapped : List.of(
                new UsageMeter().wrap("agent", budgeted),
                new RetryingLlmClient(budgeted, RetryPolicy.none()),
                new UsageMeter().wrap("agent", new RetryingLlmClient(budgeted, RetryPolicy.none())),
                new RetryingLlmClient(new UsageMeter().wrap("agent", budgeted), RetryPolicy.none()))) {
            assertThatThrownBy(() -> TemporalAgent.registerUngated(worker, wrapped, new SimpleToolRegistry()))
                    .describedAs("guard must see through %s", wrapped.getClass().getSimpleName())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("BudgetLlmClient");
        }
    }

    @Test
    void anUndecoratedClientIsStillAccepted() {
        // The other half: the guard must not start rejecting ordinary wrapped clients.
        Worker worker = newEnvAndWorker();
        LlmClient plain = new RetryingLlmClient(
                new UsageMeter().wrap("agent", new ScriptedLlm()), RetryPolicy.none());

        TemporalAgent.register(worker, plain, new SimpleToolRegistry());
    }

    // --- tool activity failures ---------------------------------------------

    @Test
    void aFailedToolActivityBecomesAnErrorResultAndTheRunContinues() {
        // ToolActivitiesImpl converts a *thrown tool* into an error result, so this
        // scripts the layer beneath it: an activity that fails outright (a timeout or
        // lost worker looks like this once retries are exhausted). In-process, a tool
        // failure never ends a run — the durable loop must match, rather than losing
        // every completed step.
        AtomicInteger attempts = new AtomicInteger();
        ScriptedToolActivities alwaysFails = invocation -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("tool worker exploded");
        };
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "echo", Map.of("text", "a")))
                .then(ScriptedLlm.text("I could not use the tool, so here is my answer."));
        startWithToolActivities(llm, alwaysFails);

        // toolMaxAttempts=1 so the activity fails without retrying.
        AgentRunResult result = run(new DurableAgentRun(Goal.of("g"), config(5), List.of(),
                new DurableAgentOptions(60, 2, 60, 1), null));

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(result.output()).isEqualTo("I could not use the tool, so here is my answer.");
        assertThat(result.steps()).isEqualTo(2); // the run carried on past the failure
        assertThat(attempts.get()).isEqualTo(1);

        // The model saw the failure as an error tool result it could react to.
        var toolResultMessage = llm.requests().get(1).messages().get(2);
        var block = (dev.agentkit.core.message.ToolResultBlock) toolResultMessage.content().get(0);
        assertThat(block.isError()).isTrue();
        assertThat(block.content()).contains("echo").contains("exploded");
    }

    @Test
    void oneFailingToolDoesNotSinkTheOthersInTheSameTurn() {
        ScriptedToolActivities failsOnlyB = invocation -> {
            if (invocation.name().equals("b")) {
                throw new IllegalStateException("only b is broken");
            }
            return ToolResult.ok("ok:" + invocation.name());
        };
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.multiProposedCall(List.of(
                        ProposedCall.of("t1", "a", Map.of()),
                        ProposedCall.of("t2", "b", Map.of()),
                        ProposedCall.of("t3", "c", Map.of()))))
                .then(ScriptedLlm.text("done"));
        startWithToolActivities(llm, failsOnlyB);

        AgentRunResult result = run(new DurableAgentRun(Goal.of("g"), config(5), List.of(),
                new DurableAgentOptions(60, 2, 60, 1), null));

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        // All three results came back, correlated to the right tool_use ids (the
        // provider rejects a mismatch), with only the middle one flagged as an error.
        var blocks = llm.requests().get(1).messages().get(2).content();
        assertThat(blocks).hasSize(3);
        assertThat(blocks).extracting(b -> ((dev.agentkit.core.message.ToolResultBlock) b).toolUseId())
                .containsExactly("t1", "t2", "t3");
        assertThat(blocks).extracting(b -> ((dev.agentkit.core.message.ToolResultBlock) b).isError())
                .containsExactly(false, true, false);
        assertThat(((dev.agentkit.core.message.ToolResultBlock) blocks.get(1)).content())
                .contains("b").contains("only b is broken");
    }

    @Test
    void aToolActivityTimeoutTellsTheModelTheOutcomeIsUnknown() {
        // Activity delivery is at-least-once, so a timed-out tool may in fact have run
        // on a worker that died before reporting. Reporting it as a plain failure would
        // invite the model to re-drive a non-idempotent side effect.
        CountDownLatch release = new CountDownLatch(1);
        ScriptedToolActivities tooSlow = invocation -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.ok("too late");
        };
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "slow", Map.of()))
                .then(ScriptedLlm.text("noted"));
        startWithToolActivities(llm, tooSlow);

        try {
            // 1s start-to-close, single attempt: the activity cannot finish in time.
            AgentRunResult result = run(new DurableAgentRun(Goal.of("g"), config(5), List.of(),
                    new DurableAgentOptions(60, 2, 1, 1), null));

            assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
            var block = (dev.agentkit.core.message.ToolResultBlock)
                    llm.requests().get(1).messages().get(2).content().get(0);
            assertThat(block.isError()).isTrue();
            // No SDK jargon (e.g. "timeoutType=TIMEOUT_TYPE_START_TO_CLOSE"), and the
            // wording must not assert the tool did not run.
            assertThat(block.content()).doesNotContain("TIMEOUT_TYPE").doesNotContain("timeoutType");
            assertThat(block.content()).contains("slow").contains("unknown");
        } finally {
            release.countDown();
        }
    }

    @Test
    void aToolWorkerThatIsAlwaysDownEndsTheRunInErrorRatherThanLookingSuccessful() {
        // Tolerating tool failures must not let an unregistered/misconfigured/dead tool
        // tier report COMPLETED: without this, the model answers around the outage and
        // the caller cannot tell the run from a healthy one.
        ScriptedToolActivities alwaysFails = invocation -> {
            throw new IllegalStateException("tool tier is down");
        };
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "a", Map.of()))
                .then(ScriptedLlm.toolUse("t2", "a", Map.of()))
                .then(ScriptedLlm.text("unreachable"));
        startWithToolActivities(llm, alwaysFails);

        AgentRunResult result = run(new DurableAgentRun(Goal.of("g"), config(9), List.of(),
                new DurableAgentOptions(60, 2, 60, 1), null));

        assertThat(result.stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(result.errorMessage()).contains("consecutive").contains("tool worker");
        assertThat(result.steps()).isEqualTo(2);     // stopped early, not run to maxSteps
        assertThat(llm.callCount()).isEqualTo(2);    // and stopped burning model calls
    }

    @Test
    void aRecoveringToolResetsTheDeadWorkerCount() {
        // One bad turn is a blip: if the next turn's tool succeeds, the run continues
        // normally rather than counting toward the give-up threshold.
        AtomicInteger calls = new AtomicInteger();
        ScriptedToolActivities failsFirstCall = invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("one-off blip");
            }
            return ToolResult.ok("fine");
        };
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "a", Map.of()))
                .then(ScriptedLlm.toolUse("t2", "a", Map.of()))
                .then(ScriptedLlm.text("all good"));
        startWithToolActivities(llm, failsFirstCall);

        AgentRunResult result = run(new DurableAgentRun(Goal.of("g"), config(9), List.of(),
                new DurableAgentOptions(60, 2, 60, 1), null));

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(result.output()).isEqualTo("all good");
    }

    @Test
    void cancellingARunUnwindsInsteadOfBeingAbsorbed() {
        // End-to-end: the handlers catch ActivityFailure broadly, and Temporal reports a
        // cancelled in-flight activity exactly that way.
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScriptedToolActivities blocks = invocation -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.ok("done");
        };
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "blocking", Map.of()))
                .then(ScriptedLlm.text("should never be reached"));
        // No time skipping: the cancel has to land while the activity is genuinely
        // in flight, not after the env has fast-forwarded past its 60s timeout.
        startWithToolActivities(llm, blocks, false);

        AgentWorkflow stub = TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE);
        try {
            WorkflowClient.start(stub::run, DurableAgentRun.of(Goal.of("g"), config(5)));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            WorkflowStub.fromTyped(stub).cancel();

            // Bounded: an unbounded getResult would hang the build rather than fail if
            // cancellation ever stopped propagating.
            assertThatThrownBy(() -> WorkflowStub.fromTyped(stub)
                    .getResult(30, TimeUnit.SECONDS, AgentRunResult.class))
                    .isInstanceOf(WorkflowFailedException.class)
                    .hasRootCauseInstanceOf(CanceledFailure.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            release.countDown();
        }
    }

    @Test
    void mentionsCancellationWalksTheCauseChain() {
        assertThat(AgentWorkflowImpl.mentionsCancellation(new CanceledFailure("c"))).isTrue();
        assertThat(AgentWorkflowImpl.mentionsCancellation(
                new IllegalStateException(new CanceledFailure("c")))).isTrue();
        assertThat(AgentWorkflowImpl.mentionsCancellation(
                new IllegalStateException("provider down"))).isFalse();
        assertThat(AgentWorkflowImpl.mentionsCancellation(
                new IllegalStateException(new IllegalStateException("nested")))).isFalse();
    }

    @Test
    void constructingTheLlmActivityWithABudgetClientIsAlsoRejected() {
        // The activity implementation is public, so registering it directly would
        // otherwise bypass the guard in register(...) and reinstate the shared tally.
        LlmClient budgeted = new BudgetLlmClient(new ScriptedLlm(), TokenBudget.ofTotalTokens(100));

        assertThatThrownBy(() -> new LlmActivitiesImpl(budgeted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BudgetLlmClient");
    }
}
