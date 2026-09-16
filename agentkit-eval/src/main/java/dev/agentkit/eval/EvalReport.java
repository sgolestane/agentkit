package dev.agentkit.eval;

import java.util.List;
import java.util.Objects;

/**
 * The aggregate result of running a dataset of {@link EvalCase}s: one
 * {@link CaseReport} per case, plus pass-rate accessors and a printable summary.
 *
 * @param caseReports one report per case, in dataset order; never {@code null}
 */
public record EvalReport(List<CaseReport> caseReports) {

    public EvalReport {
        Objects.requireNonNull(caseReports, "caseReports");
        caseReports = List.copyOf(caseReports);
    }

    public int total() {
        return caseReports.size();
    }

    public long passedCount() {
        return caseReports.stream().filter(CaseReport::passed).count();
    }

    /**
     * Fraction of cases that passed, in {@code [0, 1]}. An empty dataset is
     * {@code 1.0} (vacuously); guard with {@link #total()} in CI so an empty dataset
     * (a glob that matched nothing) is not mistaken for a green run.
     */
    public double passRate() {
        return total() == 0 ? 1.0 : (double) passedCount() / total();
    }

    public List<CaseReport> failures() {
        return caseReports.stream().filter(report -> !report.passed()).toList();
    }

    /** A one-line-per-case, human-readable summary ending with the pass rate. */
    public String summary() {
        if (caseReports.isEmpty()) {
            return "no cases run";
        }
        StringBuilder sb = new StringBuilder();
        for (CaseReport report : caseReports) {
            sb.append(report.passed() ? "PASS " : "FAIL ").append(report.caseId());
            if (!report.passed()) {
                for (CheckOutcome failure : report.failures()) {
                    sb.append("\n    - ").append(failure.name()).append(": ").append(failure.detail());
                }
            }
            sb.append("\n");
        }
        sb.append(String.format("%d/%d passed (%.0f%%)", passedCount(), total(), passRate() * 100));
        return sb.toString();
    }
}
