package dev.agentkit.examples.deferred;

import java.util.Optional;
import java.util.Set;

/**
 * Looks a subject up in its system of record. Each use case supplies one: an HRIS for workers, a
 * CRM for accounts. It is asked when a deferred action is scheduled, and again when it runs, so the
 * run sees the record as it is then rather than as it was.
 */
public interface SubjectResolver {

    /** The kinds of subject this resolver knows. */
    Set<String> kinds();

    /** The current record for a subject, if there is one. */
    Optional<SubjectRecord> resolve(String kind, String id);
}
