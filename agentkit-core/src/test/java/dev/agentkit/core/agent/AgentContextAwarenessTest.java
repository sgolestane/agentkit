package dev.agentkit.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentContextAwarenessTest {

    private static Agent agent(FakeLlmClient llm, boolean contextAware, AgentConfig config, Tool... tools) {
        return Agent.builder(llm, new SimpleToolRegistry(java.util.List.of(tools)), config)
                .contextAwareness(contextAware)
                .build();
    }

    private static String systemOf(FakeLlmClient llm, int requestIndex) {
        return llm.received().get(requestIndex).system().orElse("");
    }

    @Test
    void disabledByDefaultLeavesTheSystemPromptUntouched() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("done"));
        AgentConfig config = AgentConfig.builder("m").explainFencedContent(false)
                .systemPrompt("You are helpful.").maxSteps(5).build();

        Agent.builder(llm, new SimpleToolRegistry(), config).build().run(Goal.of("go"));

        assertThat(systemOf(llm, 0)).isEqualTo("You are helpful.");
        assertThat(systemOf(llm, 0)).doesNotContain("[Budget]");
    }

    @Test
    void onFullDefaultsTheSpotlightClauseAndTheBudgetNoteBothLandInOrder() {
        // The rest of this class opts out of the spotlight clause so its assertions stay
        // about the budget note alone. That leaves the configuration every user actually
        // gets — both features on — asserted nowhere, which is how "the tests were amended
        // until they passed" turns into an untested default. This is that configuration.
        Tool noop = FunctionTool.builder("noop", "no-op").handler(i -> ToolResult.ok("ok")).build();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "noop", Map.of()), FakeLlmClient.text("done"));
        AgentConfig config = AgentConfig.builder("m").systemPrompt("You are helpful.").maxSteps(5).build();

        agent(llm, true, config, noop).run(Goal.of("go"));

        String system = systemOf(llm, 0);
        assertThat(system).startsWith("You are helpful.");
        assertThat(system.indexOf(Spotlight.INSTRUCTION))
                .as("the operator's prompt keeps the cacheable prefix; the clause follows it")
                .isGreaterThan(system.indexOf("You are helpful."));
        assertThat(system.indexOf("[Budget]"))
                .as("the per-turn note is last, since it is the only part that changes")
                .isGreaterThan(system.indexOf(Spotlight.INSTRUCTION));
        // Turn 2 moves the note and nothing else.
        assertThat(systemOf(llm, 1)).contains("step 2 of 5").contains(Spotlight.INSTRUCTION);
    }

    @Test
    void withNoSystemPromptTheClauseIsStillDelivered() {
        // Previously a null system prompt meant no system prompt at all. It now carries the
        // clause, because a knowledge passage or a skill catalog can reach an agent whose
        // own prompt was never set, and a fence nothing explains is decoration.
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("done"));
        AgentConfig config = AgentConfig.builder("m").maxSteps(3).build();

        Agent.builder(llm, new SimpleToolRegistry(), config).build().run(Goal.of("go"));

        assertThat(systemOf(llm, 0)).isEqualTo(Spotlight.INSTRUCTION);
    }

    @Test
    void appendsAStepBudgetNoteToTheSystemPrompt() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("done"));
        AgentConfig config = AgentConfig.builder("m").explainFencedContent(false)
                .systemPrompt("You are helpful.").maxSteps(5).build();

        agent(llm, true, config).run(Goal.of("go"));

        String system = systemOf(llm, 0);
        assertThat(system).startsWith("You are helpful.");
        assertThat(system).contains("[Budget]").contains("step 1 of 5").contains("4 step(s) remaining");
    }

    @Test
    void theBudgetNoteUpdatesEachTurn() {
        Tool noop = FunctionTool.builder("noop", "no-op").handler(i -> ToolResult.ok("ok")).build();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "noop", Map.of()), // step 1 calls a tool
                FakeLlmClient.text("done"));                    // step 2 finishes
        AgentConfig config = AgentConfig.builder("m").explainFencedContent(false).maxSteps(5).build();

        agent(llm, true, config, noop).run(Goal.of("go"));

        assertThat(systemOf(llm, 0)).contains("step 1 of 5").contains("4 step(s) remaining");
        assertThat(systemOf(llm, 1)).contains("step 2 of 5").contains("3 step(s) remaining");
    }

    @Test
    void theFinalStepGetsAWrapUpInstruction() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("done"));
        // turn 1 is the last
        AgentConfig config = AgentConfig.builder("m").explainFencedContent(false).maxSteps(1).build();

        agent(llm, true, config).run(Goal.of("go"));

        assertThat(systemOf(llm, 0)).contains("last step").contains("final answer now");
    }

    @Test
    void theWrapUpInstructionAppearsOnlyOnTheFinalStepMidRun() {
        Tool noop = FunctionTool.builder("noop", "no-op").handler(i -> ToolResult.ok("ok")).build();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "noop", Map.of()), // turn 1 of 2
                FakeLlmClient.text("done"));                    // turn 2 of 2 = final
        AgentConfig config = AgentConfig.builder("m").explainFencedContent(false).maxSteps(2).build();

        agent(llm, true, config, noop).run(Goal.of("go"));

        assertThat(systemOf(llm, 0)).contains("step 1 of 2").doesNotContain("last step");
        assertThat(systemOf(llm, 1)).contains("last step").contains("final answer now");
    }

    @Test
    void aBlankBaseSystemPromptIsReplacedByTheNoteAlone() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("done"));
        AgentConfig config = AgentConfig.builder("m").explainFencedContent(false)
                .systemPrompt("   ").maxSteps(3).build();

        agent(llm, true, config).run(Goal.of("go"));

        // A whitespace-only base prompt is dropped; the note stands alone (no leading blank).
        assertThat(systemOf(llm, 0)).startsWith("[Budget]").contains("step 1 of 3");
    }

    @Test
    void theNoteDoesNotLeakIntoThePersistedConversation() {
        Tool noop = FunctionTool.builder("noop", "no-op").handler(i -> ToolResult.ok("ok")).build();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "noop", Map.of()),
                FakeLlmClient.text("done"));
        AgentConfig config = AgentConfig.builder("m").explainFencedContent(false).maxSteps(5).build();

        agent(llm, true, config, noop).run(Goal.of("go"));

        // The budget note lives in the system prompt only — no message in the second
        // turn's conversation history carries it.
        boolean anyMessageHasNote = llm.received().get(1).messages().stream()
                .anyMatch(m -> m.text().contains("[Budget]"));
        assertThat(anyMessageHasNote).isFalse();
    }

    @Test
    void withNoBaseSystemPromptTheNoteBecomesTheSystemPrompt() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("done"));
        // no system prompt
        AgentConfig config = AgentConfig.builder("m").explainFencedContent(false).maxSteps(3).build();

        agent(llm, true, config).run(Goal.of("go"));

        assertThat(systemOf(llm, 0)).startsWith("[Budget]").contains("step 1 of 3");
    }
}
