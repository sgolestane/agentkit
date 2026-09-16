package dev.agentkit.core.llm;

/**
 * Receives incremental output as a model turn streams in. Passed to
 * {@link LlmClient#generate(LlmRequest, StreamHandler)} so a caller can show text
 * as it is produced instead of waiting for the whole turn — useful for responsive
 * UIs and long answers that would otherwise sit behind a single blocking call.
 *
 * <p>Only text deltas are surfaced; tool-use and thinking blocks are delivered
 * whole in the final {@link LlmResponse}. Deltas are fragments to concatenate, not
 * complete messages, and the returned response remains the source of truth for the
 * assembled text, stop reason, and usage.
 */
@FunctionalInterface
public interface StreamHandler {

    /** Discards every delta — the default when a caller does not consume the stream. */
    StreamHandler NONE = delta -> { };

    /** Called for each text fragment as it arrives; {@code delta} is never null. */
    void onTextDelta(String delta);
}
