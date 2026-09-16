package dev.agentkit.eval;

import java.util.Objects;

/**
 * The result of applying one {@link Check} to an {@link EvalRun}.
 *
 * @param name   a short label identifying the check; never {@code null}
 * @param passed whether the check passed
 * @param detail when failed, why; empty when passed. Never {@code null}
 */
public record CheckOutcome(String name, boolean passed, String detail) {

    public CheckOutcome {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(detail, "detail");
    }

    public static CheckOutcome pass(String name) {
        return new CheckOutcome(name, true, "");
    }

    public static CheckOutcome fail(String name, String detail) {
        return new CheckOutcome(name, false, Objects.requireNonNull(detail, "detail"));
    }
}
