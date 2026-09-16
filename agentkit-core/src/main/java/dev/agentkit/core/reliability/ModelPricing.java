package dev.agentkit.core.reliability;

import dev.agentkit.core.llm.TokenUsage;

/**
 * Per-token pricing for a model, used to turn a {@link TokenUsage} into a dollar
 * cost for cost-capped {@link TokenBudget budgets}.
 *
 * <p>Prices are quoted per million tokens — the unit model vendors publish — so a
 * model that costs $5.00 per million input tokens and $25.00 per million output
 * tokens is {@code ModelPricing.of(5.00, 25.00)}. Cached reads and other tiers are
 * not modelled; this is a deliberately simple estimate for budgeting, not billing.
 *
 * @param inputPerMillionUsd  USD charged per 1,000,000 input (prompt) tokens
 * @param outputPerMillionUsd USD charged per 1,000,000 output (completion) tokens
 */
public record ModelPricing(double inputPerMillionUsd, double outputPerMillionUsd) {

    public ModelPricing {
        // Non-finite prices are rejected, not just negative ones: a NaN price makes
        // costOf() return NaN, and every `cost >= cap` comparison against NaN is false
        // — so a cost-capped budget would silently never trip. Infinity is refused for
        // the mirror-image reason (and because it has no portable JSON form).
        if (!Double.isFinite(inputPerMillionUsd) || !Double.isFinite(outputPerMillionUsd)) {
            throw new IllegalArgumentException("prices must be finite, were: "
                    + inputPerMillionUsd + ", " + outputPerMillionUsd);
        }
        if (inputPerMillionUsd < 0 || outputPerMillionUsd < 0) {
            throw new IllegalArgumentException("prices must be >= 0");
        }
    }

    /** Pricing in USD per million tokens. */
    public static ModelPricing of(double inputPerMillionUsd, double outputPerMillionUsd) {
        return new ModelPricing(inputPerMillionUsd, outputPerMillionUsd);
    }

    /** The estimated USD cost of {@code usage} at this pricing. */
    public double costOf(TokenUsage usage) {
        return usage.inputTokens() / 1_000_000.0 * inputPerMillionUsd
                + usage.outputTokens() / 1_000_000.0 * outputPerMillionUsd;
    }
}
