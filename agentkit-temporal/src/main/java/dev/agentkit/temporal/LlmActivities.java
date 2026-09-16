package dev.agentkit.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The model-inference boundary as a Temporal activity. Each model turn in the
 * durable loop is one activity invocation, so a failed or slow inference is
 * retried by Temporal and a completed one is memoized in workflow history (never
 * re-run on replay).
 */
@ActivityInterface
public interface LlmActivities {

    /** Runs one model turn for {@code spec} and returns the assistant's reply. */
    @ActivityMethod
    LlmTurn generate(LlmCallSpec spec);
}
