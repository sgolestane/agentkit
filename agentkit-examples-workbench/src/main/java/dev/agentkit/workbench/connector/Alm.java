package dev.agentkit.workbench.connector;

import dev.agentkit.workbench.domain.Ticket;
import java.util.List;
import java.util.Optional;

/**
 * The ALM capability, in the workbench's vocabulary rather than any provider's.
 *
 * <p>The one production implementation is {@link JiraClient}, over Jira's real REST API —
 * there is deliberately no in-memory stand-in in this module. The interface exists so the
 * agent, the supervisor and the console are written against tickets rather than against
 * Jira, which is what makes a second provider a new connector instead of a rewrite.
 */
public interface Alm {

    /** The provider's short name, e.g. {@code jira} — used in fence labels and the UI. */
    String name();

    /** The signed-in identity, so writes are attributable and assignment has a target. */
    Me myself();

    /** The tickets this identity can see under the configured scope, newest first. */
    List<Ticket> inbox(int limit);

    /** Free-text search within the configured scope. */
    List<Ticket> search(String text, int limit);

    Optional<Ticket> ticket(String key);

    List<Ticket.Comment> comments(String key);

    /** Adds a work note, attributed to the signed-in identity. */
    void addComment(String key, String body);

    /** The transitions the ALM currently allows for this ticket. */
    List<Transition> transitions(String key);

    /** Moves the ticket through the named transition. */
    void transition(String key, String transitionName);

    /** Assigns the ticket to the signed-in identity. */
    void assignToMe(String key);

    /** The signed-in identity. */
    record Me(String accountId, String displayName, String email) {}

    /** One workflow transition the ALM offers. */
    record Transition(String id, String toName) {}
}
