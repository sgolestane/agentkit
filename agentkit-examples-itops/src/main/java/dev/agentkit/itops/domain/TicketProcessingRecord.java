package dev.agentkit.itops.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * The platform's own record that a ticket has been taken, and by which execution.
 *
 * <p>This exists because neither of the two easier answers works. A provider-side filter
 * ("only tickets created in the last 15 minutes") re-returns the same rows on the next tick
 * and races two schedulers against each other; model memory is not a lock and does not
 * survive a restart. So the claim lives here, in a row with a uniqueness constraint on
 * {@code (provider, externalTicketId)}, and the execution that successfully inserts it is
 * the one that proceeds. In Postgres this is {@code INSERT … ON CONFLICT DO NOTHING}; in
 * this demo it is {@code ConcurrentHashMap.putIfAbsent}, which has the property that
 * matters — exactly one caller wins, and the loser is told so rather than blocked.
 *
 * <p>For v1 a finished or failed attempt counts as processed. The {@code status} and
 * {@code finishedAt} fields are what a retry policy would later read; nothing reads them
 * that way yet, and pretending otherwise would be the kind of half-built machinery that is
 * harder to remove than to add.
 */
public record TicketProcessingRecord(String tenantId, String provider, String externalTicketId,
                                     Instant firstSeenAt, Instant claimedAt, String executionId,
                                     Status status, Instant finishedAt, String result) {

    public enum Status { CLAIMED, COMPLETED, FAILED, SKIPPED_UNSUPPORTED }

    public TicketProcessingRecord {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(externalTicketId, "externalTicketId");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(status, "status");
    }

    /** The key the uniqueness constraint is on. */
    public String key() {
        return provider + '/' + externalTicketId;
    }

    public TicketProcessingRecord finished(Status outcome, String outcomeText) {
        return new TicketProcessingRecord(tenantId, provider, externalTicketId, firstSeenAt,
                claimedAt, executionId, outcome, Instant.now(), outcomeText);
    }
}
