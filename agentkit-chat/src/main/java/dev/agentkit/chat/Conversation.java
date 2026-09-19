package dev.agentkit.chat;

import java.time.Instant;
import java.util.Objects;

/**
 * One thread of work between a person and an agent.
 *
 * <p>The transcript itself is not here — {@link Turn}s are held against a conversation's id
 * rather than inside it, because a turn is appended to while it runs and a conversation is
 * not. What this carries is the identity: something a browser tab can be closed on and come
 * back to, and something a URL can name.
 *
 * <p><strong>Scoped by tenant, like everything in this module.</strong> Every store method
 * takes a {@code tenantId} and none of them will hand back a conversation belonging to
 * another one. That is not because the demos are multi-tenant — they run one tenant — but
 * because the alternative is a codebase where adding the second tenant means auditing every
 * read. {@code WorkbenchStore} in the Workbench example made the same choice for the same reason.
 *
 * @param id        minted by the store; safe to put in a path or a URL
 * @param tenantId  who this belongs to
 * @param title     what to call it in a list; may be empty until something names it
 * @param createdAt when it started
 * @param updatedAt when a turn was last appended or the title last changed
 * @param agent     which agent, at which version, this conversation is with; null when the deployment has one agent
 *                  and does not say
 */
public record Conversation(String id, String tenantId, String title,
                           Instant createdAt, Instant updatedAt, Pin agent) {

    /**
     * The agent a conversation is with, fixed when it starts: its id, and the version of its definition. A later
     * version of the same agent is a different agent as far as this conversation is concerned — what it was told, and
     * what it may do, are the version's — so a conversation never changes agents underneath the person in it.
     */
    public record Pin(String id, String version) {
        public Pin {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(version, "version");
        }

        @Override
        public String toString() {
            return id + "@" + version;
        }
    }

    /** A conversation with no agent pinned: the shape before pins existed, and a single-agent console's. */
    public Conversation(String id, String tenantId, String title, Instant createdAt, Instant updatedAt) {
        this(id, tenantId, title, createdAt, updatedAt, null);
    }

    /** How long a title may be, in a list a person scans rather than reads. */
    public static final int MAX_TITLE_CHARS = 200;

    public Conversation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        title = title == null ? "" : dev.agentkit.core.util.Cut.to(title, MAX_TITLE_CHARS);
        Objects.requireNonNull(createdAt, "createdAt");
        // Coerced rather than required: a conversation read back from a file written before
        // this component existed has no value for it, and its creation time is the honest
        // stand-in — nothing has happened to it since, by construction.
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    /** This conversation, renamed and marked as changed. */
    public Conversation titled(String newTitle, Instant now) {
        return new Conversation(id, tenantId, newTitle, createdAt, now, agent);
    }

    /** This conversation, marked as changed without changing anything else. */
    public Conversation touched(Instant now) {
        return new Conversation(id, tenantId, title, createdAt, now, agent);
    }
}
