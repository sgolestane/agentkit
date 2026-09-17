package dev.agentkit.accessdesk.tools;

import java.util.Locale;
import java.util.Optional;

/**
 * What a tool does to the world. A tool declares one ({@link ToolInfo}); which tools the chat agent
 * is given, and what a deferred action may use when it runs, are decided from these declarations.
 */
public enum Effect {
    /** Looks something up; changes nothing anybody holds. */
    READ,
    /** Gives a person access or something else to hold. */
    GRANT,
    /** Takes access or something held away. */
    REVOKE,
    /** Tells a person something. */
    NOTIFY,
    /** Records a request, or asks another team to do something. */
    REQUEST,
    /** Schedules work for later. */
    SCHEDULE;

    /** The lowercase name used in files and over MCP. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses {@link #wire()}, case-insensitively; empty for anything else. */
    public static Optional<Effect> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (Effect effect : values()) {
            if (effect.wire().equalsIgnoreCase(value.strip())) {
                return Optional.of(effect);
            }
        }
        return Optional.empty();
    }
}
