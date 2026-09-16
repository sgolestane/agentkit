package dev.agentkit.itops.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Something durable a run produced: an investigation report, a remediation summary, a
 * proposed workflow, a runbook.
 *
 * <p>The point of an artifact is that it outlives the conversation that made it. A chat
 * answer is read once and scrolls away; an artifact is addressable, linkable from a ticket
 * comment, and still there when someone asks in three weeks what the agent concluded.
 *
 * @param id          stable identifier, referenced from chat and from the ticket
 * @param tenantId    tenant scope
 * @param executionId which run produced it
 * @param kind        what sort of thing it is
 * @param title       how it is listed
 * @param body        the content, Markdown
 * @param createdAt   when
 */
public record Artifact(String id, String tenantId, String executionId, Kind kind,
                       String title, String body, Instant createdAt) {

    public enum Kind { INVESTIGATION_REPORT, REMEDIATION_SUMMARY, PROPOSED_WORKFLOW, RUNBOOK,
        TICKET_ANALYSIS }

    public Artifact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
