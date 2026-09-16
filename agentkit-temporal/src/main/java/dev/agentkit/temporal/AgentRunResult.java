package dev.agentkit.temporal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.reliability.PendingApproval;
import java.util.List;
import java.util.Objects;

/**
 * The serializable outcome of a durable agent run, returned by the workflow.
 *
 * <p>Mirrors the in-process {@code AgentResult} but replaces its
 * {@code Optional<Throwable>} with a plain {@code errorMessage} string so the
 * result serializes cleanly across the workflow boundary (a {@code Throwable} is
 * not portably serializable, and durable failures surface through Temporal's own
 * failure model rather than an embedded exception).
 *
 * @param stopReason   why the run ended
 * @param output       the final textual output; never {@code null} (may be empty)
 * @param steps        the number of model turns taken
 * @param usage        cumulative token usage; never {@code null}
 * @param errorMessage a failure message when {@code stopReason == ERROR};
 *                     otherwise empty
 * @param awaiting     the tool calls nobody decided, when
 *                     {@code stopReason == AWAITING_APPROVAL}; otherwise empty. Never
 *                     {@code null}
 */
public record AgentRunResult(StopReason stopReason, String output, int steps, TokenUsage usage,
                             String errorMessage, List<PendingApproval> awaiting) {

    public AgentRunResult {
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(errorMessage, "errorMessage");
        // Coerced rather than required: this is the workflow's return value, deserialized
        // by a client that may predate the component. The same rule DurableJson states.
        awaiting = awaiting == null ? List.of() : List.copyOf(awaiting);
    }

    /**
     * The five-value shape, for callers written before a run could stop on a question.
     *
     * <p>Not a legacy form — it is what every non-parked stop still builds. It exists so
     * the four factories below, and every caller of them, say nothing about approval.
     */
    public AgentRunResult(StopReason stopReason, String output, int steps, TokenUsage usage,
                          String errorMessage) {
        this(stopReason, output, steps, usage, errorMessage, List.of());
    }

    /**
     * A durable run that ended because nobody decided a parked call in time.
     *
     * <p>Carries what expired. Without it the operator whose run stopped at the deadline
     * has an {@code AWAITING_APPROVAL} with an empty message and an empty query — the
     * workflow has completed, so {@code pendingApprovals()} is empty too — and has to read
     * Temporal history to find out which call it was. The in-process {@code AgentResult}
     * enforces that this list is non-empty for this stop reason; this one cannot, because a
     * client on older code deserializes it absent, but it is populated on every path that
     * builds it here.
     */
    static AgentRunResult awaitingApproval(String output, int steps, TokenUsage usage,
                                           List<PendingApproval> awaiting) {
        return new AgentRunResult(StopReason.AWAITING_APPROVAL, output, steps, usage, "",
                awaiting);
    }

    /** Derived, not a serialized field — ignored so the record round-trips cleanly. */
    @JsonIgnore
    public boolean isSuccess() {
        return stopReason == StopReason.COMPLETED;
    }

    static AgentRunResult of(StopReason reason, String output, int steps, TokenUsage usage) {
        return new AgentRunResult(reason, output, steps, usage, "");
    }

    /**
     * Adapts an in-process {@link AgentResult} to its serializable form — useful
     * for callers that run an agent in-process but want to persist or return the
     * durable result shape. {@code Optional.map} already collapses a null
     * {@code getMessage()} to {@code ""}, so no extra null guard is needed.
     */
    public static AgentRunResult from(AgentResult result) {
        String message = result.error().map(Throwable::getMessage).orElse("");
        // awaiting travels too. It was dropped at first, which made the in-process type's
        // "non-empty if and only if AWAITING_APPROVAL" invariant unrepresentable one module
        // over: an in-process run that stopped on a question adapted into a durable result
        // that could not say what the question was.
        return new AgentRunResult(result.stopReason(), result.output(), result.steps(),
                result.usage(), message, result.awaiting());
    }
}
