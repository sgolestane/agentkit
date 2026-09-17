package dev.agentkit.accessdesk.desk;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * What a customer customizes, as files: the access policy, the desk's system prompt, the prompt deferred
 * actions run with, and the connectors. The defaults ship under {@code access-desk/} on the classpath; an
 * environment variable naming a file replaces each one without a code change or a rebuild.
 *
 * @param policy         {@code ACCESS_DESK_POLICY_FILE}
 * @param systemPrompt   {@code ACCESS_DESK_SYSTEM_PROMPT_FILE}
 * @param deferredPrompt {@code ACCESS_DESK_DEFERRED_PROMPT_FILE}
 * @param connectors     {@code ACCESS_DESK_CONNECTORS_FILE}
 */
public record DeskConfig(String policy, String systemPrompt, String deferredPrompt, String connectors) {

    public DeskConfig {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(deferredPrompt, "deferredPrompt");
        Objects.requireNonNull(connectors, "connectors");
    }

    public static DeskConfig fromEnv() {
        return from(System.getenv());
    }

    public static DeskConfig from(Map<String, String> env) {
        return new DeskConfig(
                load(env, "ACCESS_DESK_POLICY_FILE", "access-desk/policy.md"),
                load(env, "ACCESS_DESK_SYSTEM_PROMPT_FILE", "access-desk/system-prompt.md"),
                load(env, "ACCESS_DESK_DEFERRED_PROMPT_FILE", "access-desk/deferred-prompt.md"),
                load(env, "ACCESS_DESK_CONNECTORS_FILE", "access-desk/connectors.json"));
    }

    private static String load(Map<String, String> env, String variable, String resource) {
        String path = env.get(variable);
        try {
            if (path != null && !path.isBlank()) {
                return Files.readString(Path.of(path.strip()), StandardCharsets.UTF_8).strip();
            }
            try (InputStream in = DeskConfig.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("Missing default resource " + resource);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + (path != null ? path : resource) + " for " + variable, e);
        }
    }
}
