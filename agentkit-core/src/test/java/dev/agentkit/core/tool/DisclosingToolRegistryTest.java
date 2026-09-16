package dev.agentkit.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DisclosingToolRegistryTest {

    private static Tool tool(String name, String description) {
        return FunctionTool.builder(name, description).handler(inv -> ToolResult.ok("ran " + name)).build();
    }

    private static DisclosingToolRegistry registry() {
        return DisclosingToolRegistry.builder()
                .alwaysAvailable(tool("finish", "produce the final answer"))
                .deferred(tool("get_weather", "get the current weather forecast for a city"))
                .deferred(tool("send_email", "send an email message to a recipient"))
                .build();
    }

    @Test
    void initiallyAdvertisesOnlyAlwaysAvailablePlusSearch() {
        DisclosingToolRegistry registry = registry();
        assertThat(registry.advertisedSpecs()).extracting(ToolSpec::name)
                .containsExactlyInAnyOrder("finish", DisclosingToolRegistry.DEFAULT_SEARCH_TOOL_NAME);
    }

    @Test
    void deferredToolIsFindableButNotAdvertised() {
        DisclosingToolRegistry registry = registry();
        assertThat(registry.find("get_weather")).isPresent();
        assertThat(registry.advertisedSpecs()).extracting(ToolSpec::name).doesNotContain("get_weather");
    }

    @Test
    void searchingRevealsMatchingTools() {
        DisclosingToolRegistry registry = registry();
        ToolResult result = registry.find(DisclosingToolRegistry.DEFAULT_SEARCH_TOOL_NAME).orElseThrow()
                .execute(new ToolInvocation("s1", DisclosingToolRegistry.DEFAULT_SEARCH_TOOL_NAME,
                        Map.of("query", "weather forecast")));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("get_weather");
        assertThat(registry.revealedNames()).contains("get_weather");
        assertThat(registry.advertisedSpecs()).extracting(ToolSpec::name).contains("get_weather");
    }

    @Test
    void searchToolDisappearsOnceEverythingRevealed() {
        DisclosingToolRegistry registry = DisclosingToolRegistry.builder()
                .alwaysAvailable(tool("only", "the only tool"))
                .build();
        assertThat(registry.advertisedSpecs()).extracting(ToolSpec::name).containsExactly("only");
    }

    @Test
    void blankQueryIsRejected() {
        DisclosingToolRegistry registry = registry();
        ToolResult result = registry.find(DisclosingToolRegistry.DEFAULT_SEARCH_TOOL_NAME).orElseThrow()
                .execute(new ToolInvocation("s1", "search_tools", Map.of("query", "  ")));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void nameCollisionWithSearchToolIsRejected() {
        assertThatThrownBy(() -> DisclosingToolRegistry.builder()
                .deferred(tool("search_tools", "collides"))
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ToolResult search(DisclosingToolRegistry registry, String query) {
        return registry.find(DisclosingToolRegistry.DEFAULT_SEARCH_TOOL_NAME).orElseThrow()
                .execute(new ToolInvocation("s", "search_tools", Map.of("query", query)));
    }

    @Test
    void noMatchReturnsOkGuidance() {
        DisclosingToolRegistry registry = registry();
        ToolResult result = search(registry, "zzzzzz nonsense qqqq");
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("No new tools matched");
        assertThat(registry.revealedNames()).doesNotContain("get_weather", "send_email");
    }

    @Test
    void searchOnlySurfacesDeferredNotAlwaysAvailable() {
        DisclosingToolRegistry registry = registry();
        // "produce the final answer" describes the always-available 'finish' tool,
        // but search must never reveal/return it (it is not deferred).
        ToolResult result = search(registry, "produce the final answer");
        assertThat(result.content()).doesNotContain("finish");
    }

    @Test
    void searchLimitCapsRevealedCount() {
        DisclosingToolRegistry registry = DisclosingToolRegistry.builder()
                .deferred(tool("send_email", "send an email message"))
                .deferred(tool("send_sms", "send an sms message"))
                .searchLimit(1)
                .build();
        search(registry, "send message");
        assertThat(registry.revealedNames()).hasSize(1);
    }

    @Test
    void advertisedOrderIsSearchToolThenRevealOrder() {
        DisclosingToolRegistry registry = registry();
        search(registry, "email recipient");   // reveals send_email
        search(registry, "weather forecast");  // reveals get_weather
        assertThat(registry.advertisedSpecs()).extracting(ToolSpec::name)
                .containsExactly("search_tools", "finish", "send_email", "get_weather");
    }

    @Test
    void revealMethodSurfacesToolWithoutSearch() {
        DisclosingToolRegistry registry = registry();
        registry.reveal("send_email");
        assertThat(registry.advertisedSpecs()).extracting(ToolSpec::name).contains("send_email");
        registry.reveal("nonexistent"); // no-op
        assertThat(registry.advertisedSpecs()).extracting(ToolSpec::name).doesNotContain("nonexistent");
    }

    @Test
    void customSearchToolNameIsUsed() {
        DisclosingToolRegistry registry = DisclosingToolRegistry.builder()
                .searchToolName("find_tools")
                .deferred(tool("x", "some capability"))
                .build();
        assertThat(registry.find("find_tools")).isPresent();
        assertThat(registry.advertisedSpecs()).extracting(ToolSpec::name).contains("find_tools");
    }

    @Test
    void agentDiscoversAndCallsDeferredTool() {
        DisclosingToolRegistry registry = registry();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("s1", "search_tools", Map.of("query", "weather")),
                FakeLlmClient.toolUse("w1", "get_weather", Map.of("city", "Seattle")),
                FakeLlmClient.text("It is sunny."));

        Agent agent = new Agent(llm, registry, AgentConfig.builder("m").maxSteps(5).build());
        AgentResult result = agent.run(Goal.of("what's the weather in Seattle"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("It is sunny.");
        // First turn advertised no get_weather; after the search, it was revealed.
        assertThat(llm.received().get(0).tools()).extracting(ToolSpec::name).doesNotContain("get_weather");
        assertThat(llm.received().get(1).tools()).extracting(ToolSpec::name).contains("get_weather");
    }
}
