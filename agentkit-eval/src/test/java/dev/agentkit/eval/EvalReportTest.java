package dev.agentkit.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.TokenUsage;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvalReportTest {

    private static CaseReport caseReport(String id, boolean pass) {
        EvalRun run = new EvalRun(Goal.of("g"),
                AgentResult.completed("out", 1, TokenUsage.ZERO), List.of());
        CheckOutcome outcome = pass
                ? CheckOutcome.pass("c")
                : CheckOutcome.fail("c", "nope");
        return new CaseReport(id, run, List.of(outcome));
    }

    @Test
    void aggregatesPassRateAndFailures() {
        EvalReport report = new EvalReport(List.of(
                caseReport("a", true), caseReport("b", false), caseReport("c", true), caseReport("d", true)));

        assertThat(report.total()).isEqualTo(4);
        assertThat(report.passedCount()).isEqualTo(3);
        assertThat(report.passRate()).isEqualTo(0.75);
        assertThat(report.failures()).extracting(CaseReport::caseId).containsExactly("b");
        assertThat(report.summary()).contains("FAIL b").contains("nope").contains("3/4 passed (75%)");
    }

    @Test
    void anEmptyDatasetIsVacuouslyOneButSummarisedAsNoCases() {
        EvalReport report = new EvalReport(List.of());
        assertThat(report.total()).isZero();
        assertThat(report.passRate()).isEqualTo(1.0);
        assertThat(report.summary()).isEqualTo("no cases run");
    }
}
