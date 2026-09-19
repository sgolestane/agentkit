package dev.agentkit.mcp;

import java.util.List;
import java.util.Map;

/**
 * A live connection to a Model Context Protocol (MCP) server, reduced to the two
 * operations AgentKit needs: discovering tools and invoking them.
 *
 * <p>This is the seam the tool bridge ({@link McpTools}) depends on, so the bridge
 * is testable against a fake connection and independent of transport. The shipped
 * transports are {@link StdioMcpConnection} (JSON-RPC over a subprocess's stdio) and
 * {@link HttpMcpConnection} (streamable HTTP, for a server that runs elsewhere);
 * {@link InProcessMcpConnection} skips the transport for tests.
 * Implementations are not required to be thread-safe.
 */
public interface McpConnection extends AutoCloseable {

    /** Lists the tools the server advertises ({@code tools/list}). */
    List<McpToolInfo> listTools();

    /**
     * Invokes a tool by name with the given arguments ({@code tools/call}).
     *
     * @param name      the tool name
     * @param arguments the tool arguments (may be empty)
     * @return the tool's result, including the server's error flag
     * @throws McpException if the transport fails or the server returns a protocol error
     */
    McpCallResult callTool(String name, Map<String, Object> arguments);

    /**
     * {@link #callTool(String, Map)}, with {@code meta} as the call's {@code _meta} — such as a signed statement of who is
     * calling. A connection that cannot carry it makes the call without it.
     */
    default McpCallResult callTool(String name, Map<String, Object> arguments, Map<String, Object> meta) {
        return callTool(name, arguments);
    }

    /**
     * Reads a resource the server predeclared ({@code resources/read}).
     *
     * <p>Needed for MCP Apps (SEP-1865): a tool points at a {@code ui://} resource holding
     * the HTML a host renders. Nothing else in AgentKit reads resources, which is why this
     * arrives with a default rather than as a third required method — a connection written
     * before the extension existed is not broken by it, it simply has no apps.
     *
     * @return the resource's contents, or empty if this connection cannot read resources or
     *     the server has no such resource
     */
    default java.util.Optional<McpResource> readResource(String uri) {
        return java.util.Optional.empty();
    }

    /** Closes the connection and releases its transport (e.g. terminates the subprocess). */
    @Override
    void close();
}
