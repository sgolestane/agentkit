package dev.agentkit.core.planning;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.tool.ToolSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmPlannerTest {

    @Test
    void parsesNumberedSteps() {
        assertThat(LlmPlanner.parseSteps("1. First\n2. Second\n3. Third"))
                .containsExactly("First", "Second", "Third");
    }

    @Test
    void parsesBulletedStepsAndSkipsBlankLines() {
        assertThat(LlmPlanner.parseSteps("- alpha\n\n* beta\n+ gamma\n"))
                .containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void parenthesisedNumbersAndProseLinesAreHandled() {
        assertThat(LlmPlanner.parseSteps("1) do a thing\nthen do another"))
                .containsExactly("do a thing", "then do another");
    }

    @Test
    void emptyTextYieldsNoSteps() {
        assertThat(LlmPlanner.parseSteps("   \n  \n")).isEmpty();
    }

    @Test
    void doesNotOverStripContentThatMerelyLooksLikeAMarker() {
        // A decimal or a signed range must be preserved: the marker regex requires a
        // space after the marker, which "3.5" and "-5" do not have.
        assertThat(LlmPlanner.parseSteps("Tune to 3.5 GHz target\n-5 to 5 volts"))
                .containsExactly("Tune to 3.5 GHz target", "-5 to 5 volts");
    }

    @Test
    void bareMarkerLinesAreDroppedNotKeptAsJunkSteps() {
        assertThat(LlmPlanner.parseSteps("1. real step\n- \n2.\n* keep this"))
                .containsExactly("real step", "keep this");
    }

    @Test
    void planCallsTheModelAndReturnsTheParsedPlan() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("1. gather data\n2. write report"));
        LlmPlanner planner = new LlmPlanner(llm, "m");

        Plan plan = planner.plan(Goal.of("produce a report"), List.of());

        assertThat(plan.steps()).containsExactly("gather data", "write report");
    }

    @Test
    void toolDescriptionsAreIncludedInThePlanningPrompt() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("1. step"));
        LlmPlanner planner = new LlmPlanner(llm, "m");

        planner.plan(Goal.of("g"), List.of(new ToolSpec("search", "search the web",
                ToolSpec.emptyObjectSchema())));

        LlmRequest sent = llm.received().get(0);
        assertThat(sent.messages().get(0).text()).contains("search").contains("search the web");
        assertThat(sent.tools()).isEmpty(); // the planning call itself uses no tools
    }

    @Test
    void planningUsesASingleToolFreeCall() {
        int[] calls = {0};
        LlmClient counting = request -> {
            calls[0]++;
            return FakeLlmClient.text("1. only step");
        };
        new LlmPlanner(counting, "m").plan(Goal.of("g"), List.of());
        assertThat(calls[0]).isEqualTo(1);
    }
}
