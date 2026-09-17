package dev.agentkit.accessdesk.mcp;

import dev.agentkit.accessdesk.tools.ToolCatalog;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.mcp.McpCallResult;
import dev.agentkit.mcp.McpConnection;
import dev.agentkit.mcp.McpToolInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An {@link McpConnection} to a {@link ToolCatalog} in the same process, with no transport between — for
 * tests and evals that want MCP's shapes (and {@code McpTool}'s fencing) without a subprocess.
 */
public final class InProcessMcpConnection implements McpConnection {

    private final ToolCatalog tools;

    public InProcessMcpConnection(ToolCatalog tools) {
        this.tools = tools;
    }

    @Override
    public List<McpToolInfo> listTools() {
        return tools.entries().stream()
                .map(e -> new McpToolInfo(e.tool().name(), e.tool().description(), e.tool().inputSchema(),
                        Map.of(McpServer.META_EFFECT, e.info().effect().wire())))
                .toList();
    }

    @Override
    public McpCallResult callTool(String name, Map<String, Object> arguments) {
        ToolResult result = tools.entry(name)
                .map(e -> e.tool().execute(new ToolInvocation("in-process-" + UUID.randomUUID(), name,
                        new HashMap<>(arguments == null ? Map.of() : arguments))))
                .orElse(ToolResult.error("Unknown tool: " + name));
        return new McpCallResult(result.content(), result.isError());
    }

    @Override
    public void close() {
    }
}
