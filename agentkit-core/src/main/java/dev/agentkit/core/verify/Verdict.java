package dev.agentkit.core.verify;

import java.util.Objects;

/**
 * The result of verifying an agent's output against its goal.
 *
 * @param passed   whether the output satisfies the goal
 * @param feedback when failed, actionable guidance for a retry; may be empty
 */
public record Verdict(boolean passed, String feedback) {

    public Verdict {
        Objects.requireNonNull(feedback, "feedback");
    }

    public static Verdict pass() {
        return new Verdict(true, "");
    }

    public static Verdict fail(String feedback) {
        return new Verdict(false, Objects.requireNonNull(feedback, "feedback"));
    }
}
