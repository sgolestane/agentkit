package dev.agentkit.itops.domain;

/**
 * How much damage an action can do, ordered so that risk can only be raised.
 *
 * <p>Separate from AgentKit's {@code SideEffects}, and the two answer different questions.
 * {@code SideEffects} asks "does this leave anything behind outside the process" — a binary
 * the framework's {@code ToolGates.readOnly()} enforces. {@code Risk} asks "how bad is it
 * if this was the wrong call", which is what an approval policy needs and what a human
 * reads on the approval card. A tool declares both: {@code SideEffects} so the framework
 * can gate it, {@code Risk} so the supervisor can grade it.
 *
 * <p>The baseline lives on the tool. The supervisor may raise it from the arguments — adding
 * someone to {@code Employees-All} and adding them to {@code Production-Administrators} are
 * the same tool call with the same schema and very different consequences — and it may
 * never lower it. Escalate-only is what stops a persuaded model from arguing its way down.
 */
public enum Risk {

    /** Observes; changes nothing. */
    READ,

    /** Leaves a trace someone could undo without thinking about it, e.g. a comment. */
    LOW,

    /** A real change with a clear reverse, e.g. assigning a ticket. */
    MEDIUM,

    /** A privileged or wide-blast-radius change, e.g. granting administrative access. */
    HIGH,

    /** Irreversible, e.g. deleting an identity and its credentials. */
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
