package dev.agentkit.accessdesk.systems;

import dev.agentkit.mcp.server.McpServer;
import dev.agentkit.mcp.server.StdioMcpServer;
import java.nio.file.Path;

/**
 * The company systems as an MCP server over stdio.
 *
 * <p>Access Desk launches this as a subprocess through {@code connectors.json}, the same way it would
 * launch any other MCP server. The only argument is the directory the state is kept in; without one it
 * is kept in memory.
 */
public final class CompanySystemsServer {

    private CompanySystemsServer() {
    }

    public static void main(String[] args) {
        Path stateDir = args.length > 0 && !args[0].isBlank() ? Path.of(args[0]) : null;
        CompanySystems systems = CompanySystems.open(stateDir);
        McpServer server = new McpServer("company-systems", "0.1.0",
                "The company's directory, resource catalog, access and direct messages.");
        StdioMcpServer.serve(server, systems.catalog(), System.in, System.out);
    }
}
