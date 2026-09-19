package dev.agentkit.host.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.host.RehearsalLog;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The rehearsals reported for each organization, in {@code rehearsal}: every report, read back newest first. */
public final class PostgresRehearsalLog implements RehearsalLog {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Database database;

    public PostgresRehearsalLog(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void add(String org, Map<String, Object> report, Instant at) {
        String json;
        try {
            json = JSON.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("A rehearsal report could not be written as JSON", e);
        }
        database.transaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into rehearsal (org_id, received_at, report) values (?, ?, ?::json)")) {
                insert.setString(1, org);
                insert.setTimestamp(2, Database.timestamp(at));
                insert.setString(3, json);
                insert.executeUpdate();
            }
            return null;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> recent(String org, int limit) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("select received_at, report from rehearsal "
                    + "where org_id = ? order by received_at desc, id desc limit ?")) {
                select.setString(1, org);
                select.setInt(2, limit);
                try (ResultSet rows = select.executeQuery()) {
                    List<Map<String, Object>> found = new ArrayList<>();
                    while (rows.next()) {
                        Map<String, Object> report;
                        try {
                            report = new LinkedHashMap<>(JSON.readValue(rows.getString(2), Map.class));
                        } catch (JsonProcessingException e) {
                            throw new IllegalStateException("A stored rehearsal report could not be read", e);
                        }
                        report.put("receivedAt", Database.instant(rows.getTimestamp(1)).toString());
                        found.add(report);
                    }
                    return found;
                }
            }
        });
    }
}
