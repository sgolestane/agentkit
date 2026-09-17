package dev.agentkit.examples.deferred;

import java.time.LocalDate;

/**
 * Work to carry out for a subject on a later date.
 *
 * @param id          {@code kind_subjectId_yyyymmdd}, so a second goal for the same subject on the
 *                    same date replaces the first
 * @param when        how the date was given, e.g. {@code "14 days before termination_date (2026-12-31)"}
 * @param goal        what to do, written by the model when it was scheduled
 * @param scheduledOn the day it was scheduled
 */
public record DeferredAction(String id, String subjectKind, String subjectId, LocalDate runOn, String when,
                             String goal, LocalDate scheduledOn) {
}
