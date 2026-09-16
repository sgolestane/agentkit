package dev.agentkit.examples;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.reliability.RetryPolicy;
import dev.agentkit.core.reliability.RetryingLlmClient;
import dev.agentkit.core.tool.SimpleToolRegistry;

/**
 * A research agent that answers questions by searching the web.
 *
 * <p>The agent is given a single client-executed {@code web_search} tool (see
 * {@link WebResearchTools}) and a system prompt telling it to search when the
 * answer depends on current documentation, then cite its sources. The demo
 * question — how to add a user to a group with the Microsoft Graph API — is the
 * kind of task where the exact endpoint and required permissions are worth
 * grounding in real docs rather than recalling from memory.
 *
 * <p>By default it runs against offline {@link SampleWebSearch} results so it
 * works with no setup; set {@code TAVILY_API_KEY} to search the live web via
 * {@link TavilyWebSearch}. The backend model is chosen by {@link ExampleBackend}.
 */
public final class WebResearchAgent {

    private WebResearchAgent() {
    }

    /** An agent with a single {@code web_search} tool over {@code search}. */
    public static Agent build(LlmClient llm, String model, WebSearch search) {
        return build(llm, model, search, AgentObserver.NONE);
    }

    /** As {@link #build(LlmClient, String, WebSearch)}, with an observer (for tests/telemetry). */
    static Agent build(LlmClient llm, String model, WebSearch search, AgentObserver observer) {
        SimpleToolRegistry tools = new SimpleToolRegistry();
        tools.register(WebResearchTools.webSearchTool(search));

        AgentConfig config = AgentConfig.builder(model)
                .systemPrompt("You are a research assistant. When a question depends on current "
                        + "documentation or specific facts you are not certain of, call web_search "
                        + "before answering rather than guessing. Ground your answer in the results "
                        + "and cite the source URLs you used.")
                .maxSteps(8)
                .build();

        return Agent.builder(llm, tools, config).observer(observer).build();
    }

    /** Runs the example against the configured backend (see {@link ExampleBackend}). */
    public static void main(String[] args) {
        ExampleBackend backend = ExampleBackend.fromEnv();
        LlmClient reliable = new RetryingLlmClient(backend.llm(), RetryPolicy.defaults());

        WebSearch search;
        String tavilyKey = System.getenv("TAVILY_API_KEY");
        if (tavilyKey != null && !tavilyKey.isBlank()) {
            search = TavilyWebSearch.fromEnv();
            System.out.println("[web_search: Tavily — live web]");
        } else {
            search = new SampleWebSearch();
            System.out.println("[web_search: offline sample results — set TAVILY_API_KEY for live web search]");
        }

        Agent agent = build(reliable, backend.model(), search);
        AgentResult result = agent.run(Goal.of(
                "How do I use the Microsoft Graph API to add a user to a group? "
                        + "Give the exact HTTP request and the permissions required."));

        System.out.println("stopReason=" + result.stopReason());
        System.out.println(result.output());
    }
}
