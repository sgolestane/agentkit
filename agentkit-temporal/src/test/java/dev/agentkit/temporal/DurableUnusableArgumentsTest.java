package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.UnusableToolUseBlock;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.util.Frozen;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The durable runner's half of #246: a call whose arguments this framework will not carry
 * reaches the model as a refusal, and the run finishes.
 *
 * <h2>Where the refusal lands on this path, and why not in the activity</h2>
 *
 * <p>#246 names {@code ToolActivitiesImpl} as one of the four runners, and the decision is
 * one line earlier than that — in {@code AgentWorkflowImpl}, where a block becomes a
 * {@link dev.agentkit.core.tool.ToolInvocation}. It has to be. A {@code ToolInvocation}
 * freezes its own arguments in its constructor, so one holding a refused tree cannot exist;
 * putting the tree on the activity wire instead would mean a deserialization throw landing
 * on the <em>workflow</em> thread, which fails the workflow task, which Temporal retries
 * forever — a stall rather than a rejection, and the outcome {@code ToolInvocation}'s own
 * javadoc calls the worst failure mode in this codebase.
 *
 * <p>So the workflow is the durable counterpart of {@code Agent.runTool}'s decision, and the
 * activity is never started for a refused call. That is what the gate and side-effect
 * counters below assert: not "the gate denied it", but that nothing on the worker was asked
 * anything at all.
 *
 * <h2>The block crosses history, which is the part with a cost</h2>
 *
 * <p>The refused call rides in the assistant turn, which the model activity writes and the
 * workflow reads back, on the first write and on every replay. {@link ContentBlockMixin}
 * carries the rolling-deploy argument for the new {@code @type} that requires.
 */
class DurableUnusableArgumentsTest {

    private static final String TASK_QUEUE = "agentkit-unusable-args-test";

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    private static Map<String, Object> nested(int levels) {
        Map<String, Object> at = new LinkedHashMap<>();
        at.put("v", "x");
        for (int i = 1; i < levels; i++) {
            Map<String, Object> up = new LinkedHashMap<>();
            up.put("n", at);
            at = up;
        }
        return at;
    }

    private AgentRunResult runDurably(ScriptedLlm llm, SimpleToolRegistry tools, ToolGate gate) {
        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(TemporalAgent.dataConverter())
                        .build())
                .build());
        Worker worker = env.newWorker(TASK_QUEUE);
        TemporalAgent.register(worker, llm, tools, gate);
        env.start();
        return TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE)
                .run(DurableAgentRun.of(Goal.of("publish something"),
                        AgentConfig.builder("m").maxSteps(5).build(), tools.advertisedSpecs()));
    }

    @Test
    void aRefusedCallReachesTheModelAndTheDurableRunFinishes() {
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger gated = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(
                FunctionTool.builder("publish", "Publishes text somewhere public")
                        .sideEffects(SideEffects.EXTERNAL)
                        .handler(invocation -> {
                            ran.incrementAndGet();
                            return dev.agentkit.core.tool.ToolResult.ok("published");
                        })
                        .build());
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "publish", nested(Frozen.MAX_DEPTH + 1)))
                .then(ScriptedLlm.text("done"));

        AgentRunResult result = runDurably(llm, tools, (tool, invocation) -> {
            gated.incrementAndGet();
            return GateResult.deny("denied by policy");
        });

        // Before: the parse threw inside the model activity, Temporal retried it, and the
        // run died through the dead-tool-worker heuristic with nothing said to the model.
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(result.output()).isEqualTo("done");
        assertThat(gated.get())
                .as("the activity was started and a gate judged arguments the tool never"
                        + " received")
                .isZero();
        assertThat(ran.get()).as("a tool ran on arguments nothing froze").isZero();

        // What the model was actually shown, on the request the model activity was handed
        // next. Asserted here rather than on a value this test built, because a refusal a
        // runner produces and never sends is the same defect one layer in.
        assertThat(llm.callCount()).isEqualTo(2);
        List<ToolResultBlock> shown = llm.requests().stream()
                .flatMap(request -> request.messages().stream())
                .flatMap(message -> message.content().stream())
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .toList();
        assertThat(shown).hasSize(1);
        assertThat(shown.get(0).toolUseId()).isEqualTo("t1");
        assertThat(shown.get(0).isError()).isTrue();
        assertThat(shown.get(0).provenance()).isEqualTo(Provenance.FIRST_PARTY);
        assertThat(shown.get(0).content())
                .contains("refused before anything ran")
                .contains("No tool ran, no gate was asked")
                .contains(String.valueOf(Frozen.MAX_DEPTH));
    }

    @Test
    void aTurnMixingARefusedCallWithARunnableOneAnswersBothInOrder() {
        // The shape a one-call-per-turn probe cannot show, and on this runner it is also
        // the shape that makes the failure legible. A workflow that produced no result
        // block for the refused call would build a user message with nothing in it, which
        // throws on the workflow thread — and a workflow-thread throw fails the workflow
        // task, which Temporal retries forever. The run then STALLS: no result, no error,
        // and a test that waits on it hangs rather than failing. With a runnable sibling in
        // the turn the message is never empty, so the same mistake comes back as an
        // assertion about a tool_use left with no tool_result.
        AtomicInteger ran = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(
                FunctionTool.builder("publish", "Publishes text somewhere public")
                        .handler(invocation -> {
                            ran.incrementAndGet();
                            return dev.agentkit.core.tool.ToolResult.ok("published");
                        })
                        .build());
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.multiProposedCall(List.of(
                        dev.agentkit.core.message.ProposedCall.of(
                                "good", "publish", Map.of("text", "fine")),
                        dev.agentkit.core.message.ProposedCall.of(
                                "deep", "publish", nested(Frozen.MAX_DEPTH + 1)))))
                .then(ScriptedLlm.text("done"));

        AgentRunResult result = runDurably(llm, tools, ToolGate.ALLOW_ALL);

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(ran.get())
                .as("a refused call took its runnable sibling down with it")
                .isEqualTo(1);
        List<ToolResultBlock> shown = llm.requests().stream()
                .flatMap(request -> request.messages().stream())
                .flatMap(message -> message.content().stream())
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .toList();
        assertThat(shown)
                .as("a tool_use was left with no tool_result, which the wire format rejects")
                .hasSize(2);
        assertThat(shown.stream().map(ToolResultBlock::toolUseId))
                .containsExactly("good", "deep");
        assertThat(shown.get(0).isError()).isFalse();
        assertThat(shown.get(1).isError()).isTrue();
        assertThat(shown.get(1).content()).contains("No tool ran, no gate was asked");
    }

    @Test
    void theRefusedCallSurvivesTheRoundTripThroughHistorySoTheEchoedTurnStaysValid() {
        // The durable path's extra hazard. The assistant turn is serialized by the model
        // activity and deserialized by the WORKFLOW, so a block the converter cannot name
        // does not merely lose information — it fails the workflow task, which Temporal
        // retries forever. This asserts the round trip through the same converter the
        // workflow uses, and that the block is still in the turn the next request carries:
        // a tool_result with no matching tool_use is rejected by the wire format, so
        // dropping it at the parse would invalidate the very request that carries its
        // refusal.
        AtomicInteger ran = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(
                FunctionTool.builder("publish", "Publishes text somewhere public")
                        .handler(invocation -> {
                            ran.incrementAndGet();
                            return dev.agentkit.core.tool.ToolResult.ok("published");
                        })
                        .build());
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "publish", nested(Frozen.MAX_DEPTH + 1)))
                .then(ScriptedLlm.text("done"));

        runDurably(llm, tools, ToolGate.ALLOW_ALL);

        LlmRequest second = llm.requests().get(1);
        List<ContentBlock> assistant = second.messages().stream()
                .filter(message -> message.role() == Role.ASSISTANT)
                .flatMap(message -> message.content().stream())
                .toList();
        assertThat(assistant).anySatisfy(block -> {
            assertThat(block).isInstanceOf(UnusableToolUseBlock.class);
            assertThat(((UnusableToolUseBlock) block).id()).isEqualTo("t1");
        });
        assertThat(ran.get()).isZero();
    }

    @Test
    void theConverterCanNameTheBlockInBothDirections() {
        // ContentBlockMixin's entry, pinned directly. Without it the workflow reads back a
        // turn it cannot deserialize and the run stalls with no result and no error, which
        // is the failure mode this repository calls worse than failing — and the test above
        // would report it as a timeout rather than as a missing @type.
        UnusableToolUseBlock block =
                new UnusableToolUseBlock("t1", "publish", "the refusal a model reads");
        Message turn = Message.of(Role.ASSISTANT, block);

        byte[] encoded;
        Message decoded;
        try {
            encoded = DurableJson.objectMapper().writeValueAsBytes(turn);
            decoded = DurableJson.objectMapper().readValue(encoded, Message.class);
        } catch (Exception e) {
            throw new AssertionError("the durable converter could not round-trip the block", e);
        }

        assertThat(new String(encoded, java.nio.charset.StandardCharsets.UTF_8))
                .as("the discriminator a rolling deploy is read against changed silently")
                .contains("tool_use_unusable");
        assertThat(decoded.content()).containsExactly(block);
    }
}
