package dev.agentkit.workbench.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A ticket as the workbench understands it, not as any one ALM returns it.
 *
 * <p>The connector's job is to produce this. Nothing above the connector knows that Jira
 * spells the body {@code fields.description} or groups statuses into categories — swapping
 * in ServiceNow means writing another {@code Alm}, not editing the agent, the supervisor or
 * the UI.
 *
 * @param key            the ALM's human-facing identifier, e.g. {@code IT-421}
 * @param provider       which system this came from, e.g. {@code jira}
 * @param summary        one-line title, written by whoever filed the ticket
 * @param description    the body, written by whoever filed the ticket
 * @param status         the ALM's own status name, e.g. {@code In Progress}
 * @param statusCategory normalised lifecycle bucket
 * @param priority       the ALM's priority name, or {@code null}
 * @param assignee       who owns it, or {@code null} when unassigned
 * @param reporter       who filed it, or {@code null}
 * @param createdAt      when the ALM recorded it
 * @param updatedAt      when the ALM last changed it
 */
public record Ticket(String key, String provider, String summary, String description,
                     String status, Category statusCategory, String priority, String assignee,
                     String reporter, Instant createdAt, Instant updatedAt) {

    /** Jira's three status categories, which every Jira workflow maps onto. */
    public enum Category { OPEN, IN_PROGRESS, DONE }

    /**
     * One comment or work note.
     *
     * @param author who wrote it — a person, or the integration identity
     * @param body   the text, written by that author
     * @param at     when it was added
     */
    public record Comment(String author, String body, Instant at) {}

    public Ticket {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(summary, "summary");
        description = description == null ? "" : description;
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(statusCategory, "statusCategory");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * Everything a model may read about this ticket, as one block of text.
     *
     * <p>Deliberately <em>not</em> fenced here: this returns the raw untrusted body, and the
     * caller fences it with the label that says where it came from — see {@code AlmTools}.
     */
    public String asPromptText() {
        return "key: " + key + '\n'
                + "status: " + status + '\n'
                + "priority: " + (priority == null ? "-" : priority) + '\n'
                + "assignee: " + (assignee == null ? "-" : assignee) + '\n'
                + "reporter: " + (reporter == null ? "-" : reporter) + '\n'
                + "summary: " + summary + '\n'
                + "description: " + description;
    }
}
