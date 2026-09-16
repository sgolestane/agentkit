package dev.agentkit.core.reliability;

/**
 * Exponential-backoff retry policy for transient failures.
 *
 * @param maxRetries     maximum retry attempts after the initial try (>= 0)
 * @param baseDelayMillis delay before the first retry
 * @param maxDelayMillis  cap on any single delay
 * @param multiplier      per-attempt backoff multiplier (>= 1.0)
 */
public record RetryPolicy(int maxRetries, long baseDelayMillis, long maxDelayMillis, double multiplier) {

    public RetryPolicy {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        if (baseDelayMillis < 0 || maxDelayMillis < 0) {
            throw new IllegalArgumentException("delays must be >= 0");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0");
        }
    }

    /** A sensible default: 3 retries, 500ms base, 10s cap, doubling. */
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, 500, 10_000, 2.0);
    }

    /** No retries. */
    public static RetryPolicy none() {
        return new RetryPolicy(0, 0, 0, 1.0);
    }

    /** The delay before retry attempt {@code attempt} (1-based), capped. */
    public long delayForAttempt(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        double delay = baseDelayMillis * Math.pow(multiplier, attempt - 1);
        return (long) Math.min(delay, maxDelayMillis);
    }
}
