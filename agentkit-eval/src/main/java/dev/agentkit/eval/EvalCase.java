package dev.agentkit.eval;

import dev.agentkit.core.agent.Goal;
import java.util.List;
import java.util.Objects;

/**
 * One evaluation case: a goal to run and the {@link Check}s its result must pass.
 *
 * @param id     a stable identifier for the case (used in the report); never {@code null} or blank
 * @param goal   the goal to run; never {@code null}
 * @param checks the checks to apply to the run; never {@code null}. Stored as an unmodifiable copy
 */
public record EvalCase(String id, Goal goal, List<Check> checks) {

    public EvalCase {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("case id must not be blank");
        }
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(checks, "checks");
        checks = List.copyOf(checks);
    }

    public static EvalCase of(String id, Goal goal, Check... checks) {
        return new EvalCase(id, goal, List.of(checks));
    }
}
