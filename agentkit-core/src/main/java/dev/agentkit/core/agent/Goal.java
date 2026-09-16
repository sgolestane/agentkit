package dev.agentkit.core.agent;

import dev.agentkit.core.util.Frozen;
import java.util.Map;
import java.util.Objects;

/**
 * The objective handed to an agent.
 *
 * <p>A goal is the primary input to an unsupervised agent run: a natural-language
 * description of what to achieve, plus optional structured parameters that the
 * application wants to make available (identifiers, constraints, references).
 *
 * <h2>A parameter is a claim the transcript has already made</h2>
 *
 * <p>{@link #render()} puts every parameter into the prompt text both loops send, and a
 * goal propagates: {@code ReflectiveAgent}, {@code SelfVerifyingAgent} and
 * {@code GraphNode} each build a derived goal by handing {@link #parameters()} straight to
 * this constructor. Until #133 the copy was one level deep, so all of them shared one
 * nested object with whatever the application still held — and an application mutating it
 * mid-run changed what the model was told on a later turn, with the earlier turns sitting
 * in the same conversation saying otherwise. That is not an authorization boundary, which
 * is why #128 stopped short of it; it is a boundary the run's own record depends on.
 *
 * <p>So the copy reaches every nested map and collection ({@code Frozen.containersOf}).
 * Anything else — an {@code Instant}, a {@code Duration}, an application's own object —
 * is stored by reference and can still change, because the value is the deployment's and
 * this constructor cannot copy a type it does not know. {@code Frozen.deeply}, which
 * <em>refuses</em> such a value, is the wrong instrument here: no hostile party picks a
 * goal parameter's type, and refusing a {@code Duration} would break a working deployment
 * to close a hole that is not open. The class javadoc on {@code Frozen} states the split.
 *
 * <p><strong>What it costs, because a goal is a durable workflow input.</strong> Jackson
 * rebuilds it through this constructor on every replay of every step, so the copy is paid
 * per replay per step. Measured as bytes allocated by the copy itself, identical across
 * three JVMs, on a parameter map of sixteen expanded values of the shape an application
 * actually writes: 416 B for the one-level copy this replaced, 1,072 B for the copy that
 * reaches the containers. 656 B a replay, against the 31,105 ns and 7,341 ns #63 measured
 * for serializing and deserializing an 8 KB goal around it. Bytes and not elapsed time
 * deliberately — wall clock on a shared machine put #170's equivalent share anywhere from
 * 29% to 76%, which is noise. The walk is over containers rather than text, so a goal that
 * is large because its parameters are long strings pays nothing extra for being large.
 *
 * @param description human-readable statement of what to achieve; never
 *                    {@code null} or blank
 * @param parameters structured, application-supplied context; never {@code null}. Stored
 *                   as a defensive, unmodifiable, order-preserving copy that reaches every
 *                   nested map and collection; a value of any other type is shared. See
 *                   above, and {@link Frozen#containersOf}
 */
public record Goal(String description, Map<String, Object> parameters) {

    public Goal {
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("Goal description must not be blank");
        }
        Objects.requireNonNull(parameters, "parameters");
        parameters = Frozen.containersOf(parameters);
    }

    public static Goal of(String description) {
        return new Goal(description, Map.of());
    }

    /**
     * Renders the goal as prompt text: the description, followed by a
     * deterministically-ordered list of parameters if any are present. Shared by
     * the in-process and durable loops so they cannot drift on goal formatting.
     */
    public String render() {
        if (parameters.isEmpty()) {
            return description;
        }
        StringBuilder sb = new StringBuilder(description).append("\n\nParameters:");
        parameters.forEach((k, v) -> sb.append("\n- ").append(k).append(": ").append(v));
        return sb.toString();
    }
}
