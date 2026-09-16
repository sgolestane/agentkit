package dev.agentkit.core.goap;

import dev.agentkit.core.agent.AgentResult;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What one {@link Action} did: the facts it established, or why it could not.
 *
 * @param facts   the facts the action established; never {@code null}, and empty when the
 *                action failed. A successful action is expected to establish every key it
 *                declared it {@link Action#produces() produces} — the runner checks, because
 *                an action that declares a key and does not set it would be re-planned
 *                forever
 * @param result  the agent run behind this action, if it was one; never {@code null}. Present
 *                so a plan's steps and token usage roll up into {@link GoapResult} the same
 *                way a graph's nodes do
 * @param failure why the action could not do its job, if it could not; never {@code null}
 */
public record ActionResult(Map<String, Object> facts, Optional<AgentResult> result,
                           Optional<String> failure) {

    public ActionResult {
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(failure, "failure");
        // Ordered, like WorldState's: these keys flow into the trace and into the
        // "declared but did not establish" message.
        facts = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(facts));
        if (failure.isPresent() && !facts.isEmpty()) {
            // Otherwise a failure could satisfy the precondition of a later action, and the
            // planner would route through a step that reported it had not worked.
            throw new IllegalArgumentException(
                    "A failed action must not establish facts, but " + facts.keySet()
                            + " came back with the failure '" + failure.get() + "'");
        }
    }

    /** A success establishing {@code facts}. */
    public static ActionResult ok(Map<String, Object> facts) {
        return new ActionResult(facts, Optional.empty(), Optional.empty());
    }

    /** A success establishing one fact. */
    public static ActionResult ok(String key, Object value) {
        return ok(Map.of(key, value));
    }

    /** A success establishing {@code facts}, carrying the agent run that produced them. */
    public static ActionResult ok(Map<String, Object> facts, AgentResult result) {
        return new ActionResult(facts, Optional.of(result), Optional.empty());
    }

    /** A failure with a reason worth reading in the trace. */
    public static ActionResult failed(String reason) {
        Objects.requireNonNull(reason, "reason");
        return new ActionResult(Map.of(), Optional.empty(), Optional.of(reason));
    }

    /** A failure carrying the agent run that failed. */
    public static ActionResult failed(String reason, AgentResult result) {
        Objects.requireNonNull(reason, "reason");
        return new ActionResult(Map.of(), Optional.of(result), Optional.of(reason));
    }

    public boolean isSuccess() {
        return failure.isEmpty();
    }
}
