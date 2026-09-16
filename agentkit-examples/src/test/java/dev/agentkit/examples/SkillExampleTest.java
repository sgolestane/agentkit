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

/** Drives {@link SkillExample} against a scripted fake model. */
class SkillExampleTest {

    private static final String MODEL = "fake-model";

    @Test
    void readsTheRelevantSkillThenAnswers() {
        // Turn 1: the model picks the haiku skill from the tier-1 catalog and reads it.
        // Turn 2: with the tier-2 instructions in context, it produces the haiku.
        FakeLlm llm = new FakeLlm()
                .then(FakeLlm.toolUse("r1", "read_skill", Map.of("name", "haiku")))
                .then(FakeLlm.text("Ledger holds the state\nCrash and wake, the steps resume\nNothing lost to time"));

        // Capture tool results so we can prove read_skill actually executed and
        // returned the tier-2 body (rather than just replaying the script).
        List<ToolResult> skillReads = new ArrayList<>();
        AgentObserver observer = new AgentObserver() {
            @Override
            public void onToolResult(AgentRun run, int step,
                                     ToolInvocation proposed, ToolInvocation invocation,
                                     ToolResult result,
                                     dev.agentkit.core.tool.Disposition disposition) {
                if (invocation.name().equals("read_skill")) {
                    skillReads.add(result);
                }
            }
        };

        Agent agent = SkillExample.build(llm, MODEL, observer);
        AgentResult result = agent.run(Goal.of("Write a haiku about durable execution."));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).contains("steps resume");
        assertThat(llm.remaining()).isZero(); // the script was neither too short nor too long

        // read_skill ran once, did not error, and delivered the haiku skill's tier-2 body.
        assertThat(skillReads).hasSize(1);
        assertThat(skillReads.get(0).isError()).isFalse();
        assertThat(skillReads.get(0).content()).contains("5, 7, 5 syllable structure");
    }

    @Test
    void tier1CatalogListsBothSkillsWithoutTheirInstructions() {
        String prompt = dev.agentkit.core.skill.Skills.systemPrompt("base", SkillExample.library());

        // Metadata (name: description) is disclosed up front...
        assertThat(prompt).contains("haiku").contains("limerick")
                .contains("Write a haiku about a given topic");
        // ...but the tier-2 body is not — it only arrives via read_skill.
        assertThat(prompt).doesNotContain("5, 7, 5 syllable structure");
    }
}
