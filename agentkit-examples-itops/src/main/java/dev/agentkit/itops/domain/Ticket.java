package dev.agentkit.itops.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A ticket as the platform understands it, not as any one provider returns it.
 *
 * <p>The connector's job is to produce this. Nothing above the connector should know that
 * ServiceNow calls the table {@code incident}, spells the state as {@code "2"}, or returns
 * {@code sys_id} alongside {@code number} — swapping in Jira means writing another
 * {@code TicketProvider}, not editing the agent, the supervisor or the UI.
 *
 * @param id            the provider's human-facing identifier, e.g. {@code INC0012345}
 * @param provider      which system this came from, e.g. {@code servicenow}
 * @param title         one-line summary, written by whoever filed the ticket
 * @param description   the body, written by whoever filed the ticket
 * @param status        normalised lifecycle state
 * @param assignmentGroup the queue it sits in, or {@code null}
 * @param assignee      who owns it, or {@code null} when unassigned
 * @param createdAt     when the provider recorded it
 * @param updatedAt     when the provider last changed it
 * @param comments      the work notes, oldest first
 */
public record Ticket(String id, String provider, String title, String description,
                     Status status, String assignmentGroup, String assignee,
                     Instant createdAt, Instant updatedAt, List<Comment> comments) {

    /** Normalised across providers; each connector maps its own vocabulary onto this. */
    public enum Status { OPEN, IN_PROGRESS, ON_HOLD, RESOLVED, CLOSED, CANCELLED }

    /**
     * One comment or work note.
     *
     * @param author who wrote it — a person, or the integration identity
     * @param body   the text, written by that author
     * @param at     when it was added
     */
    public record Comment(String author, String body, Instant at) {}

    public Ticket {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        comments = List.copyOf(Objects.requireNonNull(comments, "comments"));
    }

    /**
     * Everything a model may read about this ticket, as one block of text.
     *
     * <p>Deliberately <em>not</em> fenced here. This returns the raw untrusted body, and the
     * caller fences it with the label that says where it came from — see
     * {@code TicketTools}. Returning something pre-fenced would invite a caller to fence it
     * twice or, worse, to trust that it had been.
     */
    public String asPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("id: ").append(id).append('\n')
                .append("status: ").append(status).append('\n')
                .append("assignment_group: ").append(assignmentGroup == null ? "-" : assignmentGroup)
                .append('\n')
                .append("assignee: ").append(assignee == null ? "-" : assignee).append('\n')
                .append("title: ").append(title).append('\n')
                .append("description: ").append(description);
        for (Comment comment : comments) {
            sb.append("\ncomment by ").append(comment.author()).append(": ").append(comment.body());
        }
        return sb.toString();
    }
}
