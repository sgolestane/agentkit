package dev.agentkit.chat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One thing that happened inside a {@link Turn}, in the order it happened.
 *
 * <p>This is the trace — the thing an operator opens when an answer is wrong. An earlier
 * prototype console built one of these by hand from two witnesses, a wrapper around the
 * {@code LlmClient} and an {@code AgentObserver}, and rendered it as lines of text under the bubble. It was the best
 * thing in that console and it was a {@code List<String>}, so nothing could sort it, filter
 * it, or total it. A step is that idea with a shape.
 *
 * <p><strong>{@code sequence} is the order, and it is recorded rather than inferred.</strong>
 * A turn's steps arrive from more than one thread — the model call returns on the worker, a
 * tool may not — and a list's index is only the order things were <em>added</em>. The
 * sequence is assigned when the step is created, so the trace reads the way the run
 * happened even when the writes race.
 *
 * @param sequence where this falls in the turn, from 1
 * @param kind     what happened
 * @param name     the tool, the model, or a word for a step that is neither
 * @param detail   whatever the kind carries; JSON values only, for the same reason
 *                 {@link dev.agentkit.core.tool.View} takes them
 * @param at       when it started
 * @param millis   how long it took, or 0 for a step with no duration
 * @param failed   whether this step is the reason the turn did not go well
 */
public record Step(long sequence, Kind kind, String name, Map<String, Object> detail,
                   Instant at, long millis, boolean failed) {

    /**
     * What a step is.
     *
     * <p>Deliberately small. A kind exists when a renderer would draw it differently or a
     * reader would filter on it; anything else is {@link #NOTE} with a name, which is the
     * escape hatch that keeps this enum from growing a case per feature.
     */
    public enum Kind {
        /** A request to the model and what came back. */
        MODEL_CALL,
        /** A tool the model proposed, and how the call settled. */
        TOOL_CALL,
        /** A tool result carried something for a person to look at. */
        VIEW,
        /** A gate stopped a call to ask somebody. */
        APPROVAL_REQUESTED,
        /** Anything the runtime wants on the record that is none of the above. */
        NOTE
    }

    public Step {
        Objects.requireNonNull(kind, "kind");
        name = name == null ? "" : name;
        detail = detail == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(detail));
        Objects.requireNonNull(at, "at");
    }

    public static Step of(long sequence, Kind kind, String name, Map<String, Object> detail,
            Instant at, long millis) {
        return new Step(sequence, kind, name, detail, at, millis, false);
    }
}
