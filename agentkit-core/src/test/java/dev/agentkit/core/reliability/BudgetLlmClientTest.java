package dev.agentkit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.StreamHandler;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BudgetLlmClientTest {

    private static final LlmRequest REQUEST =
            LlmRequest.builder("m").addMessage(Message.user("hi")).build();

    /** Returns a response carrying {@code usage} on every call, counting calls. */
    private static LlmClient usageEach(TokenUsage usage, AtomicInteger calls) {
        return request -> {
            calls.incrementAndGet();
            return FakeLlmClient.textWithUsage("ok", usage);
        };
    }

    @Test
    void passesThroughAndAccumulatesUsageWhileUnderBudget() {
        AtomicInteger calls = new AtomicInteger();
        BudgetLlmClient client = new BudgetLlmClient(
                usageEach(new TokenUsage(100, 50), calls), TokenBudget.ofTotalTokens(1000));

        assertThat(client.generate(REQUEST).message().text()).isEqualTo("ok");
        assertThat(client.generate(REQUEST).message().text()).isEqualTo("ok");
        assertThat(calls).hasValue(2);
        assertThat(client.spent()).isEqualTo(new TokenUsage(200, 100));
    }

    @Test
    void refusesTheCallAfterTheBudgetIsReached() {
        AtomicInteger calls = new AtomicInteger();
        // Cap of 150 total; each call spends 100. First call is allowed (spend 0 < 150),
        // pushing the total to 100. Second is allowed (100 < 150), pushing it to 200.
        // Third is refused because 200 >= 150.
        BudgetLlmClient client = new BudgetLlmClient(
                usageEach(new TokenUsage(60, 40), calls), TokenBudget.ofTotalTokens(150));

        client.generate(REQUEST);
        client.generate(REQUEST);
        assertThatThrownBy(() -> client.generate(REQUEST))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("200 total tokens >= cap 150");
        assertThat(calls).hasValue(2); // the refused call never reached the delegate
    }

    @Test
    void budgetExceededExceptionCarriesTheSpend() {
        BudgetLlmClient client = new BudgetLlmClient(
                usageEach(new TokenUsage(100, 100), new AtomicInteger()), TokenBudget.ofTotalTokens(100));

        client.generate(REQUEST); // spend now 200 >= 100
        try {
            client.generate(REQUEST);
            org.junit.jupiter.api.Assertions.fail("expected BudgetExceededException");
        } catch (BudgetExceededException e) {
            assertThat(e.spent()).isEqualTo(new TokenUsage(100, 100));
        }
    }

    @Test
    void refusesImmediatelyWhenTheFirstCallWouldExceedNothing() {
        // A tiny cap is only tripped after spend is recorded, so the very first call
        // (spend == 0) always runs; the guard is a pre-flight check on prior spend.
        AtomicInteger calls = new AtomicInteger();
        BudgetLlmClient client = new BudgetLlmClient(
                usageEach(new TokenUsage(1, 0), calls), TokenBudget.ofTotalTokens(1));

        assertThat(client.generate(REQUEST).message().text()).isEqualTo("ok"); // allowed
        assertThatThrownBy(() -> client.generate(REQUEST))
                .isInstanceOf(BudgetExceededException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void aBudgetStopIsNotRetriedByRetryingLlmClient() {
        // BudgetExceededException is not an LlmException, so a retry decorator wrapping
        // the budget client must let it propagate on the first throw, not retry it.
        AtomicInteger calls = new AtomicInteger();
        BudgetLlmClient budgeted = new BudgetLlmClient(
                usageEach(new TokenUsage(100, 0), calls), TokenBudget.ofTotalTokens(50));
        RetryingLlmClient retrying = new RetryingLlmClient(
                budgeted, new RetryPolicy(5, 1, 10, 2.0), d -> { });

        retrying.generate(REQUEST); // spend now 100 >= 50
        assertThatThrownBy(() -> retrying.generate(REQUEST))
                .isInstanceOf(BudgetExceededException.class);
        assertThat(calls).hasValue(1); // the refused call was not retried
    }

    @Test
    void resetLetsAnInstanceTrackAFreshRun() {
        AtomicInteger calls = new AtomicInteger();
        BudgetLlmClient client = new BudgetLlmClient(
                usageEach(new TokenUsage(100, 0), calls), TokenBudget.ofTotalTokens(50));

        client.generate(REQUEST); // spend 100 >= 50
        assertThatThrownBy(() -> client.generate(REQUEST)).isInstanceOf(BudgetExceededException.class);

        client.reset();
        assertThat(client.spent()).isEqualTo(TokenUsage.ZERO);
        assertThat(client.generate(REQUEST).message().text()).isEqualTo("ok"); // budget available again
        assertThat(calls).hasValue(2);
    }

    /** A delegate that streams the given fragments and returns their concatenation with usage. */
    private static LlmClient streamingDelegate(TokenUsage usage, String... fragments) {
        return new LlmClient() {
            @Override
            public LlmResponse generate(LlmRequest request) {
                return FakeLlmClient.textWithUsage(String.join("", fragments), usage);
            }

            @Override
            public LlmResponse generate(LlmRequest request, StreamHandler handler) {
                for (String f : fragments) {
                    handler.onTextDelta(f);
                }
                return generate(request);
            }
        };
    }

    @Test
    void streamingPathForwardsRealDeltasAndStillTalliesUsage() {
        BudgetLlmClient client = new BudgetLlmClient(
                streamingDelegate(new TokenUsage(10, 5), "He", "llo"), TokenBudget.ofTotalTokens(1000));
        List<String> deltas = new ArrayList<>();

        LlmResponse response = client.generate(REQUEST, deltas::add);

        assertThat(deltas).containsExactly("He", "llo"); // real deltas survived the decorator
        assertThat(response.message().text()).isEqualTo("Hello");
        assertThat(client.spent()).isEqualTo(new TokenUsage(10, 5));
    }

    @Test
    void streamingPathIsAlsoRefusedWhenTheBudgetIsExhausted() {
        AtomicInteger delegateCalls = new AtomicInteger();
        LlmClient delegate = new LlmClient() {
            @Override
            public LlmResponse generate(LlmRequest request) {
                delegateCalls.incrementAndGet();
                return FakeLlmClient.textWithUsage("ok", new TokenUsage(100, 0));
            }

            @Override
            public LlmResponse generate(LlmRequest request, StreamHandler handler) {
                return generate(request);
            }
        };
        BudgetLlmClient client = new BudgetLlmClient(delegate, TokenBudget.ofTotalTokens(50));

        client.generate(REQUEST, d -> { }); // spend 100 >= 50
        assertThatThrownBy(() -> client.generate(REQUEST, d -> { }))
                .isInstanceOf(BudgetExceededException.class);
        assertThat(delegateCalls).hasValue(1); // the refused streaming call never reached the delegate
    }

    @Test
    void costBudgetStopsWhenEstimatedSpendReachesTheCap() {
        // $5/M in, $25/M out. 100k in + 20k out = 100k/1M*5 + 20k/1M*25 = 0.5 + 0.5 = $1.00.
        // With an $0.80 cap, the first call runs (spend $0), pushing the total to $1.00;
        // the second is refused because $1.00 >= $0.80.
        ModelPricing pricing = ModelPricing.of(5.00, 25.00);
        AtomicInteger calls = new AtomicInteger();
        BudgetLlmClient client = new BudgetLlmClient(
                usageEach(new TokenUsage(100_000, 20_000), calls), TokenBudget.ofCostUsd(0.80, pricing));

        client.generate(REQUEST); // $1.00 spent, now over the $0.80 cap
        assertThatThrownBy(() -> client.generate(REQUEST))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("cost");
        assertThat(calls).hasValue(1);
    }
}
