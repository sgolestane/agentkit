package dev.agentkit.host.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.host.routing.RoutingLog;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Where each routed message went, in {@code route}, and the messages sent again to another agent, in {@code misroute}. */
public final class PostgresRoutingLog implements RoutingLog {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Database database;

    public PostgresRoutingLog(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void add(String org, Route route) {
        database.transaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("insert into route (org_id, at, tenant_id, "
                    + "conversation_id, turn_id, said, to_agent, action, why, input_tokens, output_tokens) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, org);
                insert.setTimestamp(2, Database.timestamp(route.at()));
                insert.setString(3, route.tenant());
                insert.setString(4, route.conversation());
                insert.setString(5, route.turn());
                insert.setString(6, route.said());
                insert.setString(7, route.to());
                insert.setString(8, route.action());
                insert.setString(9, route.why());
                insert.setLong(10, route.inputTokens());
                insert.setLong(11, route.outputTokens());
                insert.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<Route> recent(String org, int limit) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("select at, tenant_id, conversation_id, "
                    + "turn_id, said, to_agent, action, why, input_tokens, output_tokens from route where org_id = ? "
                    + "order by at desc, id desc limit ?")) {
                select.setString(1, org);
                select.setInt(2, limit);
                try (ResultSet rows = select.executeQuery()) {
                    List<Route> found = new ArrayList<>();
                    while (rows.next()) {
                        found.add(new Route(Database.instant(rows.getTimestamp(1)), rows.getString(2),
                                rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6),
                                rows.getString(7), rows.getString(8), rows.getLong(9), rows.getLong(10)));
                    }
                    return found;
                }
            }
        });
    }

    @Override
    public void addMisroute(String org, Misroute misroute) {
        String before;
        try {
            before = JSON.writeValueAsString(misroute.before());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("A misroute's earlier turns could not be written as JSON", e);
        }
        database.transaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("insert into misroute (id, org_id, at, "
                    + "tenant_id, conversation_id, said, routed_to, chosen, before_turns) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?, ?::json) on conflict (id) do nothing")) {
                insert.setString(1, misroute.id());
                insert.setString(2, org);
                insert.setTimestamp(3, Database.timestamp(misroute.at()));
                insert.setString(4, misroute.tenant());
                insert.setString(5, misroute.conversation());
                insert.setString(6, misroute.said());
                insert.setString(7, misroute.routedTo());
                insert.setString(8, misroute.chosen());
                insert.setString(9, before);
                insert.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<Misroute> misroutes(String org, int limit) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("select id, at, tenant_id, conversation_id, "
                    + "said, routed_to, chosen, before_turns from misroute where org_id = ? order by at desc limit ?")) {
                select.setString(1, org);
                select.setInt(2, limit);
                try (ResultSet rows = select.executeQuery()) {
                    List<Misroute> found = new ArrayList<>();
                    while (rows.next()) {
                        List<Before> before;
                        try {
                            before = JSON.readValue(rows.getString(8), new TypeReference<List<Before>>() { });
                        } catch (JsonProcessingException e) {
                            throw new IllegalStateException("A stored misroute could not be read", e);
                        }
                        found.add(new Misroute(rows.getString(1), Database.instant(rows.getTimestamp(2)),
                                rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6),
                                rows.getString(7), before));
                    }
                    return found;
                }
            }
        });
    }
}
