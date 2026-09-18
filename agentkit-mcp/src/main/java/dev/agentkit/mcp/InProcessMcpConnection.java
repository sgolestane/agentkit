package dev.agentkit.mcp;

import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An {@link McpConnection} to a {@link DeclaredTools} in the same process, with no transport between — for
 * tests and evals that want MCP's shapes (and {@code McpTool}'s fencing) without a subprocess.
 */
public final class InProcessMcpConnection implements McpConnection {

    private final DeclaredTools tools;

    public InProcessMcpConnection(DeclaredTools tools) {
        this.tools = tools;
    }

    @Override
    public List<McpToolInfo> listTools() {
        return tools.entries().stream()
                .map(e -> new McpToolInfo(e.tool().name(), e.tool().description(), e.tool().inputSchema(),
                        McpDeclarations.meta(e.declaration())))
                .toList();
    }

    @Override
    public McpCallResult callTool(String name, Map<String, Object> arguments) {
        return callTool(name, arguments, Map.of());
    }

    @Override
    public McpCallResult callTool(String name, Map<String, Object> arguments, Map<String, Object> meta) {
        return CallMeta.receiving(meta, () -> execute(name, arguments));
    }

    private McpCallResult execute(String name, Map<String, Object> arguments) {
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
