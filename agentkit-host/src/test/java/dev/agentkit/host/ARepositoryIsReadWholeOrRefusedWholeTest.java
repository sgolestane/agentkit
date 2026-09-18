package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.host.repo.AgentDefinition;
import dev.agentkit.host.repo.AgentDefinition.ToolRef;
import dev.agentkit.host.repo.DefinitionException;
import dev.agentkit.host.repo.GitVersion;
import dev.agentkit.host.repo.OrgRepo;
import dev.agentkit.host.repo.RepoLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An organization's agents are files in its repository, and a pull request is how they change. So a repository is
 * read whole — every field checked, every reference between files followed — or refused with every problem in it at
 * once, the way a check on that pull request would list them.
 */
class ARepositoryIsReadWholeOrRefusedWholeTest {

    @TempDir
    Path dir;

    @Test
    void theExampleRepositoryReadsAsWritten() {
        OrgRepo repo = RepoLoader.load(RepoFixture.copyInto(dir)
                .write("agents/.drafts/agent.yaml", "not: an agent")
                .write("connectors/.DS_Store", "")
                .root(), "abc123");

        assertThat(repo.org()).isEqualTo("acme");
        assertThat(repo.version()).isEqualTo("abc123");
        assertThat(repo.defaultModel()).isEqualTo("anthropic/claude-sonnet-5");
        assertThat(repo.directory()).hasValueSatisfying(d -> assertThat(d.tool()).isEqualTo("directory_lookup"));
        assertThat(repo.connectors()).containsOnlyKeys("helpdesk");
        assertThat(repo.connectors().get("helpdesk").server().path("url").asText()).isEqualTo("${secret:HELPDESK_URL}");
        assertThat(repo.agents()).containsOnlyKeys("helpdesk", "security-desk");

        AgentDefinition helpdesk = repo.agents().get("helpdesk");
        assertThat(helpdesk.name()).isEqualTo("IT Helpdesk");
        assertThat(helpdesk.pattern()).isEqualTo(AgentDefinition.Pattern.CHAT);
        assertThat(helpdesk.model()).isNull();
        assertThat(helpdesk.audience()).containsExactly("everyone");
        assertThat(helpdesk.systemPrompt()).startsWith("You are Acme's IT helpdesk.");
        assertThat(helpdesk.policy()).startsWith("Helpdesk policy:");
        assertThat(helpdesk.tools()).singleElement().satisfies(s -> {
            assertThat(s.connector()).isEqualTo("helpdesk");
            assertThat(s.effects()).containsExactlyInAnyOrder(ToolEffect.READ, ToolEffect.REQUEST, ToolEffect.NOTIFY,
                    ToolEffect.GRANT);
        });
        assertThat(helpdesk.confirm()).containsExactly(new ToolRef("helpdesk", "reset_mfa"));
        assertThat(helpdesk.bind()).containsEntry(new ToolRef("helpdesk", "open_ticket"), Map.of("requester", "principal.email"));
        assertThat(helpdesk.maxSteps()).isEqualTo(12);

        AgentDefinition security = repo.agents().get("security-desk");
        assertThat(security.audience()).containsExactly("security");
        assertThat(security.policy()).isEmpty();
        assertThat(security.tools().get(0).tools()).containsExactly("directory_lookup");
    }

    @Test
    void everyProblemIsReportedTogetherWithWhereItIs() {
        RepoFixture repo = RepoFixture.copyInto(dir)
                .edit("agents/helpdesk/agent.yaml", "confirm:", "confrim:")
                .edit("agents/helpdesk/agent.yaml", "effects: [read, request, notify, grant]", "effects: [read, delete]")
                .edit("agents/helpdesk/agent.yaml", "principal.email}\n  helpdesk/reset_mfa", "the model}\n  helpdesk/reset_mfa")
                .edit("agents/security-desk/agent.yaml", "system: system.md", "system: missing.md")
                .write("connectors/billing.yaml", "url: https://billing.example.com/mcp\ncommand: [billing]\n")
                .write("agents/Bad_Name/agent.yaml", "name: x\n");

        assertThatThrownBy(() -> RepoLoader.load(repo.root(), "v"))
                .isInstanceOfSatisfying(DefinitionException.class, e -> assertThat(e.problems())
                        .extracting(Object::toString)
                        .anySatisfy(p -> assertThat(p).startsWith("agents/helpdesk/agent.yaml confrim: is not a field here"))
                        .anySatisfy(p -> assertThat(p).startsWith("agents/helpdesk/agent.yaml tools[0].effects: unknown effect \"delete\""))
                        .anySatisfy(p -> assertThat(p).startsWith("agents/helpdesk/agent.yaml bind.helpdesk/open_ticket.requester: must name a field"))
                        .anySatisfy(p -> assertThat(p).isEqualTo("agents/security-desk/agent.yaml prompt.system: missing.md does not exist"))
                        .anySatisfy(p -> assertThat(p).isEqualTo("connectors/billing.yaml: needs exactly one of url or command"))
                        .anySatisfy(p -> assertThat(p).startsWith("agents/Bad_Name: an agent's id is"))
                        .hasSize(6));
    }

    @Test
    void aPromptFileCannotReachOutsideTheRepository(@TempDir Path elsewhere) throws Exception {
        Files.writeString(elsewhere.resolve("secret.txt"), "the operator's private notes");
        RepoFixture repo = RepoFixture.copyInto(dir)
                .edit("agents/security-desk/agent.yaml", "system: system.md", "system: ../../" + dir.relativize(elsewhere)
                        .resolve("secret.txt"));
        Files.createSymbolicLink(dir.resolve("agents/helpdesk/linked.md"), elsewhere.resolve("secret.txt"));
        repo.edit("agents/helpdesk/agent.yaml", "policy: policy.md", "policy: linked.md");

        assertThatThrownBy(() -> RepoLoader.load(repo.root(), "v"))
                .isInstanceOfSatisfying(DefinitionException.class, e -> assertThat(e.problems())
                        .extracting(DefinitionException.Problem::where)
                        .containsExactlyInAnyOrder("prompt.system", "prompt.policy"));
    }

    @Test
    void referencesMustNameAConnectorAndAConfirmationMustNameATool() {
        RepoFixture repo = RepoFixture.copyInto(dir)
                .edit("agents/helpdesk/agent.yaml", "  - helpdesk/reset_mfa", "  - helpdesk/*\n  - crm/update")
                .edit("org.yaml", "connector: helpdesk", "connector: hr");

        assertThatThrownBy(() -> RepoLoader.load(repo.root(), "v"))
                .isInstanceOfSatisfying(DefinitionException.class, e -> assertThat(e.problems())
                        .extracting(Object::toString)
                        .containsExactlyInAnyOrder(
                                "org.yaml directory.connector: no connector named hr",
                                "agents/helpdesk/agent.yaml confirm[0]: name the tool; helpdesk/* would cover tools nobody has read",
                                "agents/helpdesk/agent.yaml confirm[1]: no connector named crm"));
    }

    @Test
    void anAgentThatSaysNothingAboutItsAudienceIsForEveryoneAndOneThatNamesNoGroupIsRefused() {
        RepoFixture repo = RepoFixture.copyInto(dir).edit("agents/helpdesk/agent.yaml", "audience: [everyone]\n", "");
        assertThat(RepoLoader.load(repo.root(), "v").agents().get("helpdesk").audience()).containsExactly("everyone");

        repo.edit("agents/security-desk/agent.yaml", "audience: [security]", "audience: []");
        assertThatThrownBy(() -> RepoLoader.load(repo.root(), "v"))
                .isInstanceOfSatisfying(DefinitionException.class, e -> assertThat(e.problems())
                        .extracting(Object::toString).containsExactly(
                                "agents/security-desk/agent.yaml audience: admits nobody; say everyone, or name groups"));
    }

    @Test
    void deferredWorkNamesItsPromptItsActorAndHowEachSubjectIsLookedUp() {
        RepoFixture repo = RepoFixture.copyInto(dir)
                .write("agents/helpdesk/deferred.md", "Carry out one deferred action.")
                .edit("agents/helpdesk/agent.yaml", "limits:", """
                        deferred:
                          prompt: deferred.md
                          actor: helpdesk
                          subjects:
                            ticket: {tool: helpdesk/get_ticket, argument: ticket_id}
                        limits:""");
        AgentDefinition.Deferred deferred = RepoLoader.load(repo.root(), "v").agents().get("helpdesk").deferred();
        assertThat(deferred.prompt()).isEqualTo("Carry out one deferred action.");
        assertThat(deferred.actor()).isEqualTo("helpdesk");
        assertThat(deferred.subjects()).containsEntry("ticket",
                new AgentDefinition.Subject(new ToolRef("helpdesk", "get_ticket"), "ticket_id"));

        repo.edit("agents/helpdesk/agent.yaml", "actor: helpdesk", "actor: bot@acme.example")
                .edit("agents/helpdesk/agent.yaml", "helpdesk/get_ticket", "crm/get_ticket");
        assertThatThrownBy(() -> RepoLoader.load(repo.root(), "v"))
                .isInstanceOfSatisfying(DefinitionException.class, e -> assertThat(e.problems())
                        .extracting(Object::toString).containsExactlyInAnyOrder(
                                "agents/helpdesk/agent.yaml deferred.actor: is the agent's own identity, not a person's email",
                                "agents/helpdesk/agent.yaml deferred.subjects.ticket.tool: no connector named crm"));
    }

    @Test
    void theVersionIsTheCommitAndSaysSoWhenTheCheckoutHasChanged() throws Exception {
        RepoFixture repo = RepoFixture.copyInto(dir);
        assertThat(GitVersion.of(repo.root())).isEqualTo(GitVersion.UNVERSIONED);

        git("init", "-q");
        git("add", ".");
        git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "agents");
        String head = GitVersion.of(repo.root());
        assertThat(head).matches("[0-9a-f]{40}");

        repo.edit("agents/helpdesk/policy.md", "Helpdesk policy:", "Helpdesk policy (draft):");
        String dirty = GitVersion.of(repo.root());
        assertThat(dirty).matches(head + "-dirty-[0-9a-f]{12}");
        assertThat(GitVersion.of(repo.root())).as("the same changes are the same version").isEqualTo(dirty);

        repo.write("agents/helpdesk/notes.md", "an untracked file is a change too");
        assertThat(GitVersion.of(repo.root())).startsWith(head + "-dirty-").isNotEqualTo(dirty);
    }

    private void git(String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
    }
}
