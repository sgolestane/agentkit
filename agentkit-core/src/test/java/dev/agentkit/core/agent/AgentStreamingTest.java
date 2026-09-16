package dev.agentkit.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.StreamHandler;
import dev.agentkit.core.tool.SimpleToolRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentStreamingTest {

    private static final AgentConfig CONFIG = AgentConfig.builder("m").maxSteps(3).build();

    /** A client that streams the given fragments and returns their concatenation. */
    private static LlmClient streamingClient(String... fragments) {
        return new LlmClient() {
            @Override
            public LlmResponse generate(LlmRequest request) {
                return FakeLlmClient.text(String.join("", fragments));
            }

            @Override
            public LlmResponse generate(LlmRequest request, StreamHandler handler) {
                for (String f : fragments) {
                    handler.onTextDelta(f);
                }
                return generate(request);
            }
        };
    }

    private static final class CollectingObserver implements AgentObserver {
        final List<String> deltas = new ArrayList<>();
        final List<Integer> steps = new ArrayList<>();

        @Override
        public void onTextDelta(AgentRun run, int step, String delta) {
            steps.add(step);
            deltas.add(delta);
        }
    }

    @Test
    void streamingForwardsDeltasToTheObserverWithTheTurnNumber() {
        CollectingObserver observer = new CollectingObserver();
        Agent agent = Agent.builder(streamingClient("Hel", "lo"), new SimpleToolRegistry(), CONFIG)
                .observer(observer)
                .streaming(true)
                .build();

        AgentResult result = agent.run(Goal.of("greet"));

        assertThat(result.output()).isEqualTo("Hello");
        assertThat(observer.deltas).containsExactly("Hel", "lo");
        assertThat(observer.steps).containsExactly(1, 1); // both deltas belong to turn 1
    }

    @Test
    void streamingSurvivesThroughABudgetDecorator() {
        // Regression: decorators that override only generate(request) used to make
        // streaming silently degrade to one delta. BudgetLlmClient now forwards the
        // streaming overload, so real deltas still reach the observer through it.
        CollectingObserver observer = new CollectingObserver();
        LlmClient budgeted = new dev.agentkit.core.reliability.BudgetLlmClient(
                streamingClient("Hel", "lo"),
                dev.agentkit.core.reliability.TokenBudget.ofTotalTokens(1_000_000));
        Agent agent = Agent.builder(budgeted, new SimpleToolRegistry(), CONFIG)
                .observer(observer)
                .streaming(true)
                .build();

        agent.run(Goal.of("greet"));

        assertThat(observer.deltas).containsExactly("Hel", "lo");
    }

    @Test
    void streamingIsOffByDefaultSoNoDeltasAreDelivered() {
        CollectingObserver observer = new CollectingObserver();
        // Without .streaming(true) the loop uses the blocking generate(request), which
        // this client tracks so we can prove the streaming overload was not called.
        boolean[] streamedCalled = {false};
        LlmClient client = new LlmClient() {
            @Override
            public LlmResponse generate(LlmRequest request) {
                return FakeLlmClient.text("done");
            }

            @Override
            public LlmResponse generate(LlmRequest request, StreamHandler handler) {
                streamedCalled[0] = true;
                return generate(request);
            }
        };
        Agent agent = Agent.builder(client, new SimpleToolRegistry(), CONFIG)
                .observer(observer)
                .build();

        agent.run(Goal.of("greet"));

        assertThat(observer.deltas).isEmpty();
        assertThat(streamedCalled[0]).isFalse();
    }
}
