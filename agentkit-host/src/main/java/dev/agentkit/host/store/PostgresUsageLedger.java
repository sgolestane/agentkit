package dev.agentkit.host.store;

import dev.agentkit.host.models.Spend;
import dev.agentkit.host.models.UsageLedger;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What each organization's agents spent on models, in {@code model_use}: one row per organization, UTC day, agent,
 * model and payer, added to by each call. Every host instance adds to the same rows, so a budget holds across them.
 */
public final class PostgresUsageLedger implements UsageLedger {

    private final Database database;

    public PostgresUsageLedger(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void add(Key key, Spend spend) {
        database.transaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    insert into model_use (org_id, day, agent_id, model, host_paid, calls, input_tokens, output_tokens, usd)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (org_id, day, agent_id, model, host_paid) do update set
                        calls = model_use.calls + excluded.calls,
                        input_tokens = model_use.input_tokens + excluded.input_tokens,
                        output_tokens = model_use.output_tokens + excluded.output_tokens,
                        usd = model_use.usd + excluded.usd""")) {
                upsert.setString(1, key.org());
                upsert.setDate(2, Date.valueOf(key.day()));
                upsert.setString(3, key.agent());
                upsert.setString(4, key.model());
                upsert.setBoolean(5, key.hostPaid());
                upsert.setLong(6, spend.calls());
                upsert.setLong(7, spend.inputTokens());
                upsert.setLong(8, spend.outputTokens());
                upsert.setDouble(9, spend.usd());
                upsert.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Spend spent(String org, LocalDate from, LocalDate to, boolean hostPaidOnly) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("select coalesce(sum(calls), 0), "
                    + "coalesce(sum(input_tokens), 0), coalesce(sum(output_tokens), 0), coalesce(sum(usd), 0) "
                    + "from model_use where org_id = ? and day between ? and ?" + (hostPaidOnly ? " and host_paid" : ""))) {
                select.setString(1, org);
                select.setDate(2, Date.valueOf(from));
                select.setDate(3, Date.valueOf(to));
                try (ResultSet rows = select.executeQuery()) {
                    rows.next();
                    return new Spend(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getDouble(4));
                }
            }
        });
    }

    @Override
    public List<Row> rows(String org, LocalDate from, LocalDate to) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("select agent_id, model, host_paid, sum(calls), "
                    + "sum(input_tokens), sum(output_tokens), sum(usd) from model_use "
                    + "where org_id = ? and day between ? and ? group by agent_id, model, host_paid "
                    + "order by agent_id, model, host_paid")) {
                select.setString(1, org);
                select.setDate(2, Date.valueOf(from));
                select.setDate(3, Date.valueOf(to));
                try (ResultSet rows = select.executeQuery()) {
                    List<Row> found = new ArrayList<>();
                    while (rows.next()) {
                        found.add(new Row(rows.getString(1), rows.getString(2), rows.getBoolean(3),
                                new Spend(rows.getLong(4), rows.getLong(5), rows.getLong(6), rows.getDouble(7))));
                    }
                    return found;
                }
            }
        });
    }
}
