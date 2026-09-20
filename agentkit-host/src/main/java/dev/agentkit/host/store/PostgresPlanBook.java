package dev.agentkit.host.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.host.plans.PlanBook;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The plans plan-execute agents carried out from their forms, in {@code plan_run}: one row per plan, by organization,
 * agent, version and kind of task. Every instance of the host adds to and reads the same rows, so they settle on a plan
 * together.
 */
public final class PostgresPlanBook implements PlanBook {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Database database;

    public PostgresPlanBook(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void add(Agent agent, String shape, Run run) {
        database.transaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("insert into plan_run (org_id, agent_id, "
                    + "agent_version, shape_digest, shape, steps, task_values, reused, clean, at) "
                    + "values (?, ?, ?, ?, ?, ?::json, ?::json, ?, ?, ?)")) {
                insert.setString(1, agent.org());
                insert.setString(2, agent.agent());
                insert.setString(3, agent.version());
                insert.setString(4, digest(shape));
                insert.setString(5, shape);
                insert.setString(6, json(run.steps()));
                insert.setString(7, json(run.values()));
                insert.setBoolean(8, run.reused());
                insert.setBoolean(9, run.clean());
                insert.setTimestamp(10, Database.timestamp(run.at()));
                insert.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<Run> recent(Agent agent, String shape, int limit) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("select steps, task_values, reused, clean, at "
                    + "from plan_run where org_id = ? and agent_id = ? and agent_version = ? and shape_digest = ? "
                    + "order by id desc limit ?")) {
                where(select, agent, shape);
                select.setInt(5, limit);
                try (ResultSet rows = select.executeQuery()) {
                    List<Run> runs = new ArrayList<>();
                    while (rows.next()) {
                        runs.add(new Run(read(rows.getString(1), new TypeReference<List<String>>() { }),
                                read(rows.getString(2), new TypeReference<Map<String, String>>() { }),
                                rows.getBoolean(3), rows.getBoolean(4), Database.instant(rows.getTimestamp(5))));
                    }
                    return runs;
                }
            }
        });
    }

    @Override
    public long count(Agent agent, String shape) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("select count(*) from plan_run where org_id = ? "
                    + "and agent_id = ? and agent_version = ? and shape_digest = ?")) {
                where(select, agent, shape);
                try (ResultSet rows = select.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        });
    }

    @Override
    public List<String> shapes(Agent agent) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("select shape from plan_run where org_id = ? "
                    + "and agent_id = ? and agent_version = ? group by shape order by max(id) desc")) {
                select.setString(1, agent.org());
                select.setString(2, agent.agent());
                select.setString(3, agent.version());
                try (ResultSet rows = select.executeQuery()) {
                    List<String> shapes = new ArrayList<>();
                    while (rows.next()) {
                        shapes.add(rows.getString(1));
                    }
                    return shapes;
                }
            }
        });
    }

    private static void where(PreparedStatement select, Agent agent, String shape) throws java.sql.SQLException {
        select.setString(1, agent.org());
        select.setString(2, agent.agent());
        select.setString(3, agent.version());
        select.setString(4, digest(shape));
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static <T> T read(String json, TypeReference<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored plan could not be read", e);
        }
    }

    private static String digest(String shape) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(shape.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
