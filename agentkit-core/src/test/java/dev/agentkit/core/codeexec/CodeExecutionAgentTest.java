package dev.agentkit.core.codeexec;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CodeExecutionAgentTest {

    @Test
    void anAgentOrchestratesToolsThroughASingleCodeExecutionTurn() {
        AtomicInteger weatherCalls = new AtomicInteger();
        var codeTools = new SimpleToolRegistry().register(
                FunctionTool.builder("get_weather", "weather for a city")
                        .handler(i -> {
                            weatherCalls.incrementAndGet();
                            return ToolResult.ok("sunny in " + i.stringArgument("city"));
                        }).build());
        // A stand-in sandbox: the model's script fans out over three cities in one turn.
        CodeSandbox sandbox = (code, tools) -> {
            StringBuilder sb = new StringBuilder();
            for (String city : List.of("Seattle", "Portland", "Denver")) {
                sb.append(tools.invoke("get_weather", Map.of("city", city)).content()).append("; ");
            }
            return SandboxExecution.ok(sb.toString().strip());
        };
        var agentTools = new SimpleToolRegistry(List.of(
                CodeExecutionTool.builder(sandbox, codeTools).allowAllTools().build()));

        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "run_code", Map.of("code", "for c in cities: get_weather(c)")),
                FakeLlmClient.text("Checked all three cities."));

        AgentResult result = new Agent(llm, agentTools,
                AgentConfig.builder("m").maxSteps(4).build()).run(Goal.of("Weather in three cities?"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.steps()).isEqualTo(2); // one code turn (3 tool calls) + the final answer
        assertThat(weatherCalls).hasValue(3); // all three ran inside the single code turn

        // The single run_code result carried all three cities back to the model.
        var toolResultMsg = llm.received().get(1).messages().get(2);
        assertThat(toolResultMsg.role()).isEqualTo(Role.USER);
        String content = ((ToolResultBlock) toolResultMsg.content().get(0)).content();
        assertThat(content).contains("Seattle").contains("Portland").contains("Denver");
    }

    @Test
    void theObserverSeesOnlyTheCodeCallNotTheToolsInvokedInsideIt() {
        var codeTools = new SimpleToolRegistry().register(
                FunctionTool.builder("get_weather", "weather").handler(i -> ToolResult.ok("sunny")).build());
        CodeSandbox sandbox = (code, tools) -> {
            tools.invoke("get_weather", Map.of("city", "Seattle"));
            return SandboxExecution.ok("done");
        };
        var agentTools = new SimpleToolRegistry(List.of(
                CodeExecutionTool.builder(sandbox, codeTools).allowAllTools().build()));
        List<String> observed = new ArrayList<>();
        AgentObserver observer = new AgentObserver() {
            @Override
            public void onToolProposed(AgentRun run, int step, ToolInvocation invocation) {
                observed.add(invocation.name());
            }
        };
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "run_code", Map.of("code", "get_weather('Seattle')")),
                FakeLlmClient.text("done"));

        Agent.builder(llm, agentTools, AgentConfig.builder("m").maxSteps(3).build())
                .observer(observer).build().run(Goal.of("weather?"));

        // The observer (and thus the eval trajectory) sees run_code, not the inner get_weather.
        assertThat(observed).containsExactly("run_code");
    }
}
