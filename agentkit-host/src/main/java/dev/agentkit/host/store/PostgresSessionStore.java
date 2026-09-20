package dev.agentkit.host.store;

import dev.agentkit.host.auth.SessionStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Sessions and sign-ins in progress, in {@code host_session}, shared by every instance of the host. A key is kept only
 * as its SHA-256 digest, so the table does not hold anything a browser could present.
 */
public final class PostgresSessionStore implements SessionStore {

    private final Database database;

    public PostgresSessionStore(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void put(String kind, String key, String value, Instant expires) {
        database.transaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("insert into host_session (kind, key_digest, "
                    + "value, expires_at) values (?, ?, ?, ?) on conflict (kind, key_digest) do update set "
                    + "value = excluded.value, expires_at = excluded.expires_at")) {
                upsert.setString(1, kind);
                upsert.setString(2, digest(key));
                upsert.setString(3, value);
                upsert.setTimestamp(4, Database.timestamp(expires));
                upsert.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<String> get(String kind, String key, Instant now) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "select value from host_session where kind = ? and key_digest = ? and expires_at > ?")) {
                select.setString(1, kind);
                select.setString(2, digest(key));
                select.setTimestamp(3, Database.timestamp(now));
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? Optional.of(rows.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    @Override
    public Optional<String> take(String kind, String key, Instant now) {
        return database.transaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "delete from host_session where kind = ? and key_digest = ? returning value, expires_at")) {
                delete.setString(1, kind);
                delete.setString(2, digest(key));
                try (ResultSet rows = delete.executeQuery()) {
                    return rows.next() && Database.instant(rows.getTimestamp(2)).isAfter(now)
                            ? Optional.of(rows.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    @Override
    public void remove(String kind, String key) {
        database.transaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "delete from host_session where kind = ? and key_digest = ?")) {
                delete.setString(1, kind);
                delete.setString(2, digest(key));
                delete.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void prune(Instant now) {
        database.transaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "delete from host_session where expires_at <= ?")) {
                delete.setTimestamp(1, Database.timestamp(now));
                delete.executeUpdate();
            }
            return null;
        });
    }

    private static String digest(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
