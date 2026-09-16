package dev.agentkit.core.planning;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.tool.DisclosingToolRegistry;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class PlanningAgentTest {

    private static final AgentConfig CONFIG = AgentConfig.builder("m").maxSteps(3).build();

    /** A factory building a fresh executor per step, sharing one (FIFO) fake client. */
    private static Supplier<Agent> factory(LlmClient llm) {
        return () -> new Agent(llm, new SimpleToolRegistry(), CONFIG);
    }

    private static Planner fixedPlan(String... steps) {
        return (goal, tools) -> new Plan(List.of(steps));
    }

    @Test
    void executesEachStepInOrderAndAggregatesTheOutcome() {
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.textWithUsage("did A", new TokenUsage(10, 5)),
                FakeLlmClient.textWithUsage("did B", new TokenUsage(4, 3)));
        PlanningAgent agent = new PlanningAgent(fixedPlan("Do A", "Do B"), factory(llm));

        PlanExecution execution = agent.run(Goal.of("achieve X"));

        assertThat(execution.plan().steps()).containsExactly("Do A", "Do B");
        assertThat(execution.stepResults()).hasSize(2);
        assertThat(execution.isSuccess()).isTrue();
        assertThat(execution.overall().output()).isEqualTo("did B"); // final step's output
        assertThat(execution.overall().usage()).isEqualTo(new TokenUsage(14, 8)); // summed
        assertThat(execution.overall().steps()).isEqualTo(2); // one agent-step per sub-run

        // The second step's prompt threads the objective, the plan, and step 1's output.
        String secondStepPrompt = llm.received().get(1).messages().get(0).text();
        assertThat(secondStepPrompt).contains("achieve X").contains("Do A").contains("did A")
                .contains("Complete step 2");
    }

    @Test
    void anEmptyPlanFallsBackToRunningTheGoalDirectly() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("done directly"));
        PlanningAgent agent = new PlanningAgent(fixedPlan(), factory(llm));

        PlanExecution execution = agent.run(Goal.of("just do it"));

        assertThat(execution.plan().isEmpty()).isTrue();
        assertThat(execution.stepResults()).hasSize(1);
        assertThat(execution.overall().output()).isEqualTo("done directly");
        // The direct run receives the raw goal, not a step sub-goal.
        assertThat(llm.received().get(0).messages().get(0).text()).isEqualTo("just do it");
    }

    @Test
    void executionHaltsOnAnErrorStepCarryingItAsFailed() {
        LlmClient failing = request -> {
            throw new LlmException("provider down");
        };
        PlanningAgent agent = new PlanningAgent(fixedPlan("Step 1", "Step 2"), factory(failing));

        PlanExecution execution = agent.run(Goal.of("attempt"));

        assertThat(execution.stepResults()).hasSize(1); // step 2 never ran
        assertThat(execution.overall().stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(execution.overall().error()).isPresent();
        assertThat(execution.isSuccess()).isFalse();
    }

    @Test
    void aLaterStepThatRefusesHaltsWithAccumulatedUsage() {
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.textWithUsage("did A", new TokenUsage(10, 5)),   // step 1 completes
                FakeLlmClient.refusal("I can't do step 2"));                    // step 2 refuses
        PlanningAgent agent = new PlanningAgent(fixedPlan("Do A", "Do B"), factory(llm));

        PlanExecution execution = agent.run(Goal.of("achieve X"));

        assertThat(execution.stepResults()).hasSize(2);
        assertThat(execution.overall().stopReason()).isEqualTo(StopReason.REFUSED);
        assertThat(execution.overall().output()).isEqualTo("I can't do step 2");
        assertThat(execution.overall().error()).isEmpty();
        // Usage from the completed step 1 plus the refused step 2 is carried through.
        assertThat(execution.overall().usage()).isEqualTo(new TokenUsage(10, 5));
    }

    @Test
    void thePlannerReceivesTheFullCatalogIncludingUnrevealedDeferredTools() {
        // A deferred tool is NOT in advertisedSpecs() until revealed, so this proves
        // the planner sees the full tools() catalog rather than the advertised subset.
        Planner capturing = capturing();
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("done"));
        var registry = DisclosingToolRegistry.builder()
                .deferred(FunctionTool.builder("send_wire", "wires money")
                        .handler(i -> ToolResult.ok("")).build())
                .build();

        new PlanningAgent(capturing, () -> new Agent(llm, registry, CONFIG)).run(Goal.of("g"));

        assertThat(captured).extracting(ToolSpec::name).contains("send_wire");
        // Sanity: the deferred tool really is hidden from the advertised set.
        assertThat(registry.advertisedSpecs()).extracting(ToolSpec::name).doesNotContain("send_wire");
    }

    @Test
    void priorStepOutputIsTruncatedInThePromptButNotInTheStoredResult() {
        String big = "x".repeat(4001);
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.text(big),        // step 1 emits an oversized output
                FakeLlmClient.text("did B"));   // step 2
        PlanningAgent agent = new PlanningAgent(fixedPlan("Do A", "Do B"), factory(llm));

        PlanExecution execution = agent.run(Goal.of("achieve X"));

        // The full output is preserved in the stored step result...
        assertThat(execution.stepResults().get(0).output()).hasSize(4001);
        // ...but step 2's threaded prompt saw a truncated copy.
        String secondStepPrompt = llm.received().get(1).messages().get(0).text();
        assertThat(secondStepPrompt).contains("[truncated]").doesNotContain(big);
    }

    private final List<ToolSpec> captured = new ArrayList<>();

    private Planner capturing() {
        return (goal, tools) -> {
            captured.addAll(tools);
            return new Plan(List.of());
        };
    }
}
