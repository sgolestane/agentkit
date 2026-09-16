package dev.agentkit.core.planning;

import java.util.List;
import java.util.Objects;

/**
 * An ordered list of concrete steps for achieving a goal, produced by a
 * {@link Planner} and run by a {@link PlanningAgent}.
 *
 * @param steps the steps in execution order; never {@code null}. Stored as an
 *              unmodifiable copy. May be empty (a planner that could not decompose
 *              the goal), in which case the {@link PlanningAgent} runs the goal
 *              directly.
 */
public record Plan(List<String> steps) {

    public Plan {
        Objects.requireNonNull(steps, "steps");
        steps = List.copyOf(steps);
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public int size() {
        return steps.size();
    }
}
