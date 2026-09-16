package dev.agentkit.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Drives {@link WebResearchAgent} against a scripted model and a fake search backend. */
class WebResearchAgentTest {

    private static final String MODEL = "fake-model";

    @Test
    void searchesThenAnswersFromTheResults() {
        // Turn 1: the model searches. Turn 2: it answers, grounded in the results.
        FakeLlm llm = new FakeLlm()
                .then(FakeLlm.toolUse("s1", "web_search",
                        Map.of("query", "Microsoft Graph add user to group")))
                .then(FakeLlm.text("POST https://graph.microsoft.com/v1.0/groups/{group-id}/members/$ref "
                        + "with @odata.id pointing at the user; requires GroupMember.ReadWrite.All. "
                        + "Source: https://learn.microsoft.com/graph/api/group-post-members"));

        // A fake backend so the test is deterministic and offline.
        List<String> queries = new ArrayList<>();
        WebSearch search = (query, max) -> {
            queries.add(query);
            return List.of(new WebSearch.Result(
                    "Add member - Microsoft Graph",
                    "https://learn.microsoft.com/graph/api/group-post-members",
                    "POST /groups/{group-id}/members/$ref, permission GroupMember.ReadWrite.All"));
        };

        // Capture the web_search tool result to prove the tool actually ran.
        List<ToolResult> searches = new ArrayList<>();
        AgentObserver observer = new AgentObserver() {
            @Override
            public void onToolResult(AgentRun run, int step,
                                     ToolInvocation proposed, ToolInvocation invocation,
                                     ToolResult result,
                                     dev.agentkit.core.tool.Disposition disposition) {
                if (invocation.name().equals("web_search")) {
                    searches.add(result);
                }
            }
        };

        Agent agent = WebResearchAgent.build(llm, MODEL, search, observer);
        AgentResult result = agent.run(Goal.of("How do I add a user to a group with Microsoft Graph?"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).contains("members/$ref").contains("GroupMember.ReadWrite.All");
        assertThat(llm.remaining()).isZero();

        // web_search ran once, hit the backend, and returned the results (not an error).
        assertThat(queries).containsExactly("Microsoft Graph add user to group");
        assertThat(searches).hasSize(1);
        assertThat(searches.get(0).isError()).isFalse();
        assertThat(searches.get(0).content()).contains("group-post-members");
    }
}
