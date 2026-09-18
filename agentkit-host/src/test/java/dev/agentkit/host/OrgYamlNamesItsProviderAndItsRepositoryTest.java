package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.host.repo.DefinitionException;
import dev.agentkit.host.repo.OrgRepo;
import dev.agentkit.host.repo.RepoLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code org.yaml} names where the organization's people sign in, who administers its agents, and where changes to them
 * are proposed — checked like everything else in the repository, with every problem at once.
 */
class OrgYamlNamesItsProviderAndItsRepositoryTest {

    @TempDir
    Path dir;

    @Test
    void itIsReadWithTheRestOfTheRepository() {
        RepoFixture repo = RepoFixture.copyInto(dir);
        repo.write("org.yaml", repo.read("org.yaml") + """

                admins: [security]
                signIn:
                  issuer: https://login.acme.example/
                  clientId: agentkit-host
                repository:
                  github: acme/agents
                  path: /orgs/acme/
                """);

        OrgRepo loaded = RepoLoader.load(repo.root(), "v1");

        assertThat(loaded.admins()).containsExactly("security");
        assertThat(loaded.signIn()).hasValue(new OrgRepo.SignInSpec("https://login.acme.example", "agentkit-host",
                "email", ""));
        assertThat(loaded.repository()).hasValue(new OrgRepo.RepositorySpec("acme/agents", "orgs/acme", "main",
                "https://api.github.com"));
    }

    @Test
    void aProviderOverPlainHttpOrARepositoryOutsideItselfIsRefused() {
        RepoFixture repo = RepoFixture.copyInto(dir);
        repo.write("org.yaml", repo.read("org.yaml") + """

                signIn:
                  issuer: http://login.acme.example
                  client: agentkit-host
                repository:
                  github: not a repository
                """);

        assertThatThrownBy(() -> RepoLoader.load(repo.root(), "v1")).isInstanceOf(DefinitionException.class)
                .satisfies(e -> assertThat(((DefinitionException) e).problems()).extracting(Object::toString)
                        .containsExactlyInAnyOrder(
                                "org.yaml signIn.client: is not a field here; the fields are [clientId, emailClaim, issuer, mcpAudience]",
                                "org.yaml signIn.issuer: is an https URL: an identity provider is only trusted over TLS",
                                "org.yaml repository.github: is owner/name, such as acme/agents"));
    }
}
