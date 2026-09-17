package dev.agentkit.core.tool;

import java.util.Locale;
import java.util.Optional;

/**
 * What a tool does to the world, in the terms a policy about tools is written in: does it look, give,
 * take away, tell, ask or schedule.
 *
 * <p>Not {@link SideEffects}, and not a replacement for it. {@code SideEffects} answers the loop's own
 * questions — may a rehearsal run this, is a step safe to retry. An effect answers a deployment's: may a
 * deferred action use this tool, may a model be handed it at all. A revocation and a grant can both be
 * {@link SideEffects#IDEMPOTENT}; only one of them is something a job that runs months later on the
 * operator's behalf should be able to do.
 *
 * <p>A tool declares one in a {@link ToolDeclaration}, kept beside it in a {@link DeclaredTools}.
 */
public enum ToolEffect {
    /** Looks something up, or asks someone for information; changes nothing anybody holds. */
    READ,
    /** Gives someone something: access, a seat, equipment, an enrollment. */
    GRANT,
    /** Takes something someone holds away. */
    REVOKE,
    /** Tells a person something. */
    NOTIFY,
    /** Records a request, or asks another team to do something. */
    REQUEST,
    /** Schedules work for later. */
    SCHEDULE;

    /** The lowercase name used in files and on the wire. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses {@link #wire()}, ignoring case; empty for anything else, including null. */
    public static Optional<ToolEffect> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (ToolEffect effect : values()) {
            if (effect.wire().equalsIgnoreCase(value.strip())) {
                return Optional.of(effect);
            }
        }
        return Optional.empty();
    }
}
