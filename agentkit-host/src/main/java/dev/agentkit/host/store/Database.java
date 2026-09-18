package dev.agentkit.host.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The host's Postgres database: a pool of connections, and the schema it needs, brought up to date on open.
 *
 * <p>Every table is keyed by organization: {@code org_id} is on every row, so what one organization holds is
 * found, exported or deleted without reading another's.
 *
 * <p><strong>Migrations</strong> are the numbered scripts in {@link #MIGRATIONS}, applied in order, each once, in a
 * transaction that holds an advisory lock, so two hosts starting against the same database do not both apply one.
 * A script is never edited once released; a change is a new script.
 */
public final class Database implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Database.class);

    /** Any constant will do, as long as nothing else locks it: the schema's own advisory lock. */
    private static final long MIGRATION_LOCK = 0x61676e746b6974L;

    /** The schema, one script per version; version n is {@code MIGRATIONS.get(n - 1)}. */
    static final List<String> MIGRATIONS = List.of("""
            create table chat_conversation (
                id            text primary key,
                org_id        text not null,
                tenant_id     text not null,
                title         text,
                created_at    timestamptz not null,
                updated_at    timestamptz not null,
                agent_id      text,
                agent_version text
            );
            create index chat_conversation_by_tenant on chat_conversation (tenant_id, updated_at desc, id);
            create index chat_conversation_by_org on chat_conversation (org_id);

            create table chat_turn (
                id              text primary key,
                org_id          text not null,
                tenant_id       text not null,
                conversation_id text not null references chat_conversation (id) on delete cascade,
                ordinal         bigint not null,
                turn            json not null,
                unique (conversation_id, ordinal)
            );
            create index chat_turn_by_org on chat_turn (org_id);

            create table chat_attachment (
                id              text primary key,
                org_id          text not null,
                tenant_id       text not null,
                conversation_id text not null references chat_conversation (id) on delete cascade,
                name            text not null,
                media_type      text,
                bytes           bigint not null,
                uploaded_at     timestamptz not null,
                content         bytea not null
            );
            create index chat_attachment_by_conversation on chat_attachment (conversation_id, uploaded_at, id);
            create index chat_attachment_by_org on chat_attachment (org_id);

            create sequence chat_step_sequence;

            create table deferred_action (
                org_id       text not null,
                agent_id     text not null,
                id           text not null,
                subject_kind text not null,
                subject_id   text not null,
                run_at       timestamptz not null,
                when_text    text not null,
                goal         text not null,
                scheduled_at timestamptz,
                scheduled_by text not null,
                status       text not null,
                outcome      text not null,
                finished_at  timestamptz,
                claimed_at   timestamptz,
                primary key (org_id, agent_id, id)
            );
            create index deferred_action_due on deferred_action (org_id, agent_id, status, run_at);

            create table org_version (
                org_id    text not null,
                version   text not null,
                loaded_at timestamptz not null,
                primary key (org_id, version)
            );
            """, """
            create table rehearsal (
                id          bigserial primary key,
                org_id      text not null,
                received_at timestamptz not null,
                report      json not null
            );
            create index rehearsal_by_org on rehearsal (org_id, received_at desc);
            """, """
            create table model_use (
                org_id        text not null,
                day           date not null,
                agent_id      text not null,
                model         text not null,
                host_paid     boolean not null,
                calls         bigint not null,
                input_tokens  bigint not null,
                output_tokens bigint not null,
                usd           double precision not null,
                primary key (org_id, day, agent_id, model, host_paid)
            );
            """);

    private final HikariDataSource pool;

    private Database(HikariDataSource pool) {
        this.pool = pool;
    }

    /**
     * Connects to {@code url} ({@code jdbc:postgresql://...}) and brings its schema up to date.
     *
     * @param user     null to take it from the url
     * @param password null to take it from the url
     */
    public static Database open(String url, String user, String password) {
        Objects.requireNonNull(url, "url");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        if (user != null && !user.isBlank()) {
            config.setUsername(user);
        }
        if (password != null && !password.isBlank()) {
            config.setPassword(password);
        }
        config.setPoolName("agentkit-host");
        config.setMaximumPoolSize(10);
        Database database = new Database(new HikariDataSource(config));
        try {
            database.migrate();
        } catch (RuntimeException e) {
            database.close();
            throw e;
        }
        return database;
    }

    /** Work done with one connection. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Runs {@code work} in one transaction: all of it is kept, or none of it. */
    public <T> T transaction(Work<T> work) {
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    /** Runs {@code work} with a connection of its own, each statement on its own. */
    public <T> T read(Work<T> work) {
        try (Connection connection = pool.getConnection()) {
            return work.run(connection);
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    /** The version the schema is at. */
    public int schemaVersion() {
        return read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select coalesce(max(version), 0) from agentkit_schema")) {
                rows.next();
                return rows.getInt(1);
            }
        });
    }

    private void migrate() {
        transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create table if not exists agentkit_schema "
                        + "(version int primary key, applied_at timestamptz not null)");
                statement.execute("select pg_advisory_xact_lock(" + MIGRATION_LOCK + ")");
            }
            int at;
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select coalesce(max(version), 0) from agentkit_schema")) {
                rows.next();
                at = rows.getInt(1);
            }
            if (at > MIGRATIONS.size()) {
                throw new IllegalStateException("The database's schema is at version " + at + ", newer than this host's "
                        + MIGRATIONS.size() + "; run a host at least as new as the one that migrated it.");
            }
            for (int version = at + 1; version <= MIGRATIONS.size(); version++) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(MIGRATIONS.get(version - 1));
                }
                try (PreparedStatement mark = connection.prepareStatement(
                        "insert into agentkit_schema (version, applied_at) values (?, ?)")) {
                    mark.setInt(1, version);
                    mark.setTimestamp(2, Timestamp.from(Instant.now()));
                    mark.executeUpdate();
                }
                LOG.info("Applied schema version {}", version);
            }
            return null;
        });
    }

    @Override
    public void close() {
        pool.close();
    }

    /** A database call that failed, with the driver's reason. */
    public static final class StoreException extends RuntimeException {
        StoreException(SQLException cause) {
            super("The database could not be used: " + cause.getMessage(), cause);
        }
    }

    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
