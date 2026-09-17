package dev.agentkit.examples.deferred;

/**
 * What a tool does to the world, as far as deferred work cares. A tool declares one in its
 * {@link ToolInfo}; what a deferred action may use when it runs is decided from these alone.
 */
public enum Effect {
    /** Looks something up, or asks someone for information; changes nothing a subject holds. */
    READ,
    /** Gives a subject something: access, a seat, equipment, an enrollment. */
    GRANT,
    /** Takes something away from a subject. */
    REVOKE,
    /** Tells a person something. */
    NOTIFY,
    /** Asks another team to do something about a subject, such as opening a ticket. */
    REQUEST,
    /** Schedules work for later. */
    SCHEDULE
}
