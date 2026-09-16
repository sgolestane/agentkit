package dev.agentkit.core.reliability;

import dev.agentkit.core.llm.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A cap on how many tokens (or how much money) an agent run may spend, enforced by
 * {@link BudgetLlmClient}.
 *
 * <p>A budget may combine several caps — input tokens, output tokens, total tokens,
 * and estimated USD cost. It is <em>exhausted</em> as soon as the cumulative spend
 * meets or exceeds any one of them. Because the caller cannot know a turn's cost
 * before making it, the check is applied to the spend accumulated <em>so far</em>:
 * the turn that first reaches the cap still completes, and the next turn is the one
 * that is refused. See {@link BudgetLlmClient} for the loop-level effect.
 *
 * <p>Build one with {@link #builder()} or a convenience factory:
 * <pre>{@code
 * TokenBudget.ofTotalTokens(1_000_000);
 * TokenBudget.ofCostUsd(5.00, ModelPricing.of(5.00, 25.00));
 * TokenBudget.builder()
 *         .maxTotalTokens(1_000_000)
 *         .maxCostUsd(5.00, ModelPricing.of(5.00, 25.00))
 *         .build();
 * }</pre>
 *
 * <p>A plain record with a builder (the shape {@code AgentConfig} uses), so it carries
 * no serialization annotations yet still crosses the Temporal workflow boundary
 * directly — a durable run declares its cap as a {@code TokenBudget} rather than a
 * mirrored copy that could drift from this one.
 *
 * <p><strong>These component names are a persisted wire format.</strong> A budgeted
 * Temporal run stores this record in its workflow input and re-deserializes it on every
 * replay, so renaming a component — an ordinary-looking refactor, since this module has
 * no Temporal dependency — silently drops the cap from runs already in flight, or stalls
 * them outright if nothing is left to satisfy the "at least one cap" rule. A golden-JSON
 * test in the temporal module ({@code DurableJsonTest}) pins the shape; keep it passing
 * rather than updating it. Adding a component is likewise both a source-compatibility
 * break on the canonical constructor and a wire change, so treat this record's shape as
 * public API in both senses.
 *
 * @param maxInputTokens  cap on cumulative input tokens; unset if {@code <= 0}
 * @param maxOutputTokens cap on cumulative output tokens; unset if {@code <= 0}
 * @param maxTotalTokens  cap on cumulative total tokens; unset if {@code <= 0}
 * @param maxCostUsd      cap on cumulative estimated USD cost; unset if {@code <= 0}
 * @param pricing         pricing for the cost cap; required when {@code maxCostUsd > 0},
 *                        otherwise ignored and may be {@code null}
 */
public record TokenBudget(long maxInputTokens, long maxOutputTokens, long maxTotalTokens,
                          double maxCostUsd, ModelPricing pricing) {

    /**
     * @throws IllegalArgumentException if no cap is positive, a cost cap has no pricing,
     *     or {@code maxCostUsd} is not finite. This also runs on deserialization, which
     *     is how a malformed persisted budget is caught.
     */
    public TokenBudget {
        // A non-finite cap would slip past every check — NaN compares false against
        // everything, so the cap would never trip; Infinity has no portable JSON form.
        // That can only be a bug, so unlike a non-positive cap (which means "unset") it
        // is rejected. ModelPricing refuses non-finite prices for the same reason: a NaN
        // price makes every cost comparison false and would defeat this guard.
        if (!Double.isFinite(maxCostUsd)) {
            throw new IllegalArgumentException("maxCostUsd must be finite, was " + maxCostUsd);
        }
        if (maxCostUsd > 0 && pricing == null) {
            throw new IllegalArgumentException("a cost cap requires ModelPricing");
        }
        if (maxInputTokens <= 0 && maxOutputTokens <= 0 && maxTotalTokens <= 0 && maxCostUsd <= 0) {
            throw new IllegalArgumentException("a TokenBudget needs at least one positive cap");
        }
    }

    /** A budget capped at {@code max} total (input + output) tokens. */
    public static TokenBudget ofTotalTokens(long max) {
        return builder().maxTotalTokens(max).build();
    }

    /** A budget capped at {@code maxUsd} of estimated cost at {@code pricing}. */
    public static TokenBudget ofCostUsd(double maxUsd, ModelPricing pricing) {
        return builder().maxCostUsd(maxUsd, pricing).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Whether {@code spent} has met or exceeded any configured cap. */
    public boolean isExhausted(TokenUsage spent) {
        return breach(spent).isPresent();
    }

    /**
     * A human description of the first cap {@code spent} has met or exceeded, or
     * empty if the budget still has headroom. Used for the stop-reason message.
     */
    Optional<String> breach(TokenUsage spent) {
        if (maxTotalTokens > 0 && spent.totalTokens() >= maxTotalTokens) {
            return Optional.of(spent.totalTokens() + " total tokens >= cap " + maxTotalTokens);
        }
        if (maxInputTokens > 0 && spent.inputTokens() >= maxInputTokens) {
            return Optional.of(spent.inputTokens() + " input tokens >= cap " + maxInputTokens);
        }
        if (maxOutputTokens > 0 && spent.outputTokens() >= maxOutputTokens) {
            return Optional.of(spent.outputTokens() + " output tokens >= cap " + maxOutputTokens);
        }
        if (maxCostUsd > 0) {
            double cost = pricing.costOf(spent);
            if (cost >= maxCostUsd) {
                // Locale.ROOT: this runs inside a Temporal workflow via isExhausted(),
                // where a locale-dependent rendering would be replay-sensitive.
                return Optional.of(String.format(Locale.ROOT,
                        "estimated cost $%.4f >= cap $%.4f", cost, maxCostUsd));
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        List<String> caps = new ArrayList<>();
        if (maxInputTokens > 0) {
            caps.add("maxInputTokens=" + maxInputTokens);
        }
        if (maxOutputTokens > 0) {
            caps.add("maxOutputTokens=" + maxOutputTokens);
        }
        if (maxTotalTokens > 0) {
            caps.add("maxTotalTokens=" + maxTotalTokens);
        }
        if (maxCostUsd > 0) {
            caps.add(String.format(Locale.ROOT, "maxCostUsd=%.4f", maxCostUsd));
        }
        return "TokenBudget[" + String.join(", ", caps) + "]";
    }

    /** Fluent construction. At least one positive cap must be set. */
    public static final class Builder {
        private long maxInputTokens;
        private long maxOutputTokens;
        private long maxTotalTokens;
        private double maxCostUsd;
        private ModelPricing pricing;

        private Builder() {
        }

        /** Cap on cumulative input (prompt) tokens; ignored if {@code <= 0}. */
        public Builder maxInputTokens(long max) {
            this.maxInputTokens = max;
            return this;
        }

        /** Cap on cumulative output (completion) tokens; ignored if {@code <= 0}. */
        public Builder maxOutputTokens(long max) {
            this.maxOutputTokens = max;
            return this;
        }

        /** Cap on cumulative total (input + output) tokens; ignored if {@code <= 0}. */
        public Builder maxTotalTokens(long max) {
            this.maxTotalTokens = max;
            return this;
        }

        /**
         * Cap on cumulative estimated cost in USD, priced with {@code pricing}.
         *
         * @throws NullPointerException if {@code pricing} is null
         */
        public Builder maxCostUsd(double maxUsd, ModelPricing pricing) {
            this.maxCostUsd = maxUsd;
            this.pricing = java.util.Objects.requireNonNull(pricing, "pricing");
            return this;
        }

        /**
         * @throws IllegalArgumentException if no positive cap was set, or a cost cap
         *     was set without pricing — validated by the record's constructor, so the
         *     rules cannot diverge between the two ways of building one
         */
        public TokenBudget build() {
            return new TokenBudget(maxInputTokens, maxOutputTokens, maxTotalTokens, maxCostUsd, pricing);
        }
    }
}
