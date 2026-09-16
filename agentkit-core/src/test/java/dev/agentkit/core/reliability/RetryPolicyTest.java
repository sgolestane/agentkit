package dev.agentkit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void delayGrowsExponentiallyAndCaps() {
        RetryPolicy policy = new RetryPolicy(5, 100, 500, 2.0);
        assertThat(policy.delayForAttempt(1)).isEqualTo(100);
        assertThat(policy.delayForAttempt(2)).isEqualTo(200);
        assertThat(policy.delayForAttempt(3)).isEqualTo(400);
        assertThat(policy.delayForAttempt(4)).isEqualTo(500); // capped
    }

    @Test
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> new RetryPolicy(-1, 0, 0, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(1, 0, 0, 0.5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noneHasNoRetries() {
        assertThat(RetryPolicy.none().maxRetries()).isZero();
    }
}
