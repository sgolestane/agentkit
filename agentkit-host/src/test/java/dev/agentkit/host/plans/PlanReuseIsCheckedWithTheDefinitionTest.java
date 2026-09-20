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

    @Test
    void aCheckBeforePlanningIsForAPlanExecuteAgentAndNamesItsFormsFields() throws Exception {
        Files.createDirectories(dir.resolve("agents/desk"));
        Files.createDirectories(dir.resolve("connectors"));
        Files.writeString(dir.resolve("org.yaml"), "org: acme\nmodel: m\n");
        Files.writeString(dir.resolve("connectors/helpdesk.yaml"), "url: https://helpdesk.acme.example/mcp\n");
        Files.writeString(dir.resolve("agents/desk/system.md"), "Help.");
        Files.writeString(dir.resolve("agents/desk/agent.yaml"), """
                name: Desk
                prompt: {system: system.md}
                tools: [{connector: helpdesk, effects: [read]}]
                before:
                  - {tool: helpdesk/directory_lookup, with: {email: principal.email}}
                  - {tool: nowhere/lookup, with: {email: input.email}, when: always}
                """);

        assertThatThrownBy(() -> RepoLoader.load(dir, "v1")).isInstanceOf(DefinitionException.class)
                .satisfies(e -> assertThat(((DefinitionException) e).problems()).extracting(Object::toString)
                        .containsExactlyInAnyOrder(
                                "agents/desk/agent.yaml before: is for a plan-execute agent: it is checked before the "
                                        + "plan is made",
                                "agents/desk/agent.yaml before: needs input: a check is made with the fields of the task",
                                "agents/desk/agent.yaml before[0].with.email: \"principal.email\" is not input.<field> "
                                        + "of a field of the agent's input",
                                "agents/desk/agent.yaml before[1].when: is not a field here; the fields are [tool, with]",
                                "agents/desk/agent.yaml before[1].tool: no connector named nowhere",
                                "agents/desk/agent.yaml before[1].with.email: \"input.email\" is not input.<field> "
                                        + "of a field of the agent's input"));
    }
}
