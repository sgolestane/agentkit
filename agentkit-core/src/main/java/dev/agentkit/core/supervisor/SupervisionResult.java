package dev.agentkit.core.supervisor;

import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.reliability.PendingApproval;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of a supervised run: the synthesized final output plus the
 * per-subagent outcomes and aggregated cost.
 *
 * @param output      the synthesized answer to the original goal; never
 *                    {@code null}
 * @param outcomes    each subagent's outcome, in task-submission order;
 *                    unmodifiable
 * @param totalSteps  total reasoning/tool steps across all subagents (the
 *                    synthesis call is not counted)
 * @param totalUsage  cumulative token usage across all subagents (the synthesis
 *                    call is not counted); never {@code null}
 */
public record SupervisionResult(String output, List<SubagentOutcome> outcomes,
                                int totalSteps, TokenUsage totalUsage) {

    public SupervisionResult {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(outcomes, "outcomes");
        Objects.requireNonNull(totalUsage, "totalUsage");
        outcomes = List.copyOf(outcomes);
        if (totalSteps < 0) {
            throw new IllegalArgumentException("totalSteps must be >= 0, was " + totalSteps);
        }
    }

    /** Whether every delegated subagent completed successfully. */
    public boolean allSucceeded() {
        return outcomes.stream().allMatch(SubagentOutcome::succeeded);
    }

    /** Outcomes whose delegated run did not complete successfully. */
    public List<SubagentOutcome> failures() {
        return outcomes.stream().filter(o -> !o.succeeded()).toList();
    }

    /**
     * The tool calls a gate stopped pending somebody's decision, across every subagent that
     * ran — empty when nothing parked.
     *
     * <p><strong>Reported, not enforced, and that is the difference from the other two
     * orchestrators.</strong> A {@code GoapRunner} stops on a park because its next move is
     * to plan a route around the question; an {@code AgentGraph} stops scheduling because
     * its next move is to take the fallback edge. A fan-out has no next move: the subgoals
     * were decomposed before anything ran, they are independent by construction, and none of
     * them is an alternative route to another's effect. Cancelling the siblings of a parked
     * subagent would throw away work to prevent nothing.
     *
     * <p>So what was missing here was never a stop, it was a <em>name</em>. Measured before
     * this (#159): a parked subagent reached the caller as {@code allSucceeded() == false}
     * with one entry in {@link #failures()}, exactly like a subagent that ran out of steps.
     * The {@code PendingApproval} was reachable — three hops down, through
     * {@code outcomes().get(i).result().awaiting()} — by a caller who already knew to look.
     *
     * <p>The synthesized {@link #output()} is a separate matter and still says only the stop
     * reason, because a {@code Synthesizer} is handed the outcomes and decides for itself.
     */
    public List<PendingApproval> awaiting() {
        return outcomes.stream().filter(SubagentOutcome::awaitsAPerson)
                .flatMap(outcome -> outcome.result().awaiting().stream())
                .toList();
    }
}
