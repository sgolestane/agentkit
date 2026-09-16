package dev.agentkit.eval;

import java.util.List;
import java.util.Objects;

/**
 * The result of one {@link EvalCase}: the run and every {@link CheckOutcome}. The
 * case passes only if all checks passed.
 *
 * @param caseId   the case's id; never {@code null}
 * @param run      what was observed; never {@code null}
 * @param outcomes one outcome per check, in order; never {@code null}
 */
public record CaseReport(String caseId, EvalRun run, List<CheckOutcome> outcomes) {

    public CaseReport {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(outcomes, "outcomes");
        outcomes = List.copyOf(outcomes);
    }

    /** Whether every check passed. */
    public boolean passed() {
        return outcomes.stream().allMatch(CheckOutcome::passed);
    }

    /** The failing outcomes, if any. */
    public List<CheckOutcome> failures() {
        return outcomes.stream().filter(o -> !o.passed()).toList();
    }
}
