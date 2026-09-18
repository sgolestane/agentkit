package dev.agentkit.core.routine;

import dev.agentkit.core.tool.Provenance;
import java.util.List;
import java.util.Objects;

/**
 * What came of running a {@link Routine} again.
 *
 * <p>A replay either does the whole sequence or stops at the first step that did not go as recorded — a missing
 * tool, a gate that refused, an error, a placeholder the task does not fill. There is no second attempt and no
 * improvisation: improvising is what the model is for, and {@link #done} says what it has to pick up from.
 *
 * @param routine   what was replayed
 * @param done      each step that ran, in order
 * @param stoppedAt the index of the step that stopped it, or {@code -1} if it finished
 * @param reason    why it stopped, or empty if it finished
 * @param lowered   whether a step returned somebody else's words, as the run's trust floor counts them — so whoever
 *                  continues the work must do so under the tightened policy
 */
public record Replay(Routine routine, List<Step> done, int stoppedAt, String reason, boolean lowered) {

    /**
     * One step that ran.
     *
     * @param tool       the tool
     * @param output     what it returned, cut to {@link Routines#MAX_OUTPUT_CHARS}
     * @param provenance whose words the output is
     */
    public record Step(String tool, String output, Provenance provenance) {
        public Step {
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(provenance, "provenance");
        }
    }

    public Replay {
        Objects.requireNonNull(routine, "routine");
        Objects.requireNonNull(done, "done");
        Objects.requireNonNull(reason, "reason");
        done = List.copyOf(done);
    }

    /** Whether every step ran. */
    public boolean finished() {
        return stoppedAt < 0;
    }

    /** Whether any step ran at all — the question that decides whether a fallback is starting from a clean world. */
    public boolean changedAnything() {
        return !done.isEmpty();
    }

    /** What the last step returned, or empty if none ran. */
    public String lastOutput() {
        return done.isEmpty() ? "" : done.getLast().output();
    }

    /** The steps that ran, as text: what a model needs to be told before it picks the work up. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        for (Step step : done) {
            text.append("- ").append(step.tool()).append(": ").append(step.output()).append('\n');
        }
        if (!finished()) {
            text.append("Stopped before step ").append(stoppedAt + 1).append(" of ").append(routine.steps().size())
                    .append(" (").append(routine.steps().get(stoppedAt).tool()).append("): ").append(reason);
        }
        return text.toString().strip();
    }
}
