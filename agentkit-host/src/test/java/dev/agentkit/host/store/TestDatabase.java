package dev.agentkit.host.store;

import java.util.UUID;
import org.junit.jupiter.api.Assumptions;

/**
 * The Postgres the store tests run against: {@code AGENTKIT_TEST_DATABASE_URL}, such as
 * {@code jdbc:postgresql://127.0.0.1:5432/agentkit_test?user=agentkit}. Without it they are skipped. Tests share it,
 * each under an organization of its own, so they need no cleanup and can run in any order.
 */
final class TestDatabase {

    static final String URL_ENV = "AGENTKIT_TEST_DATABASE_URL";

    private static Database database;

    private TestDatabase() {
    }

    /** The database, migrated; the test is skipped when none is configured. */
    static synchronized Database get() {
        String url = System.getenv(URL_ENV);
        Assumptions.assumeTrue(url != null && !url.isBlank(), "Set " + URL_ENV + " to run the Postgres store tests.");
        if (database == null) {
            database = Database.open(url.strip(), null, null);
        }
        return database;
    }

    /** An organization no other test uses. */
    static String org() {
        return "org-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
