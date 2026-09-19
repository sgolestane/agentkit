package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.host.repo.DefinitionException;
import dev.agentkit.host.repo.OrgRepo;
import dev.agentkit.host.repo.RepoLoader;
import dev.agentkit.host.repo.RoutingCase;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code router} in org.yaml and the cases in routing.yaml are read with the rest of the repository. */
class RoutingIsSetInTheRepositoryTest {

    @TempDir
    Path dir;

    @Test
    void theRouterAndItsCasesAreRead() {
        RepoFixture repo = RepoFixture.copyInto(dir);
        repo.write("org.yaml", repo.read("org.yaml") + "\nrouter:\n  model: small/model\n  prompt: routing.md\n")
                .write("routing.md", "Anything about payroll goes to the security desk.")
                .write("routing.yaml", """
                        cases:
                          - name: laptop
                            as: sam.okafor@acme.example
                            say: My laptop will not boot
                            expect: {agent: helpdesk}
                          - name: follow-up
                            as: sam.okafor@acme.example
                            before:
                              - {say: "Look up Dana", agent: security-desk, answer: "Which Dana?"}
                            say: Dana Kim
                            expect: {agent: security-desk}
                          - name: what
                            as: sam.okafor@acme.example
                            say: What can you do?
                            expect: {answers: true}
                        """);

        OrgRepo loaded = RepoLoader.load(repo.root(), "v1");

        assertThat(loaded.router()).isEqualTo(new OrgRepo.RouterSpec(true, "small/model",
                "Anything about payroll goes to the security desk."));
        assertThat(loaded.routing()).extracting(RoutingCase::name).containsExactly("laptop", "follow-up", "what");
        assertThat(loaded.routing().get(1).before()).singleElement()
                .isEqualTo(new RoutingCase.Earlier("Look up Dana", "security-desk", "Which Dana?"));
        assertThat(loaded.routing().get(2).expect().describe()).isEqualTo("the router answers");
        assertThat(RepoLoader.load(RepoFixture.copyInto(dir.resolve("plain")).root(), "v1").router())
                .isEqualTo(OrgRepo.RouterSpec.DEFAULT);
    }

    @Test
    void aCaseForAnAgentThatDoesNotExistOrExpectingTwoThingsIsRefused() {
        RepoFixture repo = RepoFixture.copyInto(dir);
        repo.write("routing.yaml", """
                cases:
                  - name: nobody
                    as: sam.okafor@acme.example
                    say: Payroll please
                    expect: {agent: payroll}
                  - name: both
                    as: sam.okafor@acme.example
                    say: Hello
                    expect: {answers: true, asks: true}
                """);

        assertThatThrownBy(() -> RepoLoader.load(repo.root(), "v1")).isInstanceOf(DefinitionException.class)
                .satisfies(e -> assertThat(((DefinitionException) e).problems()).extracting(Object::toString)
                        .containsExactlyInAnyOrder(
                                "routing.yaml cases[0].expect.agent: there is no agent payroll",
                                "routing.yaml cases[1].expect: is exactly one of {agent: <id>}, {answers: true}, "
                                        + "{asks: true} or {form: <id>}"));
    }
}
