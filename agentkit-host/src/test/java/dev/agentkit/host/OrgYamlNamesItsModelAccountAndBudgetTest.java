package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.host.models.Budget;
import dev.agentkit.host.repo.DefinitionException;
import dev.agentkit.host.repo.OrgRepo;
import dev.agentkit.host.repo.RepoLoader;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code org.yaml} may name the organization's own model provider — its key is a secret, never in the file — and a
 * budget of its own, checked with the rest of the repository.
 */
class OrgYamlNamesItsModelAccountAndBudgetTest {

    @TempDir
    Path dir;

    @Test
    void aProviderAndABudgetAreReadWithTheRest() {
        RepoFixture repo = RepoFixture.copyInto(dir);
        repo.write("org.yaml", repo.read("org.yaml") + """

                provider: anthropic
                budget:
                  tokensPerDay: 2000000
                  usdPerMonth: 250.5
                """);

        OrgRepo loaded = RepoLoader.load(repo.root(), "v1");

        assertThat(loaded.provider()).hasValue("anthropic");
        assertThat(loaded.budget()).isEqualTo(new Budget(2_000_000, 0, 0, 250.5));
        OrgRepo plain = RepoLoader.load(RepoFixture.copyInto(dir.resolve("plain")).root(), "v1");
        assertThat(plain.provider()).isEqualTo(Optional.empty());
        assertThat(plain.budget()).isEqualTo(Budget.NONE);
    }

    @Test
    void anUnknownProviderOrAWrongBudgetIsRefused() {
        RepoFixture repo = RepoFixture.copyInto(dir);
        repo.write("org.yaml", repo.read("org.yaml") + """

                provider: openai
                budget:
                  tokensPerWeek: 5
                  usdPerDay: -1
                """);

        assertThatThrownBy(() -> RepoLoader.load(repo.root(), "v1")).isInstanceOf(DefinitionException.class)
                .satisfies(e -> assertThat(((DefinitionException) e).problems()).extracting(Object::toString)
                        .containsExactlyInAnyOrder(
                                "org.yaml provider: is one of anthropic, openrouter, with the organization's "
                                        + "MODEL_API_KEY secret as its key; leave it out to run on the host's account",
                                "org.yaml budget.tokensPerWeek: is not a field here; the fields are [tokensPerDay, "
                                        + "tokensPerMonth, usdPerDay, usdPerMonth]",
                                "org.yaml budget.usdPerDay: must be a positive number"));
    }
}
