package dev.agentkit.host.store;

import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.core.llm.TokenUsage;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The instances of the host sharing one database, each saying every {@link #HEARTBEAT} that it is still running
 * ({@code host_instance}).
 *
 * <p>A turn runs in the process of the instance that began it, and is noted with it ({@code chat_turn.runner}). If
 * that instance stops — a restart, a crash, a deployment — its turns would say "running" forever. So every instance
 * looks, every heartbeat, for turns still queued or running whose instance has not been heard from for
 * {@link #GONE_AFTER}, and ends them as failed, saying so in a sentence the person can act on.
 */
public final class Instances implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Instances.class);

    /** How often an instance says it is running, and looks for turns left behind. */
    public static final Duration HEARTBEAT = Duration.ofSeconds(15);

    /** How long an instance may go unheard before its turns are taken as left behind. */
    public static final Duration GONE_AFTER = Duration.ofMinutes(1);

    /** What a turn left behind says. */
    public static final String LEFT_BEHIND = "The host running this turn stopped before it finished. A step it was in "
            + "the middle of may have happened, so check what was done before asking again.";

    private final Database database;
    private final String id;
    private final Supplier<Instant> clock;
    private ScheduledExecutorService timer;

    public Instances(Database database, Supplier<Instant> clock) {
        this(database, "host-" + UUID.randomUUID(), clock);
    }

    Instances(Database database, String id, Supplier<Instant> clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.id = Objects.requireNonNull(id, "id");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** This instance's id, which the turns it runs are noted with. */
    public String id() {
        return id;
    }

    /** Says this instance is running. */
    public void heartbeat() {
        Instant now = clock.get();
        database.transaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("insert into host_instance (id, started_at, "
                    + "heartbeat_at) values (?, ?, ?) on conflict (id) do update set heartbeat_at = excluded.heartbeat_at")) {
                upsert.setString(1, id);
                upsert.setTimestamp(2, Database.timestamp(now));
                upsert.setTimestamp(3, Database.timestamp(now));
                upsert.executeUpdate();
            }
            try (PreparedStatement forget = connection.prepareStatement(
                    "delete from host_instance where heartbeat_at < ?")) {
                forget.setTimestamp(1, Database.timestamp(now.minus(Duration.ofDays(1))));
                forget.executeUpdate();
            }
            return null;
        });
    }

    /** Ends as failed every turn left behind by an instance that has stopped; returns how many. */
    public int endLeftBehind(ChatStore chats) {
        Instant cutoff = clock.get().minus(GONE_AFTER);
        record LeftBehind(String tenant, String conversation, String turn) {
        }
        List<LeftBehind> found = database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("""
                    select tenant_id, conversation_id, id from chat_turn
                    where state in ('QUEUED', 'RUNNING') and (runner is null or (runner <> ? and runner not in
                        (select id from host_instance where heartbeat_at >= ?)))""")) {
                select.setString(1, id);
                select.setTimestamp(2, Database.timestamp(cutoff));
                try (ResultSet rows = select.executeQuery()) {
                    List<LeftBehind> turns = new ArrayList<>();
                    while (rows.next()) {
                        turns.add(new LeftBehind(rows.getString(1), rows.getString(2), rows.getString(3)));
                    }
                    return turns;
                }
            }
        });
        int ended = 0;
        for (LeftBehind turn : found) {
            Turn.State state = chats.turn(turn.tenant(), turn.conversation(), turn.turn()).map(Turn::state).orElse(null);
            if (state == Turn.State.QUEUED || state == Turn.State.RUNNING) {
                chats.end(turn.tenant(), turn.conversation(), turn.turn(), Turn.State.FAILED, "", LEFT_BEHIND,
                        TokenUsage.ZERO);
                ended++;
            }
        }
        if (ended > 0) {
            LOG.info("Ended {} turn(s) left behind by a host instance that stopped", ended);
        }
        return ended;
    }

    /** Says this instance is running, and ends turns left behind, every {@link #HEARTBEAT} until closed. */
    public synchronized void start(ChatStore chats) {
        if (timer != null) {
            return;
        }
        heartbeat();
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "agentkit-host-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                heartbeat();
                endLeftBehind(chats);
            } catch (RuntimeException e) {
                LOG.warn("A heartbeat of {} failed", id, e);
            }
        }, 0, HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Stops the heartbeat and says this instance has stopped, so its turns are not waited on. */
    @Override
    public synchronized void close() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
        try {
            database.transaction(connection -> {
                try (PreparedStatement delete = connection.prepareStatement("delete from host_instance where id = ?")) {
                    delete.setString(1, id);
                    delete.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.warn("Could not say {} has stopped", id, e);
        }
    }
}
