package dev.agentkit.host.store;

import static dev.agentkit.host.store.Database.instant;
import static dev.agentkit.host.store.Database.timestamp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.agentkit.chat.Attachment;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Step;
import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.tool.View;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.Quoted;
import dev.agentkit.host.Tenant;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Conversations, their turns and their attachments in Postgres, each row keyed by organization and tenant.
 *
 * <p>The same answers as {@link dev.agentkit.chat.store.InMemoryChatStore}, from the database rather than from memory,
 * so a restart — or another instance of the host — sees every conversation as it was left:
 * <ul>
 *   <li><strong>Scoped by tenant.</strong> Every read and write names the tenant, and a row of another tenant's is
 *       not found, whatever its id.</li>
 *   <li><strong>One writer per conversation at a time.</strong> A write to a conversation or its turns locks the
 *       conversation's row first, so a step appended while the person renames the conversation, or two steps
 *       appended at once, cannot lose one another. A turn is read, changed and written back inside that lock.</li>
 *   <li><strong>Ids that cannot collide.</strong> Minted at random rather than counted, because a counter starts
 *       again in every process and every instance.</li>
 *   <li><strong>Step sequence from the database</strong>, so steps read in the order they happened across
 *       processes.</li>
 * </ul>
 * A turn is kept as JSON, as {@link dev.agentkit.chat.store.FileChatStore} keeps it; nothing queries inside one.
 */
public final class PostgresChatStore implements ChatStore {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Database database;
    private final Clock clock;
    private final String runner;

    public PostgresChatStore(Database database) {
        this(database, Clock.systemUTC());
    }

    public PostgresChatStore(Database database, Clock clock) {
        this(database, clock, null);
    }

    /**
     * @param runner the instance of the host whose runtime runs the turns begun through this store, noted with each
     *               so another instance can tell when one was left behind ({@link Instances}); null for none
     */
    public PostgresChatStore(Database database, Clock clock, String runner) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runner = runner;
    }

    /** Microseconds, the database's precision, so what is returned is what is read back. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** The organization a tenant belongs to: the host's tenants are {@code org/email}. */
    static String orgOf(String tenantId) {
        return Tenant.parse(tenantId).map(Tenant::org).orElse("");
    }

    private static String mint(String prefix) {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return prefix + "-" + HexFormat.of().formatHex(bytes);
    }

    // --- conversations --------------------------------------------------------------

    @Override
    public Conversation create(String tenantId, String title) {
        return create(tenantId, title, null);
    }

    @Override
    public Conversation create(String tenantId, String title, Conversation.Pin agent) {
        Objects.requireNonNull(tenantId, "tenantId");
        Instant now = now();
        Conversation conversation = new Conversation(mint("conv"), tenantId, title, now, now, agent);
        database.transaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("insert into chat_conversation "
                    + "(id, org_id, tenant_id, title, created_at, updated_at, agent_id, agent_version) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, conversation.id());
                insert.setString(2, orgOf(tenantId));
                insert.setString(3, tenantId);
                insert.setString(4, title);
                insert.setTimestamp(5, timestamp(now));
                insert.setTimestamp(6, timestamp(now));
                insert.setString(7, agent == null ? null : agent.id());
                insert.setString(8, agent == null ? null : agent.version());
                insert.executeUpdate();
            }
            return null;
        });
        return conversation;
    }

    @Override
    public Optional<Conversation> conversation(String tenantId, String id) {
        if (tenantId == null || id == null) {
            return Optional.empty();
        }
        return database.read(connection -> conversation(connection, tenantId, id, false));
    }

    @Override
    public List<Conversation> conversations(String tenantId) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("select * from chat_conversation "
                    + "where tenant_id = ? order by updated_at desc, id")) {
                select.setString(1, tenantId);
                try (ResultSet rows = select.executeQuery()) {
                    List<Conversation> found = new ArrayList<>();
                    while (rows.next()) {
                        found.add(conversation(rows));
                    }
                    return found;
                }
            }
        });
    }

    @Override
    public Optional<Conversation> rename(String tenantId, String id, String title) {
        return database.transaction(connection -> {
            Optional<Conversation> current = conversation(connection, tenantId, id, true);
            if (current.isEmpty()) {
                return Optional.empty();
            }
            Conversation renamed = current.get().titled(title, now());
            try (PreparedStatement update = connection.prepareStatement(
                    "update chat_conversation set title = ?, updated_at = ? where id = ?")) {
                update.setString(1, renamed.title());
                update.setTimestamp(2, timestamp(renamed.updatedAt()));
                update.setString(3, id);
                update.executeUpdate();
            }
            return Optional.of(renamed);
        });
    }

    @Override
    public boolean delete(String tenantId, String id) {
        return database.transaction(connection -> {
            // Turns and attachments go with it (on delete cascade): "delete this thread" includes its files.
            try (PreparedStatement delete = connection.prepareStatement(
                    "delete from chat_conversation where id = ? and tenant_id = ?")) {
                delete.setString(1, id);
                delete.setString(2, tenantId);
                return delete.executeUpdate() > 0;
            }
        });
    }

    // --- turns ----------------------------------------------------------------------

    @Override
    public Turn begin(String tenantId, String conversationId, String userText, List<String> attachmentIds) {
        return database.transaction(connection -> {
            if (conversation(connection, tenantId, conversationId, true).isEmpty()) {
                throw new IllegalArgumentException("No such conversation: "
                        + Quoted.of(Cut.to(String.valueOf(conversationId), 120)));
            }
            long ordinal;
            try (PreparedStatement next = connection.prepareStatement(
                    "select coalesce(max(ordinal), 0) + 1 from chat_turn where conversation_id = ?")) {
                next.setString(1, conversationId);
                try (ResultSet rows = next.executeQuery()) {
                    rows.next();
                    ordinal = rows.getLong(1);
                }
            }
            Instant now = now();
            Turn turn = Turn.beginning(mint("turn"), conversationId, ordinal, userText, attachmentIds, now);
            try (PreparedStatement insert = connection.prepareStatement("insert into chat_turn (id, org_id, tenant_id, "
                    + "conversation_id, ordinal, turn, state, runner) values (?, ?, ?, ?, ?, ?::json, ?, ?)")) {
                insert.setString(1, turn.id());
                insert.setString(2, orgOf(tenantId));
                insert.setString(3, tenantId);
                insert.setString(4, conversationId);
                insert.setLong(5, ordinal);
                insert.setString(6, json(turn));
                insert.setString(7, turn.state().name());
                insert.setString(8, runner);
                insert.executeUpdate();
            }
            touch(connection, conversationId, now);
            return turn;
        });
    }

    @Override
    public Optional<Turn> turn(String tenantId, String conversationId, String turnId) {
        if (tenantId == null || conversationId == null || turnId == null) {
            return Optional.empty();
        }
        return database.read(connection -> turn(connection, tenantId, conversationId, turnId, false));
    }

    @Override
    public List<Turn> turns(String tenantId, String conversationId) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("select turn from chat_turn "
                    + "where tenant_id = ? and conversation_id = ? order by ordinal")) {
                select.setString(1, tenantId);
                select.setString(2, conversationId);
                try (ResultSet rows = select.executeQuery()) {
                    List<Turn> found = new ArrayList<>();
                    while (rows.next()) {
                        found.add(turn(rows.getString(1)));
                    }
                    return found;
                }
            }
        });
    }

    @Override
    public Optional<Turn> addStep(String tenantId, String conversationId, String turnId, Step.Kind kind, String name,
                                  Map<String, Object> detail, long millis, boolean failed) {
        return replace(tenantId, conversationId, turnId, (connection, turn) -> turn.with(
                new Step(nextStep(connection), kind, name, detail == null ? Map.of() : new LinkedHashMap<>(detail),
                        now(), millis, failed)));
    }

    @Override
    public Optional<Turn> show(String tenantId, String conversationId, String turnId, View view) {
        return replace(tenantId, conversationId, turnId, (connection, turn) -> turn.showing(view));
    }

    @Override
    public Optional<Turn> markRunning(String tenantId, String conversationId, String turnId) {
        return replace(tenantId, conversationId, turnId, (connection, turn) -> turn.running());
    }

    @Override
    public Optional<Turn> end(String tenantId, String conversationId, String turnId, Turn.State state, String answer,
                              String detail, TokenUsage usage) {
        return replace(tenantId, conversationId, turnId,
                (connection, turn) -> turn.ended(state, answer, detail, usage, now()));
    }

    /** A change to a turn, which may use the connection it is made under. */
    @FunctionalInterface
    private interface Change {
        Turn apply(Connection connection, Turn turn) throws SQLException;
    }

    /** The one place a turn is swapped for a newer version of itself, under its conversation's lock. */
    private Optional<Turn> replace(String tenantId, String conversationId, String turnId, Change change) {
        if (tenantId == null || conversationId == null || turnId == null) {
            return Optional.empty();
        }
        return database.transaction(connection -> {
            if (conversation(connection, tenantId, conversationId, true).isEmpty()) {
                return Optional.empty();
            }
            Optional<Turn> current = turn(connection, tenantId, conversationId, turnId, true);
            if (current.isEmpty()) {
                return Optional.empty();
            }
            Turn updated = change.apply(connection, current.get());
            try (PreparedStatement update = connection.prepareStatement(
                    "update chat_turn set turn = ?::json, state = ? where id = ?")) {
                update.setString(1, json(updated));
                update.setString(2, updated.state().name());
                update.setString(3, turnId);
                update.executeUpdate();
            }
            touch(connection, conversationId, now());
            return Optional.of(updated);
        });
    }

    private static long nextStep(Connection connection) throws SQLException {
        try (PreparedStatement next = connection.prepareStatement("select nextval('chat_step_sequence')");
             ResultSet rows = next.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    // --- attachments ----------------------------------------------------------------

    @Override
    public Attachment attach(String tenantId, String conversationId, String name, String mediaType, byte[] content) {
        return database.transaction(connection -> {
            if (conversation(connection, tenantId, conversationId, true).isEmpty()) {
                throw new IllegalArgumentException("No such conversation: "
                        + Quoted.of(Cut.to(String.valueOf(conversationId), 120)));
            }
            byte[] bytes = content == null ? new byte[0] : content.clone();
            Attachment attachment = new Attachment(mint("att"), tenantId, conversationId, name, mediaType, bytes.length,
                    now());
            try (PreparedStatement insert = connection.prepareStatement("insert into chat_attachment "
                    + "(id, org_id, tenant_id, conversation_id, name, media_type, bytes, uploaded_at, content) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, attachment.id());
                insert.setString(2, orgOf(tenantId));
                insert.setString(3, tenantId);
                insert.setString(4, conversationId);
                insert.setString(5, name);
                insert.setString(6, mediaType);
                insert.setLong(7, bytes.length);
                insert.setTimestamp(8, timestamp(attachment.uploadedAt()));
                insert.setBytes(9, bytes);
                insert.executeUpdate();
            }
            return attachment;
        });
    }

    @Override
    public Optional<Attachment> attachment(String tenantId, String id) {
        if (tenantId == null || id == null) {
            return Optional.empty();
        }
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("select id, tenant_id, conversation_id, name, "
                    + "media_type, bytes, uploaded_at from chat_attachment where id = ? and tenant_id = ?")) {
                select.setString(1, id);
                select.setString(2, tenantId);
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? Optional.of(attachment(rows)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<Attachment> attachments(String tenantId, String conversationId) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("select id, tenant_id, conversation_id, name, "
                    + "media_type, bytes, uploaded_at from chat_attachment where tenant_id = ? and conversation_id = ? "
                    + "order by uploaded_at, id")) {
                select.setString(1, tenantId);
                select.setString(2, conversationId);
                try (ResultSet rows = select.executeQuery()) {
                    List<Attachment> found = new ArrayList<>();
                    while (rows.next()) {
                        found.add(attachment(rows));
                    }
                    return found;
                }
            }
        });
    }

    @Override
    public Optional<byte[]> content(String tenantId, String id) {
        if (tenantId == null || id == null) {
            return Optional.empty();
        }
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "select content from chat_attachment where id = ? and tenant_id = ?")) {
                select.setString(1, id);
                select.setString(2, tenantId);
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? Optional.of(rows.getBytes(1)) : Optional.empty();
                }
            }
        });
    }

    // --- rows -----------------------------------------------------------------------

    private static Optional<Conversation> conversation(Connection connection, String tenantId, String id, boolean lock)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("select * from chat_conversation "
                + "where id = ? and tenant_id = ?" + (lock ? " for update" : ""))) {
            select.setString(1, id);
            select.setString(2, tenantId);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(conversation(rows)) : Optional.empty();
            }
        }
    }

    private static Conversation conversation(ResultSet row) throws SQLException {
        String agentId = row.getString("agent_id");
        return new Conversation(row.getString("id"), row.getString("tenant_id"), row.getString("title"),
                instant(row.getTimestamp("created_at")), instant(row.getTimestamp("updated_at")),
                agentId == null ? null : new Conversation.Pin(agentId, row.getString("agent_version")));
    }

    private static Optional<Turn> turn(Connection connection, String tenantId, String conversationId, String turnId,
                                       boolean lock) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("select turn from chat_turn "
                + "where id = ? and conversation_id = ? and tenant_id = ?" + (lock ? " for update" : ""))) {
            select.setString(1, turnId);
            select.setString(2, conversationId);
            select.setString(3, tenantId);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(turn(rows.getString(1))) : Optional.empty();
            }
        }
    }

    private static void touch(Connection connection, String conversationId, Instant now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "update chat_conversation set updated_at = ? where id = ?")) {
            update.setTimestamp(1, timestamp(now));
            update.setString(2, conversationId);
            update.executeUpdate();
        }
    }

    private static Attachment attachment(ResultSet row) throws SQLException {
        return new Attachment(row.getString("id"), row.getString("tenant_id"), row.getString("conversation_id"),
                row.getString("name"), row.getString("media_type"), row.getLong("bytes"),
                instant(row.getTimestamp("uploaded_at")));
    }

    private static String json(Turn turn) {
        try {
            return JSON.writeValueAsString(turn);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A turn could not be written as JSON", e);
        }
    }

    private static Turn turn(String json) {
        try {
            return JSON.readValue(json, Turn.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored turn could not be read", e);
        }
    }
}
