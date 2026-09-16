package dev.agentkit.eval;

/**
 * Scores one aspect of an {@link EvalRun} — an outcome, a tool-use expectation, a
 * budget, or an LLM-as-judge rubric. See {@link Checks} for the built-ins.
 *
 * <p>A check should not throw; the {@link EvalHarness} nonetheless treats a thrown
 * exception as a failed outcome so one flaky check (e.g. a judge whose model call
 * fails) never aborts the whole run.
 */
@FunctionalInterface
public interface Check {

    /** Evaluates {@code run} and returns the outcome. */
    CheckOutcome check(EvalRun run);
}
