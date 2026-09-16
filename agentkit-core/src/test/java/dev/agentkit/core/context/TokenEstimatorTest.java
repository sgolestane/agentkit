package dev.agentkit.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.message.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    private final TokenEstimator estimator = TokenEstimator.HEURISTIC;

    @Test
    void estimatesRoughlyFourCharsPerToken() {
        assertThat(estimator.estimate("")).isZero();
        assertThat(estimator.estimate("abcd")).isEqualTo(1);
        assertThat(estimator.estimate("abcde")).isEqualTo(2);
    }

    @Test
    void messageEstimateIncludesOverhead() {
        assertThat(estimator.estimate(Message.user("abcd"))).isGreaterThan(estimator.estimate("abcd"));
    }

    @Test
    void listEstimateSumsMessages() {
        List<Message> messages = List.of(Message.user("aaaa"), Message.assistant("bbbb"));
        assertThat(estimator.estimate(messages))
                .isEqualTo(estimator.estimate(messages.get(0)) + estimator.estimate(messages.get(1)));
    }
}
