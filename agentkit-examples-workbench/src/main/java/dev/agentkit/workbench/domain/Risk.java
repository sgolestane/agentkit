package dev.agentkit.workbench.domain;

/**
 * How much damage an action can do, ordered so that risk can only be raised.
 *
 * <p>Separate from AgentKit's {@code SideEffects}: that is a binary the framework's
 * read-only gate enforces, this is what an approval policy grades and what a human reads on
 * the approval card. The baseline lives on the tool's policy; the supervisor may raise it
 * from the arguments and may never lower it.
 */
public enum Risk {

    /** Observes; changes nothing. */
    READ,

    /** Leaves a trace someone could undo without thinking about it. */
    LOW,

    /** A real change with a clear reverse, e.g. assigning or transitioning a ticket. */
    MEDIUM,

    /** A privileged or wide-blast-radius change. */
    HIGH,

    /** Irreversible. */
    DESTRUCTIVE;

    /** The higher of the two — the only direction the supervisor may move. */
    public Risk raisedTo(Risk other) {
        return other.ordinal() > ordinal() ? other : this;
    }

    /** Whether this is at least as severe as {@code threshold}. */
    public boolean atLeast(Risk threshold) {
        return ordinal() >= threshold.ordinal();
    }
}
