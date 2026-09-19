package dev.agentkit.host.store;

import dev.agentkit.host.VersionLog;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The versions each organization was loaded at, in {@code org_version}: shared by every host on the database. */
public final class PostgresVersionLog implements VersionLog {

    private final Database database;

    public PostgresVersionLog(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void loaded(String org, String version, Instant at) {
        database.transaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("insert into org_version (org_id, version, "
                    + "loaded_at) values (?, ?, ?) on conflict (org_id, version) do update set loaded_at = excluded.loaded_at")) {
                upsert.setString(1, org);
                upsert.setString(2, version);
                upsert.setTimestamp(3, Database.timestamp(at));
                upsert.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<String> recent(String org, int limit) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("select version from org_version "
                    + "where org_id = ? order by loaded_at desc, version limit ?")) {
                select.setString(1, org);
                select.setInt(2, limit);
                try (ResultSet rows = select.executeQuery()) {
                    List<String> found = new ArrayList<>();
                    while (rows.next()) {
                        found.add(rows.getString(1));
                    }
                    return found;
                }
            }
        });
    }
}
