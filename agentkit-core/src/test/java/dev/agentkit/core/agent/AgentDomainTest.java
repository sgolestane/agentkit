package dev.agentkit.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentDomainTest {

    @Test
    void goalRejectsBlankDescription() {
        assertThatThrownBy(() -> Goal.of("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void goalParametersAreImmutable() {
        Goal g = new Goal("do the thing", Map.of("id", 42));
        assertThatThrownBy(() -> g.parameters().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void completedResultIsSuccess() {
        AgentResult r = AgentResult.completed("answer", 3);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(r.error()).isEmpty();
    }

    @Test
    void failedResultCarriesError() {
        var boom = new IllegalStateException("boom");
        AgentResult r = AgentResult.failed(boom, 1);
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(r.error()).containsSame(boom);
    }

    @Test
    void negativeStepsRejected() {
        assertThatThrownBy(() -> AgentResult.completed("x", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stoppedRejectsErrorReason() {
        assertThatThrownBy(() -> AgentResult.stopped(StopReason.ERROR, "", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorReasonRequiresErrorPresent() {
        assertThatThrownBy(() -> new AgentResult(
                StopReason.ERROR, "", 1, dev.agentkit.core.llm.TokenUsage.ZERO, java.util.Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonErrorReasonRejectsErrorPresent() {
        assertThatThrownBy(() -> new AgentResult(StopReason.MAX_STEPS, "", 1,
                dev.agentkit.core.llm.TokenUsage.ZERO, java.util.Optional.of(new RuntimeException())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stoppedBuildsNonErrorResult() {
        AgentResult r = AgentResult.stopped(StopReason.MAX_STEPS, "partial", 5);
        assertThat(r.stopReason()).isEqualTo(StopReason.MAX_STEPS);
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.error()).isEmpty();
    }
}
