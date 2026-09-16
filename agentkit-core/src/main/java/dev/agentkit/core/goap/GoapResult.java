package dev.agentkit.core.goap;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.reliability.PendingApproval;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of a {@link GoapRunner} run: what it ended up knowing, and how it got there.
 *
 * @param stop    why the run ended; never {@code null}
 * @param state   everything known when it stopped, including facts established by a run
 *                that then failed — a partial result is usually the most useful thing to
 *                look at; never {@code null}
 * @param trace   every action that ran, in order — at most one entry each; never
 *                {@code null}
 * @param steps   the total loop steps across every agent action that ran
 * @param usage   the total token usage across every agent action that ran; never {@code null}
 * @param message what went wrong, when something did — including, on
 *                {@link GoapStop#AWAITING_APPROVAL}, which action asked; never {@code null}
 * @param error   the exception, when an action threw; never {@code null}
 */
public record GoapResult(GoapStop stop, WorldState state, List<ActionOutcome> trace, int steps,
                         TokenUsage usage, Optional<String> message, Optional<Throwable> error) {

    public GoapResult {
        Objects.requireNonNull(stop, "stop");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(error, "error");
        trace = List.copyOf(Objects.requireNonNull(trace, "trace"));
        if (steps < 0) {
            throw new IllegalArgumentException("steps must be >= 0, was " + steps);
        }
    }

    static GoapResult of(GoapStop stop, WorldState state, List<ActionOutcome> trace,
                         String message, Throwable error) {
        int steps = trace.stream().map(ActionOutcome::result).flatMap(Optional::stream)
                .mapToInt(AgentResult::steps).sum();
        TokenUsage usage = trace.stream().map(ActionOutcome::result).flatMap(Optional::stream)
                .map(AgentResult::usage).reduce(TokenUsage.ZERO, TokenUsage::plus);
        return new GoapResult(stop, state, trace, steps, usage,
                Optional.ofNullable(message), Optional.ofNullable(error));
    }

    public boolean isSuccess() {
        return stop == GoapStop.OBJECTIVE_MET;
    }

    /**
     * The tool calls a gate stopped pending somebody's decision, across every action that
     * ran — empty unless {@link #stop()} is {@link GoapStop#AWAITING_APPROVAL}.
     *
     * <p>Derived from the trace rather than stored, for the reason {@link #steps} and
     * {@link #usage} are: an action's {@code AgentResult} is already on the trace, so a
     * component would be a second copy that a future {@code of(...)} could forget to fill.
     *
     * <p>Not the same list as "every park that ever happened in this run", because the
     * runner stops at the first one. That is deliberate and matches {@code ToolGates.allOf}:
     * a composite asks one question at a time, since the answer to the first may change what
     * the second decides.
     */
    public List<PendingApproval> awaiting() {
        return trace.stream().map(ActionOutcome::result).flatMap(Optional::stream)
                .filter(AgentResult::isAwaitingApproval)
                .flatMap(result -> result.awaiting().stream())
                .toList();
    }

    /** The value of a fact, if the run established it. */
    public Optional<Object> fact(String key) {
        return state.get(key);
    }

    /** The value of a fact as text — what an agent action's output comes back as. */
    public Optional<String> output(String key) {
        return state.text(key);
    }

    /** The action names in the order they ran, including any that failed and were routed around. */
    public List<String> path() {
        return trace.stream().map(ActionOutcome::action).toList();
    }
}
