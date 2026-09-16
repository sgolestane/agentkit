package dev.agentkit.mcp;

import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolRegistry;
import java.util.List;
import java.util.Objects;

/**
 * Bridges an {@link McpConnection}'s tools into AgentKit.
 *
 * <p>Discovery is eager: {@link #load} calls {@code tools/list} once and wraps each
 * advertised tool as an {@link McpTool}. Drop the results into any registry, or use
 * {@link #registry} to get one backed entirely by the MCP server. To combine MCP
 * tools with local ones, pass both to a {@code SimpleToolRegistry}.
 *
 * <pre>{@code
 * try (McpConnection mcp = StdioMcpConnection.start(List.of("my-mcp-server"))) {
 *     ToolRegistry tools = new SimpleToolRegistry(
 *             concat(localTools, McpTools.load(mcp)));
 *     new Agent(llm, tools, config).run(goal);
 * }
 * }</pre>
 */
public final class McpTools {

    private McpTools() {
    }

    /** Loads the server's advertised tools as AgentKit {@link Tool}s (calls {@code tools/list}). */
    public static List<Tool> load(McpConnection connection) {
        Objects.requireNonNull(connection, "connection");
        return connection.listTools().stream()
                .<Tool>map(info -> new McpTool(connection, info))
                .toList();
    }

    /** A {@link ToolRegistry} advertising exactly the server's tools. */
    public static ToolRegistry registry(McpConnection connection) {
        return new SimpleToolRegistry(load(connection));
    }
}
