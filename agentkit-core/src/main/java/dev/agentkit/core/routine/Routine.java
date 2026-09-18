package dev.agentkit.core.routine;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The same work, done the same way enough times to stop paying a model to work it out again.
 *
 * <p>A routine is a sequence of tool calls that succeeded for a {@link Task}, with the task's values left as
 * placeholders ({@link RoutineStep}). It is a <em>recording</em>, not a plan: nobody wrote it, no model is consulted
 * to follow it, and {@link Routines#replay} runs it through the ordinary registry and gate.
 *
 * @param taskKind  the kind of work this was recorded for
 * @param steps     the calls, in order
 * @param timesSeen how many successful runs ended in exactly this sequence
 */
public record Routine(String taskKind, List<RoutineStep> steps, int timesSeen) {

    public Routine {
        Objects.requireNonNull(taskKind, "taskKind");
        Objects.requireNonNull(steps, "steps");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a routine has at least one step");
        }
        if (timesSeen < 1) {
            throw new IllegalArgumentException("a routine was seen at least once, not " + timesSeen + " times");
        }
        steps = List.copyOf(steps);
    }

    /** Every parameter this routine's steps expect, in name order. */
    public Set<String> parameters() {
        Set<String> names = new TreeSet<>();
        steps.forEach(step -> names.addAll(step.placeholders()));
        return names;
    }

    /** The tools it calls, in order, with repeats: the shape two runs have to share to be the same routine. */
    public List<String> toolNames() {
        return steps.stream().map(RoutineStep::tool).toList();
    }

    /** Whether {@code task} supplies every parameter the steps expect. */
    public boolean fits(Task task) {
        return task.kind().equals(taskKind) && task.parameters().keySet().containsAll(parameters());
    }

    /** One line for a log or a report. */
    public String describe() {
        return taskKind + ": " + String.join(" → ", toolNames()) + " (seen " + timesSeen + " times)";
    }
}
