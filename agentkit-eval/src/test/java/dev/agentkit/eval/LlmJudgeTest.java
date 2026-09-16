package dev.agentkit.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmJudgeTest {

    private static EvalRun run() {
        return new EvalRun(Goal.of("book a flight"),
                AgentResult.completed("Booked flight AA123", 2, TokenUsage.ZERO),
                List.of(new ToolCall(new ToolInvocation("t", "search_flights", Map.of()), false,
                        Disposition.RAN)));
    }

    @Test
    void passVerdictOnAnExplicitPass() {
        LlmClient judgeLlm = request -> ScriptedLlm.text("PASS");
        assertThat(new LlmJudge(judgeLlm, "m").judge(run(), "the agent booked a flight").passed()).isTrue();
    }

    @Test
    void failVerdictCarriesTheSecondLineAsFeedback() {
        LlmClient judgeLlm = request -> ScriptedLlm.text("FAIL\nit never confirmed the date");
        var verdict = new LlmJudge(judgeLlm, "m").judge(run(), "must confirm the travel date");
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.feedback()).isEqualTo("it never confirmed the date");
    }

    @Test
    void failsClosedWhenTheFirstTokenIsNotExactlyPass() {
        LlmClient judgeLlm = request -> ScriptedLlm.text("The agent PASSED the task nicely");
        assertThat(new LlmJudge(judgeLlm, "m").judge(run(), "rubric").passed()).isFalse();
    }

    @Test
    void passIsAcceptedEvenWithASameLineExplanation() {
        // Judges routinely add reasoning on the verdict line despite instructions.
        for (String reply : List.of("PASS. The agent booked the flight.", "PASS - correct", "PASS, good")) {
            LlmClient judgeLlm = request -> ScriptedLlm.text(reply);
            assertThat(new LlmJudge(judgeLlm, "m").judge(run(), "rubric").passed())
                    .as(reply).isTrue();
        }
    }

    @Test
    void anEmptyJudgeResponseFailsClosed() {
        LlmClient judgeLlm = request -> ScriptedLlm.text("");
        assertThat(new LlmJudge(judgeLlm, "m").judge(run(), "rubric").passed()).isFalse();
    }

    @Test
    void thePromptIncludesTheToolTrajectory() {
        LlmRequest[] captured = new LlmRequest[1];
        LlmClient judgeLlm = request -> {
            captured[0] = request;
            return ScriptedLlm.text("PASS");
        };
        new LlmJudge(judgeLlm, "m").judge(run(), "the rubric text");

        String prompt = captured[0].messages().get(0).text();
        assertThat(prompt).contains("search_flights(ok)") // trajectory annotated with outcome
                .contains("the rubric text").contains("Booked flight");
    }

    @Test
    void judgeCheckAdaptsTheVerdict() {
        LlmClient judgeLlm = request -> ScriptedLlm.text("FAIL\nno confirmation");
        CheckOutcome outcome = Checks.judge(judgeLlm, "m", "must confirm").check(run());
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.detail()).isEqualTo("no confirmation");
    }
}
