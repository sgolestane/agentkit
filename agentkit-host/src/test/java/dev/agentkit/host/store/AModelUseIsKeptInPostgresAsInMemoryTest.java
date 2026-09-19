package dev.agentkit.host.store;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.host.models.Spend;
import dev.agentkit.host.models.UsageLedger;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What an organization's agents spent, in Postgres as in memory: added up by day, agent, model and payer, read back
 * over a span of days, for one organization only.
 */
class AModelUseIsKeptInPostgresAsInMemoryTest {

    private static final LocalDate SEPT_17 = LocalDate.parse("2026-09-17");
    private static final LocalDate SEPT_18 = LocalDate.parse("2026-09-18");

    @Test
    void inMemory() {
        check(UsageLedger.inMemory(), "acme");
    }

    @Test
    void inPostgres() {
        check(new PostgresUsageLedger(TestDatabase.get()), TestDatabase.org());
    }

    private static void check(UsageLedger ledger, String org) {
        ledger.add(new UsageLedger.Key(org, SEPT_17, "helpdesk", "m1", true), Spend.of(new TokenUsage(100, 10), 0.5));
        ledger.add(new UsageLedger.Key(org, SEPT_18, "helpdesk", "m1", true), Spend.of(new TokenUsage(200, 20), 1.0));
        ledger.add(new UsageLedger.Key(org, SEPT_18, "helpdesk", "m1", true), Spend.of(new TokenUsage(300, 30), 1.5));
        ledger.add(new UsageLedger.Key(org, SEPT_18, "onboarding", "m2", false), Spend.of(new TokenUsage(5, 5), 0));
        ledger.add(new UsageLedger.Key(org + "-other", SEPT_18, "helpdesk", "m1", true),
                Spend.of(new TokenUsage(9999, 9999), 99));

        assertThat(ledger.spent(org, SEPT_18, SEPT_18, false)).isEqualTo(new Spend(3, 505, 55, 2.5));
        assertThat(ledger.spent(org, SEPT_18, SEPT_18, true)).isEqualTo(new Spend(2, 500, 50, 2.5));
        assertThat(ledger.spent(org, SEPT_17, SEPT_18, true)).isEqualTo(new Spend(3, 600, 60, 3.0));
        assertThat(ledger.spent(org, SEPT_18.plusDays(1), SEPT_18.plusDays(30), false)).isEqualTo(Spend.ZERO);
        assertThat(ledger.rows(org, SEPT_17, SEPT_18)).isEqualTo(List.of(
                new UsageLedger.Row("helpdesk", "m1", true, new Spend(3, 600, 60, 3.0)),
                new UsageLedger.Row("onboarding", "m2", false, new Spend(1, 5, 5, 0))));
    }
}
