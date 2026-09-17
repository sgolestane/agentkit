package dev.agentkit.mcp;

import dev.agentkit.core.tool.SideEffects;
import java.util.Map;

/**
 * What an MCP server says about a tool's behaviour: the protocol's {@code annotations} hints.
 *
 * <p><strong>Hints, not guarantees.</strong> The protocol is explicit that a client must not rely on them
 * from a server it does not trust, and a hostile server has every reason to call a destructive tool
 * read-only. So they are always captured — a deployment can read them, log them, show them — but
 * {@link McpTool} acts on them only when told to ({@link McpTool#trustingAnnotations}). Until then its side
 * effects stay {@link SideEffects#UNKNOWN}, which every gate treats as the unsafe case.
 *
 * <p>Only the four boolean hints are held. {@code title} is prose a model never needs from here, and a value
 * of the wrong type is dropped rather than coerced: {@code "readOnlyHint": "yes"} is not a claim this record
 * will make on the server's behalf.
 *
 * @param readOnlyHint    the tool does not change its environment; absent means false
 * @param destructiveHint when it does change something, the change may be destructive; absent means true
 * @param idempotentHint  calling it again with the same arguments has no further effect; absent means false
 * @param openWorldHint   it reaches beyond a closed set of entities; absent means true
 */
public record McpToolAnnotations(Boolean readOnlyHint, Boolean destructiveHint, Boolean idempotentHint,
                                 Boolean openWorldHint) {

    /** A tool the server said nothing about. */
    public static final McpToolAnnotations NONE = new McpToolAnnotations(null, null, null, null);

    /** The hints in a tool listing's {@code annotations} object; {@link #NONE} for null or empty. */
    public static McpToolAnnotations from(Map<String, Object> annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return NONE;
        }
        return new McpToolAnnotations(bool(annotations.get("readOnlyHint")), bool(annotations.get("destructiveHint")),
                bool(annotations.get("idempotentHint")), bool(annotations.get("openWorldHint")));
    }

    /** Whether the server gave none of the hints. */
    public boolean isEmpty() {
        return readOnlyHint == null && destructiveHint == null && idempotentHint == null && openWorldHint == null;
    }

    /**
     * The side effects these hints describe, read with the protocol's defaults for anything absent:
     * <ul>
     *   <li>no hints at all — {@link SideEffects#UNKNOWN};</li>
     *   <li>{@code readOnlyHint} — {@link SideEffects#NONE};</li>
     *   <li>{@code idempotentHint} with {@code destructiveHint} explicitly false — {@link SideEffects#IDEMPOTENT};</li>
     *   <li>anything else — {@link SideEffects#EXTERNAL}.</li>
     * </ul>
     * A destructive tool is never idempotent here, whatever it claims: undoing a deletion by repeating it is not
     * what a caller retrying a step means by "safe to retry".
     */
    public SideEffects asSideEffects() {
        if (isEmpty()) {
            return SideEffects.UNKNOWN;
        }
        if (Boolean.TRUE.equals(readOnlyHint)) {
            return SideEffects.NONE;
        }
        if (Boolean.TRUE.equals(idempotentHint) && Boolean.FALSE.equals(destructiveHint)) {
            return SideEffects.IDEMPOTENT;
        }
        return SideEffects.EXTERNAL;
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean b ? b : null;
    }
}
