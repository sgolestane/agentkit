package dev.agentkit.core.goap;

import java.util.Set;

/**
 * Thrown when no sequence of the available actions could meet the objective.
 *
 * <p>Carries the diagnosis rather than just "no plan found", because the two causes want
 * opposite fixes: a fact <em>nothing produces</em> means an action is missing, and a fact
 * that is produced only by actions whose own needs can never be met means the chain is
 * broken further back. {@link #getMessage()} says which, and names the actions involved.
 */
public class UnreachableObjectiveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Set<String> unreachable;

    UnreachableObjectiveException(String message, Set<String> unreachable) {
        super(message);
        // Not transient: it was, with no readObject, so a deserialized exception reported
        // null for the one thing it exists to carry.
        this.unreachable = java.util.Collections.unmodifiableSet(
                new java.util.LinkedHashSet<>(unreachable));
    }

    /** The objective's fact keys that could not be established from the starting state. */
    public Set<String> unreachable() {
        return unreachable;
    }
}
