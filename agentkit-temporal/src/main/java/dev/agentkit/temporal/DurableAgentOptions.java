package dev.agentkit.temporal;

/**
 * Tuning knobs for the activities a durable run issues, carried in the workflow
 * input so they replay deterministically (rather than being baked into the
 * workflow code).
 *
 * <p>Timeouts are start-to-close per activity attempt; {@code maxAttempts}
 * bounds Temporal's automatic retry. The LLM activity is expected to fail
 * transiently (rate limits, network blips) and so retries; the tool activity
 * already converts a failed tool into an error result the model reacts to, so
 * its retry count guards only against genuinely transient infrastructure faults.
 * Because retry is at-least-once, set {@code toolMaxAttempts} to 1 for a tool
 * whose side effect is not idempotent (see {@link ToolActivities}).
 *
 * @param llmStartToCloseSeconds  per-attempt timeout for an LLM call
 * @param llmMaxAttempts          max attempts for an LLM call (>= 1)
 * @param toolStartToCloseSeconds per-attempt timeout for a tool call
 * @param toolMaxAttempts         max attempts for a tool call (>= 1)
 * @param approvalTimeoutSeconds  how long the workflow waits for somebody to decide a call
 *                                a gate parked, before ending the run with
 *                                {@code AWAITING_APPROVAL} (>= 1)
 */
public record DurableAgentOptions(long llmStartToCloseSeconds, int llmMaxAttempts,
                                  long toolStartToCloseSeconds, int toolMaxAttempts,
                                  long approvalTimeoutSeconds) {

    /**
     * A day. Long enough that a person has a working day; short enough to be an answer.
     *
     * <p>It is a default and not a recommendation. The one human-in-the-loop example in
     * this repository describes an approval "decided next week by someone who was not
     * here", and a day silently kills that run — so a deployment that pages a rota rather
     * than a person at a desk should raise it, or pass {@link #NO_APPROVAL_DEADLINE}.
     */
    public static final long DEFAULT_APPROVAL_TIMEOUT_SECONDS = 86_400;

    /**
     * Wait for as long as it takes, which a workflow can do and an activity cannot.
     *
     * <p>Spelled as a constant rather than as {@code 0}, because {@code 0} already means
     * something here: a run started before this component existed deserializes it as zero,
     * and a zero-second deadline would expire the moment such a run reached a park. So
     * absent means "the default" and this means "no deadline".
     */
    public static final long NO_APPROVAL_DEADLINE = Long.MAX_VALUE / 1_000;

    public DurableAgentOptions {
        if (llmStartToCloseSeconds <= 0 || toolStartToCloseSeconds <= 0) {
            throw new IllegalArgumentException("start-to-close timeouts must be > 0");
        }
        if (llmMaxAttempts < 1 || toolMaxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        // The next line coerces where the two above throw, which is a real inconsistency
        // and a deliberate one: those four components have existed since the first durable
        // run, so no payload in any history is missing them, and a caller passing zero is
        // making a mistake worth failing on. This one is new, so a payload in flight can be
        // missing it — and refusing there fails a workflow task rather than a constructor.
        // If another component is added later it should coerce too, for the same reason.
        // Coerced rather than refused, because this record travels in the workflow input
        // and is therefore read back out of history on every replay. A run started before
        // this component existed carries no value for it, and Jackson passes 0; refusing
        // that would fail the workflow task of a live run, which Temporal retries forever.
        // The rule DurableJson states is "keep added components nullable"; for a primitive
        // the equivalent is to treat the zero value as absent.
        approvalTimeoutSeconds = approvalTimeoutSeconds <= 0
                ? DEFAULT_APPROVAL_TIMEOUT_SECONDS
                : approvalTimeoutSeconds;
    }

    /**
     * The four-value shape, for callers written before an approval could be waited out.
     *
     * <p>They get {@link #DEFAULT_APPROVAL_TIMEOUT_SECONDS}, which is only reachable by a
     * run whose gate parks a call — and a gate that parks is a thing they could not have
     * had.
     */
    public DurableAgentOptions(long llmStartToCloseSeconds, int llmMaxAttempts,
                               long toolStartToCloseSeconds, int toolMaxAttempts) {
        this(llmStartToCloseSeconds, llmMaxAttempts, toolStartToCloseSeconds, toolMaxAttempts,
                DEFAULT_APPROVAL_TIMEOUT_SECONDS);
    }

    /**
     * Sensible defaults: 120s/4 attempts for LLM calls, 60s/3 for tools, and a day for
     * somebody to answer a parked call.
     */
    public static DurableAgentOptions defaults() {
        return new DurableAgentOptions(120, 4, 60, 3, DEFAULT_APPROVAL_TIMEOUT_SECONDS);
    }

    /** The same options, waiting {@code seconds} for a person rather than a day. */
    public DurableAgentOptions withApprovalTimeout(long seconds) {
        return new DurableAgentOptions(llmStartToCloseSeconds, llmMaxAttempts,
                toolStartToCloseSeconds, toolMaxAttempts, seconds);
    }
}
