package dev.agentkit.core.goap;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What the run is for, stated as the facts that must exist when it is over.
 *
 * <p>Deliberately not {@code dev.agentkit.core.agent.Goal}: that one is prose for a model to
 * read, and this one is a condition the planner has to evaluate mechanically after every
 * step. The description here is for the humans reading the trace.
 *
 * @param description what this objective is, in words; never blank
 * @param required    the fact keys that must all exist for the objective to be met; never
 *                    empty, since an objective nothing has to establish is already met
 */
public record Objective(String description, Set<String> required) {

    public Objective {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(required, "required");
        if (description.isBlank()) {
            throw new IllegalArgumentException("An objective's description must not be blank");
        }
        if (required.isEmpty()) {
            throw new IllegalArgumentException("Objective '" + description + "' requires no facts, "
                    + "so it is met before anything runs; name the fact you actually want");
        }
        required = Collections.unmodifiableSet(new LinkedHashSet<>(required));
        for (String key : required) {
            Objects.requireNonNull(key, "required key");
            if (key.isBlank()) {
                throw new IllegalArgumentException("A required fact key must not be blank");
            }
        }
    }

    /** An objective met once every one of {@code required} exists. */
    public static Objective of(String description, String... required) {
        return new Objective(description, new LinkedHashSet<>(List.of(required)));
    }

    /** Whether {@code state} meets this objective. */
    public boolean isMetBy(WorldState state) {
        Objects.requireNonNull(state, "state");
        return state.keys().containsAll(required);
    }

    /** The required facts {@code state} is still missing, in declaration order. */
    public Set<String> missingIn(WorldState state) {
        Objects.requireNonNull(state, "state");
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(state.keys());
        // Not Set.copyOf, which randomises iteration order per JVM run — this set is read
        // straight into failure messages, and a message that reorders itself between runs
        // is a message you cannot diff.
        return Collections.unmodifiableSet(missing);
    }
}
