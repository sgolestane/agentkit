package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.reliability.Approver;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Whether a {@link ToolGate} actually stops a tool on the durable path (#58).
 *
 * <p>A durable run consulted no gate at all: wiring {@code ToolGates.readOnly()} and then
 * moving the run to Temporal dropped every gate, approvals included, with nothing failing
 * and nothing logged. The wiring looked right and did nothing, which is the worst shape a
 * missing control can take.
 *
 * <p><strong>These tests assert on the tool, not on the gate.</strong> Asking whether the
 * gate returned deny is asking the control whether it worked, and it passes for a runner
 * that denies and then runs the tool anyway — which is exactly the class of bug this
 * repository has shipped twice in-process. Every test here counts what the <em>tool</em>
 * did.
 */
class DurableToolGateTest {

    private static final String TASK_QUEUE = "agentkit-gate-test";

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    // --- the world ---------------------------------------------------------------

    /** A tool that records every invocation it was actually entered with. */
    private static FunctionTool writer(List<ToolInvocation> entered) {
        return FunctionTool.builder("publish", "Publishes text somewhere public")
                .schema(Map.of("type", "object",
                        "properties", Map.of("text", Map.of("type", "string")),
                        "required", List.of("text")))
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> {
                    entered.add(invocation);
                    return ToolResult.ok("published:" + invocation.stringArgument("text"));
                })
                .build();
    }

    private Worker worker() {
        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(TemporalAgent.dataConverter())
                        .build())
                // No sticky cache, so every workflow task replays from history. That is
                // what makes the memoization assertion below mean anything.
                .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder()
                        .setWorkflowCacheSize(0)
                        .build())
                .build());
        return env.newWorker(TASK_QUEUE);
    }

    private AgentRunResult runWith(LlmClient llm, ToolRegistry tools, ToolGate gate) {
        TemporalAgent.register(worker(), llm, tools, gate);
        env.start();
        return TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE)
                .run(DurableAgentRun.of(Goal.of("publish something"),
                        AgentConfig.builder("m").maxSteps(5).build(),
                        ((SimpleToolRegistry) tools).advertisedSpecs()));
    }

    private static ScriptedLlm callsThenStops() {
        return new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "publish", Map.of("text", "the payload")))
                .then(ScriptedLlm.text("done"));
    }

    // --- the tests ---------------------------------------------------------------

    @Test
    void aReadOnlyGateStopsAWriterOnTheDurablePath() {
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(writer(entered));

        AgentRunResult result = runWith(callsThenStops(), tools, ToolGates.readOnly());

        assertThat(entered)
                .as("the gate denied and the tool ran anyway — which is what asserting on "
                        + "the gate's own answer would have missed")
                .isEmpty();
        assertThat(result.output()).isEqualTo("done");
    }

    @Test
    void theModelIsToldWhyRatherThanTheRunFailing() {
        // A denial is an answer the model reacts to, not an activity failure. If it
        // surfaced as a failure Temporal would retry it — asking the same settled question
        // until the attempts ran out, then failing the call.
        AtomicInteger evaluations = new AtomicInteger();
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(writer(entered));
        ScriptedLlm llm = callsThenStops();
        ToolGate countingDeny = (tool, invocation) -> {
            evaluations.incrementAndGet();
            return GateResult.deny("not on a durable run");
        };

        AgentRunResult result = runWith(llm, tools, countingDeny);

        assertThat(result.isSuccess()).isTrue();
        assertThat(entered).isEmpty();
        // The measurement that makes this test about retries rather than about wording.
        // A denial thrown out of the activity is indistinguishable downstream — the
        // workflow catches activity failures and turns them into error results too, so
        // the model still gets a refusal and the run still succeeds. What differs is that
        // Temporal retries the throw first, re-asking a question that was already settled
        // until the attempts run out. One evaluation is the whole claim.
        assertThat(evaluations.get())
                .as("the denial was retried, so a settled decision was re-asked")
                .isEqualTo(1);
        // Two turns: the refused call, then the model's answer.
        assertThat(llm.callCount()).isEqualTo(2);
        assertThat(result.steps()).isEqualTo(2);
    }

    @Test
    void anApprovedEditIsWhatRuns() {
        // Dropping the replacement is how two in-process decorators turned an editing gate
        // into a rubber stamp: the edit was computed, discarded, and the original ran.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(writer(entered));
        ToolGate redacts = (tool, invocation) -> GateResult.allowWith(
                new ToolInvocation(invocation.id(), invocation.name(),
                        Map.of("text", "[redacted]")));

        AgentRunResult result = runWith(callsThenStops(), tools, redacts);

        assertThat(entered).hasSize(1);
        assertThat(entered.get(0).stringArgument("text"))
                .as("the tool ran with the arguments the model proposed, not the approved ones")
                .isEqualTo("[redacted]");
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void theDecisionIsMadeOnceAndReplayedRatherThanRecomputed() {
        // The reason the gate runs in the activity rather than the workflow. An activity's
        // result is memoized in history, so a replay reuses the decision. Evaluated in the
        // workflow it would be recomputed on every replay — and a policy edited between
        // replays would rewrite what history says happened.
        AtomicInteger evaluations = new AtomicInteger();
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(writer(entered));
        // A gate whose answer *changes*. Counting evaluations alone proved nothing: with a
        // constant gate the count is one wherever the gate sits, so the test passed with
        // the replay removed entirely. What the placement actually buys is that the
        // recorded decision stands even when re-asking would now give a different one.
        ToolGate allowsOnlyTheFirstTime = (tool, invocation) ->
                evaluations.incrementAndGet() == 1
                        ? GateResult.allow()
                        : GateResult.deny("the policy changed since that call was allowed");
        // The second turn fails once, so Temporal retries it and the workflow replays from
        // history with the tool activity — and its gate decision — already complete.
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "publish", Map.of("text", "the payload")))
                .fail()
                .then(ScriptedLlm.text("done"));

        AgentRunResult result = runWith(llm, tools, allowsOnlyTheFirstTime);

        assertThat(result.isSuccess()).isTrue();
        assertThat(entered)
                .as("the replay re-gated a completed call, so what history recorded and "
                        + "what the policy now says came apart")
                .hasSize(1);
        assertThat(evaluations.get())
                .as("the gate was consulted again on replay")
                .isEqualTo(1);
    }

    @Test
    void aGateThatWaitsForAPersonIsRefusedWhereItIsWired() {
        // An approver blocks a thread in process and the run waits, which is what it is
        // for. As an activity it burns the start-to-close timeout, fails, and is retried —
        // asking the person again each time before failing the call anyway. Refused at
        // registration rather than discovered in production.
        SimpleToolRegistry tools = new SimpleToolRegistry().register(writer(new ArrayList<>()));
        // An approver that reaches a person — the default presumption, and the case that
        // cannot work here.
        ToolGate approval = ToolGates.requireApproval(
                invocation -> true, (tool, invocation) -> ApprovalDecision.approve());
        Worker worker = worker();

        assertThatThrownBy(() -> TemporalAgent.register(worker, new ScriptedLlm(), tools, approval))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block waiting for a person");

        // And composing it with an ordinary gate does not launder it.
        assertThatThrownBy(() -> TemporalAgent.register(worker, new ScriptedLlm(), tools,
                ToolGates.allOf(ToolGates.readOnly(), approval)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block waiting for a person");
    }

    @Test
    void anApproverThatWaitsForNobodyIsStillUsableDurably() {
        // The refusal is about waiting, not about approval as a shape. DENY_ALL is what
        // this framework recommends for an unattended run and it waits for no one, so
        // banning it from the one runner built for unattended work would have been the
        // check misfiring on its own best case.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(writer(entered));

        AgentRunResult result = runWith(callsThenStops(), tools,
                ToolGates.requireApproval(invocation -> true, Approver.DENY_ALL));

        assertThat(result.isSuccess()).isTrue();
        assertThat(entered).as("DENY_ALL let the write through").isEmpty();
    }

    @Test
    void aGateThatThrowsDoesNotEndTheRunOrBlameTheToolWorker() {
        // Three in-process callers put the gate inside the try; this one did not, so a
        // thrown gate escaped the activity. Temporal then retried it — hammering whatever
        // the policy consults — and the run died through the dead-tool-worker heuristic,
        // telling the operator the tool worker was unavailable when the gate had thrown.
        AtomicInteger evaluations = new AtomicInteger();
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(writer(entered));
        ToolGate broken = (tool, invocation) -> {
            evaluations.incrementAndGet();
            throw new IllegalStateException("the policy service is down");
        };

        AgentRunResult result = runWith(callsThenStops(), tools, broken);

        assertThat(result.isSuccess())
                .as("a broken gate aborted the run, which Agent's own contract forbids")
                .isTrue();
        assertThat(entered).as("the tool ran despite the gate throwing").isEmpty();
        assertThat(evaluations.get())
                .as("the failing policy was retried rather than answered once")
                .isEqualTo(1);
    }

    @Test
    void anUnknownToolIsNotGatedBecauseThereIsNothingToGate() {
        AtomicInteger evaluations = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(writer(new ArrayList<>()));
        ToolGate counting = (tool, invocation) -> {
            evaluations.incrementAndGet();
            return GateResult.allow();
        };
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "no-such-tool", Map.of("text", "x")))
                .then(ScriptedLlm.text("done"));

        AgentRunResult result = runWith(llm, tools, counting);

        assertThat(result.isSuccess()).isTrue();
        // Matching Agent.runTool: a caller that cannot resolve a tool has nothing to gate,
        // and handing a gate a null tool is what the two-argument evaluate exists to stop.
        assertThat(evaluations.get()).isZero();
    }

    @Test
    void aRunWiredWithNoGateIsUngated() {
        // Stated as a test rather than left to be inferred: omitting the gate is the same
        // choice as an in-process Agent.Builder that never calls toolGate, and it is the
        // three-argument register that makes it. Nothing here has become gated by default.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(writer(entered));
        TemporalAgent.registerUngated(worker(), callsThenStops(), tools);
        env.start();

        AgentRunResult result = TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE)
                .run(DurableAgentRun.of(Goal.of("publish something"),
                        AgentConfig.builder("m").maxSteps(5).build(), tools.advertisedSpecs()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(entered).hasSize(1);
    }

    @Test
    void aGateCannotRedirectToAnotherToolOnTheDurablePath() {
        // #104. GateResult's javadoc said a replacement was "the same tool with edited
        // arguments" and nothing enforced it, so a gate written to downgrade a public
        // publish to a private draft let the *publish* happen, carrying the draft's
        // arguments. Measured before the fix on both runners: publish ran once, draft never.
        //
        // Asserted on the tools, not on the gate. Asking the gate whether it redirected is
        // asking the control whether it worked, and it answers yes for a runner that
        // ignores the redirect — the class of bug this file exists for.
        List<ToolInvocation> published = new ArrayList<>();
        List<ToolInvocation> drafted = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(writer(published))
                .register(FunctionTool.builder("draft", "Saves text privately")
                        .schema(Map.of("type", "object",
                                "properties", Map.of("text", Map.of("type", "string"))))
                        .sideEffects(SideEffects.IDEMPOTENT)
                        .handler(invocation -> {
                            drafted.add(invocation);
                            return ToolResult.ok("drafted");
                        })
                        .build());
        ToolGate redirecting = (tool, invocation) -> GateResult.allowWith(
                new ToolInvocation(invocation.id(), "draft", invocation.arguments()));

        AgentRunResult result = runWith(callsThenStops(), tools, redirecting);

        assertThat(published)
                .as("the redirect was ignored and the public tool ran anyway")
                .isEmpty();
        assertThat(drafted)
                .as("the redirect was honoured, which would mean a tool ran without having "
                        + "been resolved or gated as itself")
                .isEmpty();
        // Refused, and the run continues: a gate author's mistake is a tool error the model
        // can react to, not a dead run.
        assertThat(result.output()).isEqualTo("done");
    }

    @Test
    void anEditedArgumentIsStillHonouredOnTheDurablePath() {
        // The other half, so the refusal above is not just "replacements stopped working".
        List<ToolInvocation> published = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(writer(published));
        ToolGate narrowing = (tool, invocation) -> GateResult.allowWith(
                new ToolInvocation(invocation.id(), invocation.name(),
                        Map.of("text", "redacted")));

        runWith(callsThenStops(), tools, narrowing);

        assertThat(published).hasSize(1);
        assertThat(published.get(0).stringArgument("text")).isEqualTo("redacted");
    }

    @Test
    void aRepeatedToolCallIdRunsNothingOnTheDurablePath() {
        // #119, and it is here rather than only in core because the two loops decide this
        // independently and an authorization-shaped rule they come to disagree about is
        // exactly what #58 was. The check reads only the turn, so a replay reaches the same
        // conclusion — which it must, or a replayed run and a fresh one differ.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(writer(entered));
        ScriptedLlm llm = new ScriptedLlm()
                .then(dev.agentkit.core.llm.LlmResponse.of(
                        dev.agentkit.core.message.Message.of(
                                dev.agentkit.core.message.Role.ASSISTANT, List.of(
                                        dev.agentkit.core.message.ProposedCall.of(
                                                "t1", "publish", Map.of("text", "reviewed")),
                                        dev.agentkit.core.message.ProposedCall.of(
                                                "t1", "publish", Map.of("text", "hostile")))),
                        dev.agentkit.core.llm.LlmStopReason.TOOL_USE,
                        dev.agentkit.core.llm.TokenUsage.ZERO))
                .then(ScriptedLlm.text("done"));

        AgentRunResult result = runWith(llm, tools, ToolGates.allowAll());

        assertThat(entered)
                .as("a call the framework could not correlate still ran durably")
                .isEmpty();
        // Stops, like the in-process loop, and for the same reason: the assistant turn is
        // echoed back verbatim so there is no next request to make. Asserted on steps too,
        // because an earlier version incremented in this branch on top of the increment the
        // loop had already made — so a refused turn cost one step in-process and two
        // durably, and nothing noticed.
        assertThat(result.stopReason())
                .isEqualTo(dev.agentkit.core.agent.StopReason.ERROR);
        assertThat(result.errorMessage()).contains("more than once");
        assertThat(result.steps()).isEqualTo(1);
    }
}
