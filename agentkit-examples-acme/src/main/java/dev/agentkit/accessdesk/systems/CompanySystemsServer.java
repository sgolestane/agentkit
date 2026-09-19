package dev.agentkit.accessdesk.systems;

import dev.agentkit.acme.HttpConnector;
import dev.agentkit.mcp.server.McpServer;
import dev.agentkit.mcp.server.StdioMcpServer;
import java.nio.file.Path;

/**
 * The company systems as an MCP server: over stdio, or over HTTP.
 *
 * <p>Over stdio it runs as a subprocess of an MCP client, as any local MCP server would. The only argument is the
 * directory the state is kept in; without one it is kept in memory.
 *
 * <p>{@code --http <port> <token> [stateDir]} serves it over HTTP instead, the way the agent host reaches a
 * connector, answering only a caller with that bearer token. With no arguments and {@code COMPANY_HTTP_TOKEN} set,
 * it does the same on {@code COMPANY_HTTP_PORT} (default 8130), keeping state in {@code COMPANY_DATA_DIR} if set.
 */
public final class CompanySystemsServer {

    private CompanySystemsServer() {
    }

    static final String INSTRUCTIONS = "The company's directory, resource catalog, access and direct messages.";

    public static void main(String[] args) throws Exception {
        String token = System.getenv("COMPANY_HTTP_TOKEN");
        if (args.length == 0 && token != null && !token.isBlank()) {
            String dir = System.getenv("COMPANY_DATA_DIR");
            args = new String[] {"--http", System.getenv().getOrDefault("COMPANY_HTTP_PORT", "8130"), token,
                    dir == null ? "" : dir};
        }
        if (args.length >= 3 && args[0].equals("--http")) {
            Path stateDir = args.length > 3 && !args[3].isBlank() ? Path.of(args[3]) : null;
            HttpConnector served = HttpConnector.serve(Integer.parseInt(args[1]), "company-systems", INSTRUCTIONS,
                    args[2], CompanySystems.open(stateDir).catalog());
            System.out.println("company systems at " + served.url());
            Thread.currentThread().join();
            return;
        }
        Path stateDir = args.length > 0 && !args[0].isBlank() ? Path.of(args[0]) : null;
        CompanySystems systems = CompanySystems.open(stateDir);
        McpServer server = new McpServer("company-systems", "0.1.0", INSTRUCTIONS);
        StdioMcpServer.serve(server, systems.catalog(), System.in, System.out);
    }
}
