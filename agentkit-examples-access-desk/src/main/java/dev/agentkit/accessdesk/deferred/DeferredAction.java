package dev.agentkit.accessdesk.deferred;

import java.time.Instant;
import java.util.Objects;

/**
 * Work to carry out for a subject at a later time, and what became of it.
 *
 * @param id          {@code kind_subjectId_yyyyMMddHHmm}, so a second goal for the same subject at the same
 *                    minute replaces the first
 * @param when        how the time was given, e.g. {@code "15 minutes before expires_at (2026-09-16T18:00:00Z)"}
 * @param goal        what to do, written by the model when it was scheduled
 * @param scheduledBy who scheduled it
 * @param outcome     what the run said, once it has run; empty before
 */
public record DeferredAction(String id, String subjectKind, String subjectId, Instant runAt, String when,
                             String goal, Instant scheduledAt, String scheduledBy, Status status,
                             String outcome, Instant finishedAt) {

    public enum Status { SCHEDULED, RUNNING, DONE, FAILED }

    public DeferredAction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(subjectKind, "subjectKind");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(runAt, "runAt");
        Objects.requireNonNull(goal, "goal");
        status = status == null ? Status.SCHEDULED : status;
        when = when == null ? "" : when;
        scheduledBy = scheduledBy == null ? "" : scheduledBy;
        outcome = outcome == null ? "" : outcome;
    }

    DeferredAction withStatus(Status next, String result, Instant at) {
        return new DeferredAction(id, subjectKind, subjectId, runAt, when, goal, scheduledAt, scheduledBy, next,
                result, at);
    }
}
