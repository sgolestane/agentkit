package dev.agentkit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.StreamHandler;
import dev.agentkit.core.message.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;

class RetryingLlmClientTest {

    private static final LlmRequest REQUEST = LlmRequest.builder("m").addMessage(Message.user("hi")).build();

    /** Fails the first {@code failures} calls with LlmException, then succeeds. */
    private static LlmClient flaky(int failures, AtomicInteger calls) {
        return request -> {
            if (calls.getAndIncrement() < failures) {
                throw new LlmException("transient");
            }
            return FakeLlmClient.text("ok");
        };
    }

    @Test
    void succeedsAfterTransientFailures() {
        AtomicInteger calls = new AtomicInteger();
        List<Long> delays = new ArrayList<>();
        RetryingLlmClient client = new RetryingLlmClient(
                flaky(2, calls), new RetryPolicy(3, 100, 1000, 2.0), delays::add);

        assertThat(client.generate(REQUEST).message().text()).isEqualTo("ok");
        assertThat(calls).hasValue(3); // 2 failures + 1 success
        assertThat(delays).containsExactly(100L, 200L); // exponential backoff before each retry
    }

    @Test
    void throwsAfterExhaustingRetries() {
        AtomicInteger calls = new AtomicInteger();
        LongConsumer noSleep = d -> {
        };
        RetryingLlmClient client = new RetryingLlmClient(
                flaky(99, calls), new RetryPolicy(2, 1, 10, 2.0), noSleep);

        assertThatThrownBy(() -> client.generate(REQUEST))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("3 attempt");
        assertThat(calls).hasValue(3); // initial + 2 retries
    }

    @Test
    void streamingPathIsRetriedAndForwardsDeltasOnSuccess() {
        AtomicInteger calls = new AtomicInteger();
        // Fails the first streaming attempt, then succeeds and emits two deltas.
        LlmClient flakyStream = new LlmClient() {
            @Override
            public LlmResponse generate(LlmRequest request) {
                return FakeLlmClient.text("ok");
            }

            @Override
            public LlmResponse generate(LlmRequest request, StreamHandler handler) {
                if (calls.getAndIncrement() < 1) {
                    throw new LlmException("transient");
                }
                handler.onTextDelta("o");
                handler.onTextDelta("k");
                return generate(request);
            }
        };
        RetryingLlmClient client = new RetryingLlmClient(
                flakyStream, new RetryPolicy(3, 1, 10, 2.0), d -> { });
        List<String> deltas = new ArrayList<>();

        LlmResponse response = client.generate(REQUEST, deltas::add);

        assertThat(response.message().text()).isEqualTo("ok");
        assertThat(deltas).containsExactly("o", "k"); // deltas from the successful attempt
        assertThat(calls).hasValue(2); // 1 failure + 1 success on the streaming overload
    }

    @Test
    void noRetryPolicyCallsOnce() {
        AtomicInteger calls = new AtomicInteger();
        RetryingLlmClient client = new RetryingLlmClient(flaky(99, calls), RetryPolicy.none(), d -> {
        });
        assertThatThrownBy(() -> client.generate(REQUEST)).isInstanceOf(LlmException.class);
        assertThat(calls).hasValue(1);
    }
}
