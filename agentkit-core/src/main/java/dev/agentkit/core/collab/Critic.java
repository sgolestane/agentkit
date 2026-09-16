package dev.agentkit.core.collab;

import dev.agentkit.core.agent.Goal;

/**
 * Reviews a draft against a goal and returns a {@link Critique}. Implementations
 * range from a single model call ({@link Critics#llm}) to a full peer agent
 * ({@link Critics#agent}), so the critique loop can be as light or as capable as
 * the task warrants.
 */
@FunctionalInterface
public interface Critic {

    /** Judges {@code draft} against {@code goal}. */
    Critique review(Goal goal, String draft);
}
