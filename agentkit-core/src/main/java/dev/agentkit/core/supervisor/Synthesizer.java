package dev.agentkit.core.supervisor;

import dev.agentkit.core.agent.Goal;
import java.util.List;

/**
 * Combines the outcomes of fanned-out subagents into a single final answer for
 * the original goal — the "collect" half of decompose/delegate/collect.
 *
 * <p>A synthesizer can be a deterministic join (see {@link Synthesizers}) or an
 * independent model call that reconciles the pieces. It receives the original
 * goal for context and every outcome, including failed ones, so it can decide
 * how to present partial results.
 */
@FunctionalInterface
public interface Synthesizer {

    /**
     * Produces the supervisor's final output from the subagent {@code outcomes}
     * (in the same order the tasks were submitted).
     */
    String synthesize(Goal original, List<SubagentOutcome> outcomes);
}
