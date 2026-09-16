package dev.agentkit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.llm.TokenUsage;
import org.junit.jupiter.api.Test;

class TokenBudgetTest {

    @Test
    void totalTokenCapTripsWhenReachedOrExceeded() {
        TokenBudget budget = TokenBudget.ofTotalTokens(100);
        assertThat(budget.isExhausted(new TokenUsage(40, 40))).isFalse(); // 80 < 100
        assertThat(budget.isExhausted(new TokenUsage(50, 50))).isTrue();  // 100 >= 100
        assertThat(budget.isExhausted(new TokenUsage(90, 90))).isTrue();  // 180 >= 100
    }

    @Test
    void separateInputAndOutputCapsAreCheckedIndependently() {
        TokenBudget budget = TokenBudget.builder()
                .maxInputTokens(100)
                .maxOutputTokens(10)
                .build();
        assertThat(budget.isExhausted(new TokenUsage(50, 5))).isFalse();
        // Output cap alone is enough to exhaust even though input has headroom.
        assertThat(budget.isExhausted(new TokenUsage(50, 10))).isTrue();
        assertThat(budget.breach(new TokenUsage(50, 10)).orElseThrow()).contains("output tokens");
    }

    @Test
    void costCapUsesPricingToConvertTokensToDollars() {
        TokenBudget budget = TokenBudget.ofCostUsd(1.00, ModelPricing.of(5.00, 25.00));
        // 100k in = $0.50, 20k out = $0.50 => $1.00 exactly, which meets the cap.
        assertThat(budget.isExhausted(new TokenUsage(100_000, 20_000))).isTrue();
        assertThat(budget.isExhausted(new TokenUsage(99_000, 19_000))).isFalse();
        assertThat(budget.breach(new TokenUsage(100_000, 20_000)).orElseThrow()).contains("cost");
    }

    @Test
    void breachReportsTheFirstCapHit() {
        TokenBudget budget = TokenBudget.ofTotalTokens(50);
        assertThat(budget.breach(TokenUsage.ZERO)).isEmpty();
        assertThat(budget.breach(new TokenUsage(30, 30)).orElseThrow()).contains("total tokens");
    }

    @Test
    void builderRejectsAnEmptyBudget() {
        // Validation lives in the record's canonical constructor, so building one
        // directly and building one via the builder cannot disagree.
        assertThatThrownBy(() -> TokenBudget.builder().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one positive cap");
        assertThatThrownBy(() -> new TokenBudget(0, 0, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one positive cap");
    }

    @Test
    void aCostCapRequiresPricing() {
        assertThatThrownBy(() -> TokenBudget.builder().maxCostUsd(1.00, null).build())
                .isInstanceOf(NullPointerException.class); // the builder null-checks eagerly
        assertThatThrownBy(() -> new TokenBudget(0, 0, 0, 1.00, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ModelPricing");
    }

    @Test
    void aNaNCostCapIsRejectedRatherThanSilentlyCappingNothing() {
        // NaN is neither positive nor "<= 0", so it would slip past every comparison
        // and leave a budget that never trips — unlike a non-positive cap, which is a
        // legitimate way to say "unset".
        assertThatThrownBy(() -> new TokenBudget(0, 0, 500, Double.NaN, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NaN");
    }

    @Test
    void nonPositiveCapsAreIgnored() {
        // A zero/negative cap is treated as "unset", so this budget is driven only
        // by the total-token cap.
        TokenBudget budget = TokenBudget.builder()
                .maxInputTokens(0)
                .maxOutputTokens(-5)
                .maxTotalTokens(100)
                .build();
        assertThat(budget.isExhausted(new TokenUsage(200, 0))).isTrue();
        assertThat(budget.breach(new TokenUsage(60, 60)).orElseThrow()).contains("total tokens");
    }

    @Test
    void modelPricingComputesCost() {
        ModelPricing pricing = ModelPricing.of(5.00, 25.00);
        assertThat(pricing.costOf(new TokenUsage(1_000_000, 0))).isEqualTo(5.00);
        assertThat(pricing.costOf(new TokenUsage(0, 1_000_000))).isEqualTo(25.00);
        assertThat(pricing.costOf(TokenUsage.ZERO)).isEqualTo(0.0);
    }

    @Test
    void modelPricingRejectsNegativePrices() {
        assertThatThrownBy(() -> ModelPricing.of(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelPricingRejectsNonFinitePrices() {
        // A NaN price makes costOf() return NaN, and every `cost >= cap` comparison
        // against NaN is false — so a cost-capped budget would never trip, defeating
        // the NaN guard on maxCostUsd itself. Infinity is refused alongside it.
        assertThatThrownBy(() -> ModelPricing.of(Double.NaN, 25.00))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finite");
        assertThatThrownBy(() -> ModelPricing.of(5.00, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelPricing.of(Double.POSITIVE_INFINITY, 25.00))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInfiniteCostCapIsRejectedAlongsideNaN() {
        // Infinity would never trip either, and has no portable JSON representation —
        // Jackson emits the bare token `Infinity`, which a non-Java client cannot read.
        assertThatThrownBy(() -> new TokenBudget(0, 0, 0, Double.POSITIVE_INFINITY,
                ModelPricing.of(5.00, 25.00)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finite");
    }
}
