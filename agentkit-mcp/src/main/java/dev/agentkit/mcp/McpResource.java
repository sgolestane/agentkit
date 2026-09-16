package dev.agentkit.mcp;

import dev.agentkit.core.util.Cut;
import java.util.Objects;

/**
 * One resource a server predeclared, as {@code resources/read} returns it.
 *
 * <p>Here for MCP Apps (SEP-1865), whose whole mechanism is that a tool points at a
 * {@code ui://} resource and the host fetches it. The extension defines exactly one content
 * type for now — {@code text/html;profile=mcp-app} — and everything else is a resource this
 * host has no use for.
 *
 * @param uri      what was asked for
 * @param mimeType what the server says it is; the profile parameter is part of the string
 * @param text     the contents, bounded — see {@link #MAX_CHARS}
 */
public record McpResource(String uri, String mimeType, String text) {

    /** The MCP Apps content type, exactly as the specification spells it. */
    public static final String APP_HTML = "text/html;profile=mcp-app";

    /**
     * How much of a resource this host will hold.
     *
     * <p>A UI resource is a self-contained page — the spec requires it, because the host
     * renders it with external network requests blocked — so it is legitimately larger than
     * anything else this module carries. It is still a server's bytes arriving over a pipe,
     * and an unbounded read is an unbounded allocation somebody else chooses.
     */
    public static final int MAX_CHARS = 512_000;

    public McpResource {
        Objects.requireNonNull(uri, "uri");
        mimeType = mimeType == null ? "" : mimeType;
        text = Cut.to(text == null ? "" : text, MAX_CHARS);
    }

    /**
     * Whether this is a thing a host should try to render.
     *
     * <p>The type is compared with its parameters ignored and the profile required. A server
     * sending plain {@code text/html} has not opted into the extension, and rendering it
     * anyway would mean this host renders arbitrary HTML any MCP server hands it — which is
     * the difference between supporting an extension and having a vulnerability.
     */
    public boolean isApp() {
        String type = mimeType.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
        return type.startsWith("text/html") && type.contains("profile=mcp-app");
    }
}
