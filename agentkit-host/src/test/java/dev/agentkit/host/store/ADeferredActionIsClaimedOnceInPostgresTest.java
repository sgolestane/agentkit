package dev.agentkit.host.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.deferred.DeferredAction;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * One agent's deferred actions in Postgres: kept per organization and agent, claimed by one host however many sweep at
 * once, and due again when a host that claimed one stopped before it finished.
 */
class ADeferredActionIsClaimedOnceInPostgresTest {

    private static final Instant NOW = Instant.parse("2026-12-31T00:00:00Z");

    private static DeferredAction action(String id, Instant runAt) {
        return new DeferredAction(id, "worker", "W-1002", runAt, "at termination_date", "Remove Marcus's access.",
                NOW.minus(Duration.ofDays(90)), "lena.ortiz@acme.example", DeferredAction.Status.SCHEDULED, "", null);
    }

    @Test
    void anActionIsKeptForItsOrganizationAndAgentAlone() {
        Database database = TestDatabase.get();
        String org = TestDatabase.org();
        PostgresDeferredActionStore onboarding = new PostgresDeferredActionStore(database, org, "onboarding");
        PostgresDeferredActionStore desk = new PostgresDeferredActionStore(database, org, "access-desk");
        PostgresDeferredActionStore otherOrg = new PostgresDeferredActionStore(database, TestDatabase.org(), "onboarding");

        assertThat(onboarding.put(action("later", NOW.plusSeconds(60)))).isFalse();
        assertThat(onboarding.put(action("now", NOW))).isFalse();
        assertThat(onboarding.put(action("now", NOW.minusSeconds(60)))).isTrue();

        assertThat(onboarding.all()).extracting(DeferredAction::id).containsExactly("now", "later");
        assertThat(onboarding.get("now")).hasValueSatisfying(a -> {
            assertThat(a.runAt()).isEqualTo(NOW.minusSeconds(60));
            assertThat(a.goal()).isEqualTo("Remove Marcus's access.");
            assertThat(a.scheduledBy()).isEqualTo("lena.ortiz@acme.example");
        });
        assertThat(onboarding.due(NOW)).extracting(DeferredAction::id).containsExactly("now");
        assertThat(desk.all()).isEmpty();
        assertThat(otherOrg.all()).isEmpty();
        assertThat(desk.claim("now", NOW)).isFalse();
    }

    @Test
    void oneOfManyHostsClaimsItAndItFinishesOnce() throws Exception {
        Database database = TestDatabase.get();
        String org = TestDatabase.org();
        new PostgresDeferredActionStore(database, org, "onboarding").put(action("offboard", NOW));

        int hosts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(hosts);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> claims = new ArrayList<>();
        for (int i = 0; i < hosts; i++) {
            PostgresDeferredActionStore host = new PostgresDeferredActionStore(database, org, "onboarding");
            claims.add(pool.submit(() -> {
                go.await();
                return host.claim("offboard", NOW);
            }));
        }
        go.countDown();
        int won = 0;
        for (Future<Boolean> claim : claims) {
            won += claim.get() ? 1 : 0;
        }
        pool.shutdown();

        PostgresDeferredActionStore store = new PostgresDeferredActionStore(database, org, "onboarding");
        assertThat(won).isEqualTo(1);
        assertThat(store.get("offboard").orElseThrow().status()).isEqualTo(DeferredAction.Status.RUNNING);
        assertThat(store.due(NOW)).isEmpty();
        assertThatThrownBy(() -> store.put(action("offboard", NOW))).isInstanceOf(IllegalStateException.class);

        assertThat(store.finish("offboard", true, "Removed.", NOW.plusSeconds(30))).isTrue();
        assertThat(store.finish("offboard", false, "Again.", NOW.plusSeconds(40))).isFalse();
        assertThat(store.get("offboard")).hasValueSatisfying(a -> {
            assertThat(a.status()).isEqualTo(DeferredAction.Status.DONE);
            assertThat(a.outcome()).isEqualTo("Removed.");
            assertThat(a.finishedAt()).isEqualTo(NOW.plusSeconds(30));
        });
    }

    @Test
    void anActionWhoseHostStoppedIsDueAgainOnceTheLeaseRunsOut() {
        Database database = TestDatabase.get();
        String org = TestDatabase.org();
        PostgresDeferredActionStore store = new PostgresDeferredActionStore(database, org, "onboarding",
                Duration.ofMinutes(10));
        store.put(action("offboard", NOW));
        assertThat(store.claim("offboard", NOW)).isTrue();

        assertThat(store.due(NOW.plus(Duration.ofMinutes(9)))).isEmpty();
        assertThat(store.claim("offboard", NOW.plus(Duration.ofMinutes(9)))).isFalse();
        assertThat(store.due(NOW.plus(Duration.ofMinutes(11)))).extracting(DeferredAction::id).containsExactly("offboard");
        assertThat(store.claim("offboard", NOW.plus(Duration.ofMinutes(11)))).isTrue();
        assertThat(store.claim("offboard", NOW.plus(Duration.ofMinutes(12)))).isFalse();
    }

    @Test
    void theVersionsAnOrganizationWasLoadedAtAreListedNewestFirst() {
        PostgresVersionLog log = new PostgresVersionLog(TestDatabase.get());
        String org = TestDatabase.org();
        log.loaded(org, "v1", NOW);
        log.loaded(org, "v2", NOW.plusSeconds(60));
        log.loaded(org, "v3", NOW.plusSeconds(120));
        log.loaded(org, "v1", NOW.plusSeconds(180));

        assertThat(log.recent(org, 2)).containsExactly("v1", "v3");
        assertThat(log.recent(org, 10)).containsExactly("v1", "v3", "v2");
        assertThat(log.recent(TestDatabase.org(), 10)).isEmpty();
    }
}
