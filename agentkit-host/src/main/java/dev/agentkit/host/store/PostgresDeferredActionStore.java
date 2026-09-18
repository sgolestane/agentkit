package dev.agentkit.host.store;

import static dev.agentkit.host.store.Database.instant;
import static dev.agentkit.host.store.Database.timestamp;

import dev.agentkit.core.deferred.DeferredAction;
import dev.agentkit.core.deferred.DeferredActionStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One agent's deferred actions, in Postgres: the rows of {@code deferred_action} for one organization and agent.
 *
 * <p><strong>Several hosts may share it.</strong> A claim is one conditional update, so however many hosts sweep at
 * once, one of them runs an action. A host that stops while an action is running leaves it claimed; once the claim is
 * older than the lease, the action is due again and another sweep takes it — at least once, as the
 * {@link DeferredActionStore} contract says, and without a restart having to notice.
 */
public final class PostgresDeferredActionStore implements DeferredActionStore {

    /** How long a claim holds before the action is taken to have been abandoned. */
    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(30);

    private static final int MAX_ID = 200;

    private final Database database;
    private final String org;
    private final String agent;
    private final Duration lease;

    public PostgresDeferredActionStore(Database database, String org, String agent) {
        this(database, org, agent, DEFAULT_LEASE);
    }

    public PostgresDeferredActionStore(Database database, String org, String agent, Duration lease) {
        this.database = Objects.requireNonNull(database, "database");
        this.org = Objects.requireNonNull(org, "org");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.lease = Objects.requireNonNull(lease, "lease");
    }

    @Override
    public boolean put(DeferredAction action) {
        Objects.requireNonNull(action, "action");
        if (action.id().isBlank() || action.id().length() > MAX_ID) {
            throw new IllegalArgumentException("A deferred action id is 1 to " + MAX_ID + " characters: " + action.id());
        }
        return database.transaction(connection -> {
            Optional<DeferredAction> existing = get(connection, action.id(), true);
            if (existing.isPresent() && existing.get().status() == DeferredAction.Status.RUNNING) {
                throw new IllegalStateException("The deferred action " + action.id() + " is running and cannot be replaced");
            }
            try (PreparedStatement upsert = connection.prepareStatement("insert into deferred_action (org_id, agent_id, "
                    + "id, subject_kind, subject_id, run_at, when_text, goal, scheduled_at, scheduled_by, status, outcome, "
                    + "finished_at, claimed_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null) "
                    + "on conflict (org_id, agent_id, id) do update set subject_kind = excluded.subject_kind, "
                    + "subject_id = excluded.subject_id, run_at = excluded.run_at, when_text = excluded.when_text, "
                    + "goal = excluded.goal, scheduled_at = excluded.scheduled_at, scheduled_by = excluded.scheduled_by, "
                    + "status = excluded.status, outcome = excluded.outcome, finished_at = excluded.finished_at, "
                    + "claimed_at = null")) {
                upsert.setString(1, org);
                upsert.setString(2, agent);
                upsert.setString(3, action.id());
                upsert.setString(4, action.subjectKind());
                upsert.setString(5, action.subjectId());
                upsert.setTimestamp(6, timestamp(action.runAt()));
                upsert.setString(7, action.when());
                upsert.setString(8, action.goal());
                upsert.setTimestamp(9, timestamp(action.scheduledAt()));
                upsert.setString(10, action.scheduledBy());
                upsert.setString(11, action.status().name());
                upsert.setString(12, action.outcome());
                upsert.setTimestamp(13, timestamp(action.finishedAt()));
                upsert.executeUpdate();
            }
            return existing.isPresent();
        });
    }

    @Override
    public List<DeferredAction> all() {
        return database.read(connection -> list(connection, "", null));
    }

    @Override
    public Optional<DeferredAction> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return database.read(connection -> get(connection, id, false));
    }

    /** Scheduled actions whose time has come, and running ones whose claim has outlived the lease. */
    @Override
    public List<DeferredAction> due(Instant now) {
        return database.read(connection -> list(connection, " and ((status = 'SCHEDULED' and run_at <= ?) "
                + "or (status = 'RUNNING' and claimed_at <= ?))", now));
    }

    @Override
    public boolean claim(String id, Instant now) {
        return database.transaction(connection -> {
            try (PreparedStatement claim = connection.prepareStatement("update deferred_action "
                    + "set status = 'RUNNING', claimed_at = ?, outcome = '', finished_at = null "
                    + "where org_id = ? and agent_id = ? and id = ? "
                    + "and ((status = 'SCHEDULED' and run_at <= ?) or (status = 'RUNNING' and claimed_at <= ?))")) {
                claim.setTimestamp(1, timestamp(now));
                claim.setString(2, org);
                claim.setString(3, agent);
                claim.setString(4, id);
                claim.setTimestamp(5, timestamp(now));
                claim.setTimestamp(6, timestamp(now.minus(lease)));
                return claim.executeUpdate() == 1;
            }
        });
    }

    @Override
    public boolean finish(String id, boolean succeeded, String outcome, Instant now) {
        return database.transaction(connection -> {
            try (PreparedStatement finish = connection.prepareStatement("update deferred_action "
                    + "set status = ?, outcome = ?, finished_at = ?, claimed_at = null "
                    + "where org_id = ? and agent_id = ? and id = ? and status = 'RUNNING'")) {
                finish.setString(1, (succeeded ? DeferredAction.Status.DONE : DeferredAction.Status.FAILED).name());
                finish.setString(2, outcome == null ? "" : outcome);
                finish.setTimestamp(3, timestamp(now));
                finish.setString(4, org);
                finish.setString(5, agent);
                finish.setString(6, id);
                return finish.executeUpdate() == 1;
            }
        });
    }

    private Optional<DeferredAction> get(Connection connection, String id, boolean lock) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("select * from deferred_action "
                + "where org_id = ? and agent_id = ? and id = ?" + (lock ? " for update" : ""))) {
            select.setString(1, org);
            select.setString(2, agent);
            select.setString(3, id);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(action(rows)) : Optional.empty();
            }
        }
    }

    /** This agent's actions matching {@code where}, earliest first; {@code now}, if given, fills its two parameters. */
    private List<DeferredAction> list(Connection connection, String where, Instant now) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("select * from deferred_action "
                + "where org_id = ? and agent_id = ?" + where + " order by run_at, id")) {
            select.setString(1, org);
            select.setString(2, agent);
            if (now != null) {
                select.setTimestamp(3, timestamp(now));
                select.setTimestamp(4, timestamp(now.minus(lease)));
            }
            try (ResultSet rows = select.executeQuery()) {
                List<DeferredAction> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(action(rows));
                }
                return found;
            }
        }
    }

    private static DeferredAction action(ResultSet row) throws SQLException {
        return new DeferredAction(row.getString("id"), row.getString("subject_kind"), row.getString("subject_id"),
                instant(row.getTimestamp("run_at")), row.getString("when_text"), row.getString("goal"),
                instant(row.getTimestamp("scheduled_at")), row.getString("scheduled_by"),
                DeferredAction.Status.valueOf(row.getString("status")), row.getString("outcome"),
                instant(row.getTimestamp("finished_at")));
    }
}
