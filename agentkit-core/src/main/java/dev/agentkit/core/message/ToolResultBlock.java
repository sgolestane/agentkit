package dev.agentkit.core.message;

import dev.agentkit.core.tool.Provenance;
import java.util.Objects;

/**
 * The result of a tool invocation, sent back to the model.
 *
 * <p>{@code provenance} does not reach the model — the wire format has nowhere to put it,
 * and it is not the model's business anyway. It stays on the block so that whatever reads
 * the transcript afterwards can: an observer building an audit trail, a compactor deciding
 * what a summary inherits, a caller's own filter. Content arrives labelled or it does not
 * arrive labelled at all, and the label has to survive being written down.
 *
 * @param toolUseId  the {@link ToolUseBlock#id()} this result corresponds to;
 *                   never {@code null}
 * @param content    the textual result content; never {@code null}
 * @param isError    whether the tool invocation failed. When {@code true}, the
 *                   model is expected to treat {@code content} as an error
 *                   message and adapt.
 * @param provenance who wrote {@code content}; {@link Provenance#UNKNOWN} when nothing said
 */
public record ToolResultBlock(String toolUseId, String content, boolean isError,
                              Provenance provenance) implements ContentBlock {

    public ToolResultBlock {
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(content, "content");
        // Coerced, not required. Jackson deserializes a record through its canonical
        // constructor and passes null for a field the payload does not carry, so requiring
        // it here refuses every payload written before this component existed — which on
        // the durable path is a run already in flight: the workflow re-reads its own
        // activity results from history on every replay, so a rolling deploy would make a
        // tool-using run fail its workflow task, and Temporal retries that forever. The run
        // stalls rather than fails, which is worse. DurableJson states this rule and
        // ToolSpec's examples field already follows it.
        provenance = provenance == null ? Provenance.UNKNOWN : provenance;
    }

    /** A block whose content nothing has attributed. */
    public ToolResultBlock(String toolUseId, String content, boolean isError) {
        this(toolUseId, content, isError, Provenance.UNKNOWN);
    }

    public static ToolResultBlock ok(String toolUseId, String content) {
        return new ToolResultBlock(toolUseId, content, false);
    }

    public static ToolResultBlock error(String toolUseId, String content) {
        return new ToolResultBlock(toolUseId, content, true);
    }
}
