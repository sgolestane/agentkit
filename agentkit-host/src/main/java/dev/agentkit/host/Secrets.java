package dev.agentkit.host;

import java.util.Map;
import java.util.Optional;

/**
 * An organization's secrets, by name: what {@code ${secret:NAME}} in a connector file becomes. A repository names
 * secrets and never holds them, so a connector's credentials stay out of Git.
 */
@FunctionalInterface
public interface Secrets {

    Optional<String> get(String name);

    /** No secrets at all. */
    Secrets NONE = name -> Optional.empty();

    static Secrets of(Map<String, String> values) {
        Map<String, String> copy = Map.copyOf(values);
        return name -> Optional.ofNullable(copy.get(name));
    }

    /** Environment variables named {@code prefix + NAME}, for a single-organization deployment. */
    static Secrets fromEnv(String prefix) {
        return name -> Optional.ofNullable(System.getenv(prefix + name)).filter(v -> !v.isBlank());
    }
}
