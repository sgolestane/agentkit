package dev.agentkit.itops.connector;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The authoritative source for who someone is — the HR system, not the identity provider.
 *
 * <p>Kept separate from {@link IdentityConnector} on purpose. "Who is Alice" and "what
 * access does alice@example.com have" are different questions with different authorities,
 * and collapsing them is how an agent ends up treating the existence of an account as proof
 * of employment. The termination scenario turns on exactly that distinction: the directory
 * says Bob is terminated, and that is the evidence the approval card shows.
 */
public final class DirectoryConnector {

    /**
     * A person, as HR knows them.
     *
     * @param name         display name
     * @param email        the identifier other systems key on
     * @param department   where they work
     * @param manager      who they report to
     * @param employmentStatus {@code ACTIVE} or {@code TERMINATED}
     * @param terminationDate when they left, or {@code null}
     */
    public record Employee(String name, String email, String department, String manager,
                           String employmentStatus, LocalDate terminationDate) {
        public Employee {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(email, "email");
            Objects.requireNonNull(employmentStatus, "employmentStatus");
        }
    }

    /** The people the demo knows about, including the two Alices the scenarios turn on. */
    private static List<Employee> demoRoster() {
        return List.of(
                new Employee("Alice Smith", "alice@example.com", "Finance", "carol@example.com",
                        "ACTIVE", null),
                new Employee("Alice Johnson", "alice.johnson@example.com", "Marketing",
                        "dave@example.com", "ACTIVE", null),
                new Employee("Bob Reeves", "bob@example.com", "Platform Engineering",
                        "erin@example.com", "TERMINATED", LocalDate.now().minusDays(3)),
                new Employee("Carol Nguyen", "carol@example.com", "Finance", "erin@example.com",
                        "ACTIVE", null));
    }

    private final List<Employee> employees;

    /** The demo roster. */
    public DirectoryConnector() {
        this(demoRoster());
    }

    /**
     * The same connector over a roster the caller supplies.
     *
     * <p>Here because the roster was a field initialiser on a {@code final} class, so
     * nothing could ask this connector what it does with a name an HR system did not write
     * — and {@code DirectoryTools} renders every field of every record it returns. #191's
     * sweep needs a directory whose {@code name} and {@code email} carry the canary, which
     * is the only way to tell a tool that fences its records from one that happens to be
     * fed records with nothing in them worth fencing.
     */
    public DirectoryConnector(List<Employee> roster) {
        this.employees = List.copyOf(roster);
    }

    /**
     * Everyone matching {@code query} by name or email.
     *
     * <p>Returns every match rather than a best one, and that is the whole reason this
     * method exists in the shape it does. "Alice" matches two people here, and an agent
     * handed one of them has no way to know it was handed a guess. Returning both forces
     * the ambiguity up to where it can be resolved — a clarifying question, not a coin flip.
     */
    public List<Employee> search(String query) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).strip();
        if (needle.isEmpty()) {
            return List.of();
        }
        return employees.stream()
                .filter(e -> e.name().toLowerCase(Locale.ROOT).contains(needle)
                        || e.email().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }
}
