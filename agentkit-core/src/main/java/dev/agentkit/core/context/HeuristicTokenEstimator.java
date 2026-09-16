package dev.agentkit.core.context;

/**
 * A dependency-free token estimator using the common ~4-characters-per-token
 * approximation. Intended for budgeting decisions, not billing.
 */
public final class HeuristicTokenEstimator implements TokenEstimator {

    private static final double CHARS_PER_TOKEN = 4.0;

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}
