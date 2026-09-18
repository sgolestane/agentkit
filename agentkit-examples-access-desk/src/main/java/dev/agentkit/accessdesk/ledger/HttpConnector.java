package dev.agentkit.accessdesk.ledger;

import com.sun.net.httpserver.HttpServer;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.mcp.server.HttpMcpEndpoint;
import dev.agentkit.mcp.server.McpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * A connector served the way the agent host reaches one: MCP over HTTP at {@code /mcp}, answering only a caller that
 * presents its bearer token — the connection credential the host holds for this organization.
 */
public final class HttpConnector implements AutoCloseable {

    private final HttpServer server;

    private HttpConnector(HttpServer server) {
        this.server = server;
    }

    /**
     * Serves {@code tools} on {@code port} (0 for any free one) on localhost.
     *
     * @param token the bearer token a caller must send; compared in constant time
     */
    public static HttpConnector serve(int port, String name, String instructions, String token, DeclaredTools tools)
            throws IOException {
        Objects.requireNonNull(token, "token");
        if (token.isBlank()) {
            throw new IllegalArgumentException("A connector needs a token to check its caller against");
        }
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        // Concurrent, so a call that asks the person something can be answered while it waits.
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, name + "-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/mcp", new HttpMcpEndpoint(new McpServer(name, "0.1.0", instructions),
                HttpMcpEndpoint.Callers.header("Authorization"),
                caller -> MessageDigest.isEqual(caller.getBytes(StandardCharsets.UTF_8), expected)
                        ? Optional.of(tools) : Optional.empty()));
        server.start();
        return new HttpConnector(server);
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
