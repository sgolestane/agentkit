package dev.agentkit.itops.connector;

import dev.agentkit.itops.domain.Ticket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * What the platform needs from a ticketing system, in the platform's own vocabulary.
 *
 * <p>ServiceNow is the first implementation and deliberately not the shape of this
 * interface. Nothing here mentions tables, {@code sys_id}, or a state number, because the
 * moment one of them appears in a signature every caller learns it and the abstraction is
 * over. The test for whether this held is whether a Jira implementation can be written
 * without touching anything above {@code connector/}.
 *
 * <p>Note what is <em>not</em> here: no {@code delete}, and no generic {@code execute(query)}.
 * A connector interface wide enough to express anything is a connector interface the
 * supervisor cannot reason about, since risk would then depend on a string rather than on
 * which method was called.
 */
public interface TicketProvider {

    /** The provider's name, used as the deduplication key and in fence labels. */
    String name();

    /**
     * Tickets created or updated within {@code lookback}, newest first.
     *
     * @param assignmentGroup restrict to one queue, or {@code null} for all
     * @param limit           at most this many
     */
    List<Ticket> searchRecent(String assignmentGroup, Duration lookback, int limit);

    /** Free-text search across the fields a provider considers searchable. */
    List<Ticket> search(String query, int limit);

    Optional<Ticket> get(String id);

    /** Comments/work notes, oldest first. */
    List<Ticket.Comment> comments(String id);

    /** Assigns to a user, a group, or both; either may be {@code null}. */
    Ticket assign(String id, String assignee, String assignmentGroup);

    Ticket comment(String id, String author, String body);

    Ticket updateStatus(String id, Ticket.Status status);
}
