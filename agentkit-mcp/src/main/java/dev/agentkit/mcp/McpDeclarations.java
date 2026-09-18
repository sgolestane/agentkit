package dev.agentkit.mcp;

import dev.agentkit.core.tool.ToolDeclaration;
import dev.agentkit.core.tool.ToolEffect;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * How a {@link ToolDeclaration} travels over MCP: three keys in a listed tool's {@code _meta}.
 *
 * <p>The standard annotations can say a tool only reads, or that it may be destructive; they cannot say it
 * <em>grants</em> something, or which argument names the person it acts on, and every rule AgentKit applies to
 * tools is written in those terms. So a server built with {@link dev.agentkit.mcp.server.McpServer} sends the full
 * declaration in {@code _meta} beside the standard hints, and a client reads it back with {@link #declare}. A
 * server in another language takes part by sending the same keys; {@code docs/MCP-CONNECTORS.md} is the contract.
 *
 * <pre>{@code
 * "_meta": {
 *   "dev.agentkit/effect":  "grant",        // read | grant | revoke | notify | request | schedule
 *   "dev.agentkit/system":  "okta",         // optional; the connector's name otherwise
 *   "dev.agentkit/subject": "email"         // optional; the argument naming whom it acts on
 * }
 * }</pre>
 */
public final class McpDeclarations {

    public static final String EFFECT = "dev.agentkit/effect";
    public static final String SYSTEM = "dev.agentkit/system";
    public static final String SUBJECT = "dev.agentkit/subject";

    /** An operator's own declaration for a tool, which wins over whatever the server sends; any field may be null. */
    public record Override(String effect, String system, String subject) {
        public static final Override NONE = new Override(null, null, null);
    }

    private McpDeclarations() {
    }

    /** The {@code _meta} entries that carry {@code declaration}. */
    public static Map<String, Object> meta(ToolDeclaration declaration) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(EFFECT, declaration.effect().wire());
        meta.put(SYSTEM, declaration.system());
        if (declaration.subjectParam() != null) {
            meta.put(SUBJECT, declaration.subjectParam());
        }
        return meta;
    }

    /**
     * The declaration for one listed tool. First match wins, field by field:
     * <ol>
     *   <li>{@code override}, for a server that does not describe itself or an operator who disagrees with one that
     *       does;</li>
     *   <li>the server's {@code _meta} keys;</li>
     *   <li>when {@code trustAnnotations}, a {@code readOnlyHint} of true, which is enough to declare a read.</li>
     * </ol>
     * Empty when none of them gives an effect: an undeclared tool could do anything, so callers leave it out.
     *
     * @param serverName the connector's name, which is the system when nothing else names one
     */
    public static Optional<ToolDeclaration> declare(String serverName, McpToolInfo listed, Override override,
                                                    boolean trustAnnotations) {
        Override given = override == null ? Override.NONE : override;
        Map<String, Object> meta = listed.meta();
        Optional<ToolEffect> effect = ToolEffect.parse(given.effect())
                .or(() -> ToolEffect.parse(text(meta.get(EFFECT))))
                .or(() -> trustAnnotations && Boolean.TRUE.equals(listed.annotations().readOnlyHint())
                        ? Optional.of(ToolEffect.READ) : Optional.empty());
        if (effect.isEmpty()) {
            return Optional.empty();
        }
        String system = Optional.ofNullable(blankToNull(given.system()))
                .or(() -> Optional.ofNullable(blankToNull(text(meta.get(SYSTEM)))))
                .orElse(serverName);
        String subject = Optional.ofNullable(blankToNull(given.subject())).orElse(text(meta.get(SUBJECT)));
        return Optional.of(new ToolDeclaration(system, effect.get(), subject));
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
