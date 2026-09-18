package dev.agentkit.core.routine;

import dev.agentkit.core.agent.Goal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * How a deployment recognises one of its jobs in a goal.
 *
 * <p>This is the one piece a deployment has to write, and it is the piece only a deployment can write: which part of
 * "Onboard Marcus Bell, employee W-1002, starting Monday" is the job and which part is this week's values. Get it
 * wrong in the safe direction — too specific — and routines are never reused; get it wrong in the other and a
 * recording made for one job is replayed for another, which is why {@link #ofText()} is the default and errs
 * towards never matching anything twice.
 *
 * <p>An empty answer means "not a job I recognise": nothing is recorded and nothing is replayed, which is the right
 * outcome for a one-off.
 *
 * <p>{@link #ofGoalParameters()} fits a deployment that already separates the two: a {@link Goal} carries a
 * description and its own parameters, so the description is the job and the parameters are the values.
 */
@FunctionalInterface
public interface TaskShape {

    /** The task this goal is an instance of, or empty if it is not one this deployment recognises. */
    Optional<Task> of(Goal goal);

    /**
     * The goal's description is the job and the goal's own parameters are the values.
     *
     * <p>For a deployment that already builds goals that way — {@code Goal.of("Onboard a worker")} with
     * {@code employee_id} as a parameter — this is the whole of the recognition, and two hires are the same job.
     * It is the wrong answer when the description itself carries the values, because then every instance is a kind
     * of its own and nothing is ever seen twice.
     */
    static TaskShape ofGoalParameters() {
        return goal -> {
            String kind = goal.description().strip();
            if (kind.isEmpty()) {
                return Optional.empty();
            }
            Map<String, String> values = new LinkedHashMap<>();
            goal.parameters().forEach((name, value) -> {
                // A parameter that cannot be a placeholder is left out, so its value is recorded literally, differs
                // between runs, and keeps the job from settling: unable to generalise, rather than wrong.
                if (value != null && Task.NAME.matcher(name).matches()) {
                    values.put(name, String.valueOf(value));
                }
            });
            return Optional.of(new Task(kind, values));
        };
    }

    /**
     * Every goal is its own kind, with no parameters: only a goal repeated word for word counts as the same job.
     *
     * <p>A starting point that cannot confuse two jobs — it just rarely finds a repeat.
     */
    static TaskShape ofText() {
        return goal -> {
            String text = goal.description().strip();
            return text.isEmpty() ? Optional.empty() : Optional.of(Task.of(text));
        };
    }
}
