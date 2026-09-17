package dev.agentkit.examples.deferred;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a deferred action is about, as its system of record describes it today: a worker in an
 * HRIS, an account in a CRM, a server in a CMDB.
 *
 * @param kind        what sort of subject this is ("worker", "account")
 * @param id          its id in the system of record
 * @param identifiers every value a tool argument may use to refer to it (an email, the id itself);
 *                    a deferred action for this subject may act only on these
 * @param contacts    people a deferred action for this subject may notify besides the subject
 *                    itself (a manager, an account owner)
 * @param facts       the record's fields, whatever the system of record has
 */
public record SubjectRecord(String kind, String id, Set<String> identifiers, Set<String> contacts,
                            Map<String, String> facts) {

    public SubjectRecord {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        identifiers = lower(identifiers);
        contacts = lower(contacts);
        facts = Map.copyOf(facts);
    }

    /** Whether {@code value} refers to this subject. */
    public boolean refersTo(String value) {
        return value != null && identifiers.contains(value.strip().toLowerCase(Locale.ROOT));
    }

    /** Whether {@code value} is one of this subject's contacts. */
    public boolean isContact(String value) {
        return value != null && contacts.contains(value.strip().toLowerCase(Locale.ROOT));
    }

    /** A date field of the record, if it has one. */
    public Optional<LocalDate> date(String field) {
        try {
            return Optional.ofNullable(facts.get(field)).map(String::strip).map(LocalDate::parse);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Set<String> lower(Set<String> values) {
        return values.stream().filter(Objects::nonNull).map(v -> v.strip().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
