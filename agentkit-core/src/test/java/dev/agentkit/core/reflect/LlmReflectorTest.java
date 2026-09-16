package dev.agentkit.core.reflect;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.TokenUsage;
import org.junit.jupiter.api.Test;

class LlmReflectorTest {

    private static final Goal GOAL = Goal.of("Book a flight to Tokyo");
    private static final AgentResult RESULT =
            AgentResult.completed("I found some options", 2, TokenUsage.ZERO);

    @Test
    void returnsTheModelsLessonStripped() {
        LlmClient llm = request -> FakeLlmClient.text("  Always confirm the travel dates before booking.  ");
        String lesson = new LlmReflector(llm, "m").reflect(GOAL, RESULT, "no dates were confirmed");
        assertThat(lesson).isEqualTo("Always confirm the travel dates before booking.");
    }

    @Test
    void thePromptCarriesTheGoalOutputAndFeedback() {
        LlmRequest[] captured = new LlmRequest[1];
        LlmClient llm = request -> {
            captured[0] = request;
            return FakeLlmClient.text("a lesson");
        };
        new LlmReflector(llm, "m").reflect(GOAL, RESULT, "missing the return date");

        String prompt = captured[0].messages().get(0).text();
        assertThat(prompt).contains("Book a flight to Tokyo")
                .contains("I found some options").contains("missing the return date");
        assertThat(captured[0].tools()).isEmpty(); // reflection uses no tools
    }
}
