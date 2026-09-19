package dev.agentkit.host.plans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.host.repo.DefinitionException;
import dev.agentkit.host.repo.RepoLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code plans.reuse} is for a plan-execute agent started from its form, and names only the form's fields. */
class PlanReuseIsCheckedWithTheDefinitionTest {

    @TempDir
    Path dir;

    @Test
    void aChatAgentWithoutAFormCannotReuseAPlan() throws Exception {
        Files.createDirectories(dir.resolve("agents/desk"));
        Files.createDirectories(dir.resolve("connectors"));
        Files.writeString(dir.resolve("org.yaml"), "org: acme\nmodel: m\n");
        Files.writeString(dir.resolve("connectors/helpdesk.yaml"), "url: https://helpdesk.acme.example/mcp\n");
        Files.writeString(dir.resolve("agents/desk/system.md"), "Help.");
        Files.writeString(dir.resolve("agents/desk/agent.yaml"), """
                name: Desk
                prompt: {system: system.md}
                tools: [{connector: helpdesk, effects: [read]}]
                plans:
                  reuse: {after: 1, sameWhen: [office], recheck: 3}
                """);

        assertThatThrownBy(() -> RepoLoader.load(dir, "v1")).isInstanceOf(DefinitionException.class)
                .satisfies(e -> assertThat(((DefinitionException) e).problems()).extracting(Object::toString)
                        .containsExactlyInAnyOrder(
                                "agents/desk/agent.yaml plans.reuse.recheck: is not a field here; the fields are "
                                        + "[after, recheckEvery, sameWhen]",
                                "agents/desk/agent.yaml plans.reuse: is for a plan-execute agent: only it makes a plan "
                                        + "to reuse",
                                "agents/desk/agent.yaml plans.reuse: needs input: a plan is reused for a task started "
                                        + "from the agent's form, whose fields say which tasks are alike",
                                "agents/desk/agent.yaml plans.reuse.after: is from 2 to 10: one plan is an anecdote"));
    }
}
