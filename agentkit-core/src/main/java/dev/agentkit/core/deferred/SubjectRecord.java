package dev.agentkit.core.deferred;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a deferred action is about, as its system of record describes it now: a grant in the access
 * ledger, a worker in an HRIS, an account in a CRM.
 *
 * @param kind        what sort of subject this is ("grant")
 * @param id          its id in the system of record
 * <p>Identifiers and contacts match exactly, after surrounding whitespace is stripped: ids in most systems are
 * case-sensitive ({@code 00Q5e000abc} and {@code 00Q5E000ABC} are different CRM records). An email address is the
 * exception and matches ignoring case, because mail systems treat it that way and a model rarely preserves it.
 *
 * @param identifiers every value a tool argument may use to refer to it; a deferred action for this
 *                    subject may act only on these
 * @param contacts    people a deferred action for this subject may notify besides the subject itself
 * @param facts       the record's fields
 */
public record SubjectRecord(String kind, String id, Set<String> identifiers, Set<String> contacts,
                            Map<String, String> facts) {

    public SubjectRecord {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        identifiers = stripped(identifiers);
        contacts = stripped(contacts);
        facts = Map.copyOf(facts);
    }

    /** Whether {@code value} refers to this subject. */
    public boolean refersTo(String value) {
        return matchesAny(identifiers, value);
    }

    /** Whether {@code value} is one of this subject's contacts. */
    public boolean isContact(String value) {
        return matchesAny(contacts, value);
    }

    /** A time field of the record — an ISO instant, or a date meaning its start in UTC — if it has one. */
    public Optional<Instant> instant(String field) {
        String value = facts.get(field);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value.strip()));
        } catch (DateTimeParseException notAnInstant) {
            try {
                return Optional.of(LocalDate.parse(value.strip()).atStartOfDay().toInstant(ZoneOffset.UTC));
            } catch (DateTimeParseException notADate) {
                return Optional.empty();
            }
        }
    }

    private static boolean matchesAny(Set<String> known, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String candidate = value.strip();
        return known.stream().anyMatch(k -> k.equals(candidate) || (isEmail(k) && k.equalsIgnoreCase(candidate)));
    }

    private static boolean isEmail(String value) {
        int at = value.indexOf('@');
        return at > 0 && at == value.lastIndexOf('@') && at < value.length() - 1;
    }

    private static Set<String> stripped(Set<String> values) {
        return values.stream().filter(Objects::nonNull).filter(v -> !v.isBlank())
                .map(String::strip).collect(Collectors.toUnmodifiableSet());
    }
}
