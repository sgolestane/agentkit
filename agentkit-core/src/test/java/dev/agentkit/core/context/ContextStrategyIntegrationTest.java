package dev.agentkit.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextStrategyIntegrationTest {

    @Test
    void ofAppliesEditorThenCompactor() {
        ContextEditor editor = history -> List.of(Message.user("edited"));
        Compactor compactor = history -> {
            assertThat(history).containsExactly(Message.user("edited")); // sees editor output
            return List.of(Message.user("compacted"));
        };
        ContextStrategy strategy = ContextStrategies.of(editor, compactor);
        assertThat(strategy.prepare(List.of(Message.user("original"))))
                .containsExactly(Message.user("compacted"));
    }

    @Test
    void agentAppliesContextStrategyToEachRequest() {
        var registry = new SimpleToolRegistry().register(
                FunctionTool.builder("noop", "no-op").handler(inv -> ToolResult.ok("ok")).build());
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "noop", Map.of()),
                FakeLlmClient.text("done"));

        // Strategy that only ever sends the single most-recent message.
        ContextStrategy lastOnly = history -> List.of(history.get(history.size() - 1));

        Agent agent = new Agent(llm, registry, AgentConfig.builder("m").maxSteps(5).build(),
                AgentObserver.NONE, lastOnly);
        agent.run(Goal.of("do it"));

        // Every request the model received was reduced to one message by the strategy.
        assertThat(llm.received()).allSatisfy(req -> assertThat(req.messages()).hasSize(1));
    }

    @Test
    void emptyStrategyOutputFailsCleanly() {
        var registry = new SimpleToolRegistry();
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("never reached"));
        ContextStrategy empty = history -> List.of();

        Agent agent = new Agent(llm, registry, AgentConfig.builder("m").build(),
                AgentObserver.NONE, empty);
        var result = agent.run(Goal.of("go"));

        assertThat(result.stopReason()).isEqualTo(dev.agentkit.core.agent.StopReason.ERROR);
        assertThat(result.error()).get().isInstanceOf(IllegalStateException.class);
    }
}
