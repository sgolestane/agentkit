package dev.agentkit.host.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The rehearsals an organization's pull requests reported, in Postgres: its own, newest first, as they were sent. */
class ARehearsalReportIsKeptInPostgresTest {

    @Test
    void reportsAreReadBackNewestFirstForTheirOrganizationOnly() {
        PostgresRehearsalLog log = new PostgresRehearsalLog(TestDatabase.get());
        String org = TestDatabase.org();
        log.add(org, Map.of("org", org, "title", "first", "results", List.of()), Instant.parse("2026-09-18T10:00:00Z"));
        log.add(org, Map.of("org", org, "title", "second", "held", 2, "results",
                List.of(Map.of("agent", "onboarding", "passed", true))), Instant.parse("2026-09-18T11:00:00Z"));

        List<Map<String, Object>> reports = log.recent(org, 10);

        assertThat(reports).extracting(r -> r.get("title")).containsExactly("second", "first");
        assertThat(reports.get(0)).containsEntry("held", 2).containsEntry("receivedAt", "2026-09-18T11:00:00Z");
        assertThat(reports.get(0).get("results").toString()).contains("onboarding");
        assertThat(log.recent(org, 1)).hasSize(1);
        assertThat(log.recent(TestDatabase.org(), 10)).isEmpty();
    }
}
