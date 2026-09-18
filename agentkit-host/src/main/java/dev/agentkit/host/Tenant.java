package dev.agentkit.host;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Whose conversations these are: a person in an organization. A chat store scopes everything by one string, and this
 * is what the host puts in it — {@code org/email} — so two organizations' people never share a conversation list,
 * even when an email is the same in both.
 */
public record Tenant(String org, String email) {

    public Tenant {
        org = Objects.requireNonNull(org, "org").strip();
        email = Objects.requireNonNull(email, "email").strip().toLowerCase(Locale.ROOT);
        if (org.isEmpty() || org.contains("/") || email.isEmpty()) {
            throw new IllegalArgumentException("A tenant is an organization and an email");
        }
    }

    /** The string a chat store keys this tenant's conversations by. */
    public String id() {
        return org + "/" + email;
    }

    /** The tenant a chat store's key names; empty for a key the host did not write. */
    public static Optional<Tenant> parse(String id) {
        if (id == null) {
            return Optional.empty();
        }
        int slash = id.indexOf('/');
        if (slash <= 0 || slash == id.length() - 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Tenant(id.substring(0, slash), id.substring(slash + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return id();
    }
}
