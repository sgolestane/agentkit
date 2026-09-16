package dev.agentkit.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A tool call's arguments are snapshotted once, not once per record that carries them
 * (#134).
 *
 * <p>{@code Agent} and {@code AgentWorkflowImpl} each do
 * {@code new ToolInvocation(use.id(), use.name(), use.input())}, and {@code ToolUseBlock}'s
 * constructor has already deep-frozen {@code input()}. So every tool call walked its whole
 * argument tree twice, and on the durable path {@code ToolUseBlock} is rebuilt by Jackson
 * on every workflow replay of every step, so the second walk was paid per replay. Measured
 * as bytes allocated per call, by the method #170 used, on {@code main} before the fix:
 *
 * <pre>
 * expanded nodes   ToolUseBlock's freeze   ToolInvocation's freeze   redundant
 *             16                 1,080 B                   1,128 B         51%
 *          1,554               108,680 B                 114,008 B         51%
 *         69,904             4,200,456 B               4,299,144 B         51%
 * </pre>
 *
 * <p>Exactly half, because the second walk visits the node set the first one produced. The
 * second column is 24 B at every one of those sizes now — the {@code ToolInvocation} record
 * itself and nothing else.
 *
 * <h2>Why the assertion is object identity</h2>
 *
 * <p>Not a call counter on {@code Frozen}: it is a static method that returned a plain
 * {@code LinkedHashMap}, so an instrumented input map is visited exactly once either way
 * and the counter would read the same before and after. Not elapsed time either — wall
 * clock on a shared machine put #170's equivalent share anywhere from 29% to 76%, which is
 * noise reported as a finding. What actually changed is that the second call now returns
 * the map the first one built, and {@code isSameAs} is the whole of that.
 */
class OneSnapshotPerToolCallTest {

    /** A model-shaped argument map: scalars, a nested map, a nested list. */
    private static Map<String, Object> arguments() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("encoding", "utf-8");
        options.put("skip", List.of("target", ".git"));
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("path", "/tmp/report.txt");
        arguments.put("limit", 20);
        arguments.put("options", options);
        return arguments;
    }

    @Test
    @DisplayName("the invocation carries the block's own snapshot, not a copy of it")
    void theSecondFreezeIsGone() {
        ToolUseBlock use = new ToolUseBlock("t1", "read", arguments());

        ToolInvocation invocation = new ToolInvocation(use.id(), use.name(), use.input());

        assertThat(invocation.arguments()).isSameAs(use.input());
        assertThat(invocation.arguments().get("options")).isSameAs(use.input().get("options"));
    }

    @Test
    @DisplayName("the loop that actually builds it takes the same snapshot the block holds")
    void theAgentLoopDoesNotRefreeze() {
        // Through a real Agent rather than the two lines above, because the double freeze
        // was at a call site and a fix that only held in a unit test would be the shape
        // this repository keeps shipping. The gate sees the proposal, the tool sees what
        // the gate settled on, and both must be the block's map.
        List<ToolInvocation> gated = new ArrayList<>();
        List<ToolInvocation> entered = new ArrayList<>();
        Map<String, Object> arguments = arguments();
        // The block itself, not FakeLlmClient.toolUse, so the assertion can name the map
        // the model's turn actually carries — the one ToolUseBlock's constructor froze.
        ToolUseBlock use = new ToolUseBlock("t1", "read", arguments);
        SimpleToolRegistry registry = new SimpleToolRegistry();
        registry.register(FunctionTool.builder("read", "reads")
                .schema(Map.of("type", "object"))
                .handler(invocation -> {
                    entered.add(invocation);
                    return ToolResult.ok("done");
                })
                .build());

        Agent.builder(new FakeLlmClient(
                                LlmResponse.of(Message.of(Role.ASSISTANT, use),
                                        LlmStopReason.TOOL_USE, TokenUsage.ZERO),
                                FakeLlmClient.text("done")),
                        registry, AgentConfig.builder("m").maxSteps(3).build())
                .toolGate((tool, invocation) -> {
                    gated.add(invocation);
                    return GateResult.allow();
                })
                .build()
                .run(Goal.of("read the file"));

        assertThat(entered).hasSize(1);
        assertThat(gated).hasSize(1);
        // The map the turn carries, frozen once by ToolUseBlock's constructor. Both the
        // gate and the tool must have been handed that, not a second walk of it.
        assertThat(gated.get(0).arguments()).isSameAs(use.input());
        assertThat(entered.get(0).arguments()).isSameAs(use.input());
        assertThat(use.input().get("options")).isNotSameAs(arguments.get("options"));
        assertThat(use.input()).isEqualTo(arguments);
    }

    @Test
    @DisplayName("an approver's edited arguments are still frozen, because they are new")
    void anEditIsStillSnapshotted() {
        // The fast path must not become "ToolInvocation stopped freezing". A map that
        // reached this constructor from anywhere other than a previous snapshot — a gate's
        // replacement, a person's edit in an approval console, a sandbox bridging a
        // script's own types — is walked exactly as before.
        Map<String, Object> edited = new LinkedHashMap<>();
        List<String> caller = new ArrayList<>(List.of("/tmp/harmless.txt"));
        edited.put("paths", caller);

        ToolInvocation invocation = new ToolInvocation("t1", "read", edited);
        caller.set(0, "/etc/shadow");

        assertThat(invocation.arguments()).isNotSameAs(edited);
        assertThat(invocation.argument("paths")).isEqualTo(List.of("/tmp/harmless.txt"));
    }
}
