package dev.agentkit.host.store;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.host.routing.RoutingLog;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Where messages went, and the ones sent again to another agent, are kept in Postgres as in memory. */
class RoutingIsKeptInPostgresAsInMemoryTest {

    @Test
    void inMemory() {
        check(RoutingLog.inMemory(), "acme");
    }

    @Test
    void inPostgres() {
        check(new PostgresRoutingLog(TestDatabase.get()), TestDatabase.org());
    }

    private static void check(RoutingLog log, String org) {
        Instant at = Instant.parse("2026-09-19T10:00:00Z");
        log.add(org, new RoutingLog.Route(at, org + "/sam@acme.example", "c1", "t1", "My laptop", "helpdesk", "agent",
                "a device", 10, 2));
        log.add(org, new RoutingLog.Route(at.plusSeconds(1), org + "/sam@acme.example", "c1", "t2", "Hi", null,
                "answer", "a greeting", 5, 1));
        String id = "t-" + System.nanoTime();
        log.addMisroute(org, new RoutingLog.Misroute(id, at.plusSeconds(2), org + "/sam@acme.example", "c1",
                "Who is Dana's manager?", null, "security-desk",
                List.of(new RoutingLog.Before("My laptop", "helpdesk", "On it."))));

        assertThat(log.recent(org, 2)).extracting(RoutingLog.Route::turn).containsExactly("t2", "t1");
        assertThat(log.recent(org, 2).get(0).to()).isNull();
        assertThat(log.misroutes(org, 1)).singleElement().satisfies(misroute -> {
            assertThat(misroute.id()).isEqualTo(id);
            assertThat(misroute.routedTo()).isNull();
            assertThat(misroute.before()).containsExactly(new RoutingLog.Before("My laptop", "helpdesk", "On it."));
        });
    }
}
