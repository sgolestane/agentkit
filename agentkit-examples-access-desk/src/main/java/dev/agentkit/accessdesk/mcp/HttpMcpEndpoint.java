package dev.agentkit.accessdesk.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.agentkit.accessdesk.tools.ToolCatalog;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Serves an {@link McpServer} over MCP's streamable HTTP transport, in its simplest conforming form: a
 * client POSTs one JSON-RPC message and gets one JSON response back ({@code application/json}), or
 * {@code 202 Accepted} for a notification. No server-initiated stream is offered, so {@code GET} is
 * {@code 405}.
 *
 * <p><strong>Who is calling.</strong> Each request names a caller in the {@link #USER_HEADER} header,
 * and the catalog served is the one {@code toolsFor} returns for that caller — so the same tool acts as
 * whoever asked. That is a demo identity, suitable for a local deployment only: nothing here
 * authenticates the header.
 *
 * <p><strong>Where requests may come from.</strong> A browser page on another origin could otherwise
 * reach a server on localhost, so a request carrying an {@code Origin} header is refused unless it is a
 * localhost origin. Clients such as Claude Code send none.
 */
public final class HttpMcpEndpoint implements HttpHandler {

    public static final String USER_HEADER = "X-Access-Desk-User";
    static final String SESSION_HEADER = "Mcp-Session-Id";

    private final McpServer server;
    private final Function<String, Optional<ToolCatalog>> toolsFor;

    /**
     * @param toolsFor the catalog to serve to a caller, or empty if the caller is not known
     */
    public HttpMcpEndpoint(McpServer server, Function<String, Optional<ToolCatalog>> toolsFor) {
        this.server = Objects.requireNonNull(server, "server");
        this.toolsFor = Objects.requireNonNull(toolsFor, "toolsFor");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!localOrigin(exchange.getRequestHeaders().getFirst("Origin"))) {
                send(exchange, 403, McpServer.error(null, -32000, "Origin not allowed"));
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Allow", "POST");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            JsonNode message;
            try {
                message = McpServer.mapper().readTree(exchange.getRequestBody().readAllBytes());
            } catch (JsonProcessingException e) {
                send(exchange, 400, McpServer.error(null, -32700, "Parse error"));
                return;
            }
            String user = exchange.getRequestHeaders().getFirst(USER_HEADER);
            Optional<ToolCatalog> tools = user == null ? Optional.empty() : toolsFor.apply(user.strip());
            if (tools.isEmpty()) {
                send(exchange, 401, McpServer.error(message == null ? null : message.get("id"), -32001,
                        "Unknown caller: send the " + USER_HEADER + " header with a known user's email"));
                return;
            }
            Optional<ObjectNode> response = server.handle(message, tools.get());
            if (response.isEmpty()) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            if ("initialize".equals(message.path("method").asText())) {
                exchange.getResponseHeaders().add(SESSION_HEADER, UUID.randomUUID().toString());
            }
            send(exchange, 200, response.get());
        }
    }

    static boolean localOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            String host = URI.create(origin.strip()).getHost();
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host)
                    || "::1".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = McpServer.mapper().writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
