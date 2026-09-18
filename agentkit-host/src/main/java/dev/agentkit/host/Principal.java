package dev.agentkit.host;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The person an agent is acting for: who they are in their organization, as the host established it, and what the
 * organization's directory says about them.
 *
 * <p>A turn's agent acts only as its principal. Arguments an agent definition binds ({@code principal.email},
 * {@code principal.manager}) are filled from here and hidden from the model, so what a connector is told about who
 * is asking never comes from the model.
 *
 * @param org    the organization
 * @param id     the host's stable id for the person
 * @param email  their work email, lowercased
 * @param groups the groups they are in, for an agent's audience
 * @param facts  their directory record, one value per field
 */
public record Principal(String org, String id, String email, Set<String> groups, Map<String, String> facts) {

    public Principal {
        Objects.requireNonNull(org, "org");
        Objects.requireNonNull(id, "id");
        email = Objects.requireNonNull(email, "email").strip().toLowerCase(Locale.ROOT);
        groups = Set.copyOf(groups);
        facts = Map.copyOf(facts);
    }

    /**
     * The value a binding such as {@code principal.email} names: {@code org}, {@code id} and {@code email} are the
     * host's own, anything else is a field of the directory record. Empty when the record has no such field.
     */
    public Optional<String> value(String path) {
        if (path == null || !path.startsWith("principal.")) {
            return Optional.empty();
        }
        String field = path.substring("principal.".length());
        return switch (field) {
            case "org" -> Optional.of(org);
            case "id" -> Optional.of(id);
            case "email" -> Optional.of(email);
            default -> Optional.ofNullable(facts.get(field)).filter(v -> !v.isBlank());
        };
    }
}
