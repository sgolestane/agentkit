package dev.agentkit.core.supervisor;

import dev.agentkit.core.agent.Goal;
import java.util.Objects;

/**
 * An assignment of one subgoal to a named subagent — the unit of work a
 * {@link Supervisor} fans out.
 *
 * @param subagentName the name of the {@link Subagent} to run this subgoal; must
 *                     resolve in the supervisor's roster
 * @param goal         the subgoal to pursue
 */
public record DelegatedTask(String subagentName, Goal goal) {

    public DelegatedTask {
        Objects.requireNonNull(subagentName, "subagentName");
        Objects.requireNonNull(goal, "goal");
    }

    public static DelegatedTask of(String subagentName, Goal goal) {
        return new DelegatedTask(subagentName, goal);
    }

    public static DelegatedTask of(String subagentName, String goalDescription) {
        return new DelegatedTask(subagentName, Goal.of(goalDescription));
    }
}
