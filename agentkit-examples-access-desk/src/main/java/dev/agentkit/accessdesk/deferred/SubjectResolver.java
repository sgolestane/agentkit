package dev.agentkit.accessdesk.deferred;

import java.util.Optional;
import java.util.Set;

/**
 * Looks a subject up in its system of record. Asked when a deferred action is scheduled and again when
 * it runs, so a run sees the record as it is then.
 */
public interface SubjectResolver {

    /** The kinds of subject this resolver knows. */
    Set<String> kinds();

    /** The current record for a subject, if there is one. */
    Optional<SubjectRecord> resolve(String kind, String id);
}
