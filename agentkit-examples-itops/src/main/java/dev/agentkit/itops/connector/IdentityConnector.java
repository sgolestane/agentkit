package dev.agentkit.itops.connector;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * A stand-in identity provider (Okta-shaped): users, groups and membership.
 *
 * <p>The methods that change something are written to be <em>idempotent</em>, and that is
 * not decoration. An agent runtime that can crash between "the call succeeded" and "the
 * result was persisted" will re-run the call on recovery, so a membership add that fails
 * the second time turns a successful remediation into a failed execution. Adding a member
 * who is already a member returns {@code false} for "did anything change" and no error,
 * which is what lets the runtime retry without thinking.
 */
public final class IdentityConnector {

    /**
     * @param email  the identifier
     * @param name   display name
     * @param status {@code ACTIVE}, {@code SUSPENDED} or {@code DEPROVISIONED}
     * @param admin  whether the account carries administrative privilege
     */
    public record User(String email, String name, String status, boolean admin) {
        public User {
            Objects.requireNonNull(email, "email");
            Objects.requireNonNull(status, "status");
        }
    }

    /**
     * @param name       the group's name, which is also how a ticket refers to it
     * @param privileged whether membership grants elevated access
     */
    public record Group(String name, String privileged) {}

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> members = new ConcurrentHashMap<>();
    private final Map<String, Boolean> privilegedGroups = new ConcurrentHashMap<>();

    /**
     * A group as this connector holds one: a name, whether it is privileged, and who is in
     * it.
     *
     * <p>Distinct from {@link Group}, which is what a <em>lookup</em> returns and carries no
     * membership. Seeding needs the members and the answer to a lookup does not.
     */
    public record GroupSeed(String name, boolean privileged, Set<String> members) {}

    /** The demo tenant: four accounts, four groups, two of them privileged. */
    public IdentityConnector() {
        this(List.of(
                        new User("alice@example.com", "Alice Smith", "ACTIVE", false),
                        new User("alice.johnson@example.com", "Alice Johnson", "ACTIVE", false),
                        new User("bob@example.com", "Bob Reeves", "ACTIVE", true),
                        new User("carol@example.com", "Carol Nguyen", "ACTIVE", false)),
                List.of(
                        new GroupSeed("Finance Application Users", false,
                                Set.of("carol@example.com")),
                        new GroupSeed("Employees-All", false,
                                Set.of("alice@example.com", "bob@example.com",
                                        "carol@example.com")),
                        new GroupSeed("Production-Administrators", true,
                                Set.of("bob@example.com")),
                        new GroupSeed("Finance Admin", true, Set.of())));
    }

    /**
     * The same connector over a tenant the caller supplies.
     *
     * <p>Here for the reason {@code DirectoryConnector}'s roster constructor is: the demo
     * tenant was written into the no-argument constructor of a {@code final} class, so no
     * test could ask what {@code IdentityTools} does with a display name, a status or a
     * group name that an identity provider did not write. Every one of those is rendered
     * into a prompt, and #191 found four of them reaching it through an unbounded fence.
     */
    public IdentityConnector(List<User> seedUsers, List<GroupSeed> seedGroups) {
        seedUsers.forEach(user -> users.put(user.email(), user));
        seedGroups.forEach(seed -> group(seed.name(), seed.privileged(), seed.members()));
    }

    private void group(String name, boolean privileged, Set<String> initialMembers) {
        privilegedGroups.put(name, privileged);
        members.put(name, new CopyOnWriteArraySet<>(initialMembers));
    }

    public Optional<User> findUser(String email) {
        return Optional.ofNullable(users.get(email == null ? "" : email.strip()));
    }

    public Optional<Group> findGroup(String name) {
        String key = name == null ? "" : name.strip();
        Boolean privileged = privilegedGroups.get(key);
        return privileged == null ? Optional.empty()
                : Optional.of(new Group(key, privileged ? "true" : "false"));
    }

    /** Whether a group grants elevated access — what turns a MEDIUM action into a HIGH one. */
    public boolean isPrivileged(String groupName) {
        return Boolean.TRUE.equals(privilegedGroups.get(groupName == null ? "" : groupName.strip()));
    }

    public List<String> groupMembers(String groupName) {
        Set<String> current = members.get(groupName == null ? "" : groupName.strip());
        return current == null ? List.of() : current.stream().sorted().toList();
    }

    /** @return {@code true} if this call changed anything; {@code false} if already a member */
    public boolean addUserToGroup(String email, String groupName) {
        Set<String> current = members.get(groupName.strip());
        if (current == null) {
            throw new IllegalArgumentException("No such group: " + groupName);
        }
        if (!users.containsKey(email.strip())) {
            throw new IllegalArgumentException("No such user: " + email);
        }
        return current.add(email.strip());
    }

    /** @return {@code true} if this call changed anything */
    public boolean removeUserFromGroup(String email, String groupName) {
        Set<String> current = members.get(groupName.strip());
        return current != null && current.remove(email.strip());
    }

    /** Reversible: the account keeps its identity and can be reactivated. */
    public boolean suspendUser(String email) {
        User user = users.get(email.strip());
        if (user == null) {
            throw new IllegalArgumentException("No such user: " + email);
        }
        if ("SUSPENDED".equals(user.status())) {
            return false;
        }
        users.put(user.email(), new User(user.email(), user.name(), "SUSPENDED", user.admin()));
        return true;
    }

    /**
     * Irreversible: the account and its credentials are gone.
     *
     * <p>Left un-idempotent on purpose — deleting an already-deleted user throws rather than
     * shrugging, because "it was already done" and "I did it" are different facts and the
     * audit trail should not be able to confuse them for a destructive operation.
     */
    public void deleteUser(String email) {
        User removed = users.remove(email.strip());
        if (removed == null) {
            throw new IllegalArgumentException("No such user: " + email);
        }
        members.values().forEach(set -> set.remove(email.strip()));
    }
}
