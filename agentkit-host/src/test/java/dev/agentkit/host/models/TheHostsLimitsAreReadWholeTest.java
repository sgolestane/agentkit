package dev.agentkit.host.models;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.reliability.ModelPricing;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The operator's limits file: prices, a default for every organization, and an organization's own entry overriding
 * only what it names. A file with mistakes is refused whole, naming each.
 */
class TheHostsLimitsAreReadWholeTest {

    @TempDir
    Path dir;

    @Test
    void anOrganizationsEntryOverridesTheDefaultWhereItSaysAndKeepsTheRest() throws Exception {
        Path file = Files.writeString(dir.resolve("limits.yaml"), """
                prices:
                  anthropic/claude-sonnet-5: {input: 3, output: 15}
                  some/free-model: {input: 0, output: 0}
                default:
                  concurrentCalls: 4
                  budget: {usdPerMonth: 100, tokensPerDay: 1000000}
                orgs:
                  acme:
                    budget: {usdPerMonth: 500}
                  globex:
                    concurrentCalls: 16
                """);

        HostLimits limits = HostLimits.load(file);

        assertThat(limits.of("acme")).isEqualTo(new HostLimits.Limits(4, new Budget(1_000_000, 0, 0, 500)));
        assertThat(limits.of("globex")).isEqualTo(new HostLimits.Limits(16, new Budget(1_000_000, 0, 0, 100)));
        assertThat(limits.of("initech")).isEqualTo(new HostLimits.Limits(4, new Budget(1_000_000, 0, 0, 100)));
        assertThat(limits.price("anthropic/claude-sonnet-5")).hasValue(ModelPricing.of(3, 15));
        assertThat(limits.price("some/free-model")).hasValue(ModelPricing.of(0, 0));
        assertThat(limits.price("unknown/model")).isEmpty();
        assertThat(HostLimits.none().of("acme")).isEqualTo(new HostLimits.Limits(
                HostLimits.DEFAULT_CONCURRENT_CALLS, Budget.NONE));
        assertThat(new Budget(1_000_000, 0, 0, 500).described()).containsExactly("1,000,000 tokens a day",
                "$500.00 a month");
    }

    @Test
    void aFileWithMistakesIsRefusedNamingEach() throws Exception {
        Path file = Files.writeString(dir.resolve("limits.yaml"), """
                prices:
                  anthropic/claude-sonnet-5: {input: -3, output: 15}
                default:
                  concurrentCalls: 0
                  budget: {usdPerMonth: lots}
                orgs:
                  acme:
                    budgets: {usdPerMonth: 500}
                """);

        assertThatThrownBy(() -> HostLimits.load(file)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prices.anthropic/claude-sonnet-5.input must be a number of dollars, zero or more")
                .hasMessageContaining("default.concurrentCalls must be a positive whole number")
                .hasMessageContaining("default.budget.usdPerMonth must be a positive number")
                .hasMessageContaining("orgs.acme.budgets is not a field here; the fields are [budget, concurrentCalls]");
    }
}
