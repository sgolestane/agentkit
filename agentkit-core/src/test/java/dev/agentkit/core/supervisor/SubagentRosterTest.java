package dev.agentkit.core.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.tool.SimpleToolRegistry;
import org.junit.jupiter.api.Test;

class SubagentRosterTest {

    private static Subagent stub(String name) {
        Agent agent = new Agent(new FakeLlmClient(FakeLlmClient.text("x")),
                new SimpleToolRegistry(), AgentConfig.builder("m").build());
        return Subagent.of(name, name + " does things", agent);
    }

    @Test
    void preservesRegistrationOrder() {
        SubagentRoster roster = SubagentRoster.of(stub("a"), stub("b"), stub("c"));
        assertThat(roster.names()).containsExactly("a", "b", "c");
    }

    @Test
    void findsByName() {
        SubagentRoster roster = SubagentRoster.of(stub("researcher"));
        assertThat(roster.find("researcher")).isPresent();
        assertThat(roster.find("missing")).isEmpty();
    }

    @Test
    void rejectsDuplicateNames() {
        SubagentRoster roster = new SubagentRoster().add(stub("dup"));
        assertThatThrownBy(() -> roster.add(stub("dup")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dup");
    }

    @Test
    void catalogListsNameAndDescription() {
        SubagentRoster roster = SubagentRoster.of(stub("writer"), stub("editor"));
        assertThat(roster.catalog())
                .isEqualTo("- writer: writer does things\n- editor: editor does things");
    }

    @Test
    void emptyRosterReportsEmpty() {
        assertThat(new SubagentRoster().isEmpty()).isTrue();
        assertThat(SubagentRoster.of(stub("a")).isEmpty()).isFalse();
    }
}
