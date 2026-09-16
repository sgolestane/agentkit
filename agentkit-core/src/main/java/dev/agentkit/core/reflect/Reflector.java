package dev.agentkit.core.reflect;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;

/**
 * Turns a failed attempt into a concise, reusable <em>lesson</em> — the "reflect"
 * half of the Reflexion pattern. A {@link ReflectiveAgent} stores what a reflector
 * produces and replays it into later attempts (and later runs) so the agent stops
 * repeating the same mistakes.
 *
 * <p>{@link LlmReflector} is the model-backed implementation; this seam keeps the
 * agent testable and lets a caller substitute a rule-based reflector.
 */
@FunctionalInterface
public interface Reflector {

    /**
     * Distills a lesson from a failed attempt.
     *
     * @param goal     the original goal
     * @param result   the attempt's result
     * @param feedback why it fell short (verifier feedback, or the stop reason)
     * @return a short lesson to remember; an empty string to record nothing
     */
    String reflect(Goal goal, AgentResult result, String feedback);
}
