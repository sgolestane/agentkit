package dev.agentkit.host.store;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.host.auth.SessionStore;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Sessions and sign-ins in progress, in Postgres as in memory: kept until they expire, taken once, removed on
 * sign-out — and in Postgres, never kept as the key a browser holds.
 */
class ASessionIsKeptInPostgresAsInMemoryTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

    @Test
    void inMemory() {
        check(SessionStore.inMemory(), UUID.randomUUID().toString());
    }

    @Test
    void inPostgres() throws Exception {
        Database database = TestDatabase.get();
        String key = UUID.randomUUID().toString();
        check(new PostgresSessionStore(database), key);

        new PostgresSessionStore(database).put("session", key, "acme/priya@acme.example", NOW.plusSeconds(60));
        boolean plain = database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "select count(*) from host_session where key_digest = ?")) {
                select.setString(1, key);
                try (ResultSet rows = select.executeQuery()) {
                    rows.next();
                    return rows.getInt(1) > 0;
                }
            }
        });
        assertThat(plain).as("the key as the browser holds it").isFalse();
    }

    private static void check(SessionStore store, String key) {
        store.put("session", key, "acme/priya@acme.example", NOW.plusSeconds(60));
        store.put("sign-in", key, "acme\nnonce\nverifier", NOW.plusSeconds(60));

        assertThat(store.get("session", key, NOW)).hasValue("acme/priya@acme.example");
        assertThat(store.get("session", key, NOW.plusSeconds(61))).as("expired").isEmpty();
        assertThat(store.get("session", "someone-else", NOW)).isEmpty();
        assertThat(store.take("sign-in", key, NOW)).hasValue("acme\nnonce\nverifier");
        assertThat(store.take("sign-in", key, NOW)).as("a sign-in's state is used once").isEmpty();

        store.remove("session", key);
        assertThat(store.get("session", key, NOW)).isEmpty();

        store.put("session", key, "acme/dana@acme.example", NOW.plusSeconds(1));
        store.prune(NOW.plusSeconds(2));
        assertThat(store.get("session", key, NOW)).as("pruned once expired").isEmpty();
    }
}
