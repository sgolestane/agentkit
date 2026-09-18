package dev.agentkit.mcp.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.mcp.HttpMcpConnection;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Serves an {@link McpServer} over MCP's streamable HTTP transport, in its simplest conforming form: a
 * client POSTs one JSON-RPC message and gets one JSON response back ({@code application/json}), or
 * {@code 202 Accepted} for a notification. No server-initiated stream is offered, so {@code GET} is
 * {@code 405}.
 *
 * <p><strong>Who is calling</strong> is the {@link Callers}' answer for the request's headers, and the catalog
 * served is the one {@code toolsFor} returns for that caller — so the same tool acts as whoever asked. A request
 * whose caller is unknown, or who has no catalog, is refused with {@code 401}. {@link Callers#header} trusts a
 * header as it stands, which is a demo identity for a local deployment only.
 *
 * <p><strong>Where requests may come from.</strong> A browser page on another origin could otherwise reach a
 * server on localhost, so a request carrying an {@code Origin} header is refused unless {@code origins} allows it;
 * by default only localhost origins are. Clients such as Claude Code send none.
 */
public final class HttpMcpEndpoint implements HttpHandler {

    static final String SESSION_HEADER = HttpMcpConnection.SESSION_HEADER;

    /** Who a request comes from, judged from its headers; empty when it cannot be told. */
    @FunctionalInterface
    public interface Callers {
        Optional<String> identify(Headers headers);

        /** The value of {@code header}, unauthenticated: whoever sends it is taken at their word. */
        static Callers header(String header) {
            Objects.requireNonNull(header, "header");
            return headers -> Optional.ofNullable(headers.getFirst(header)).map(String::strip).filter(s -> !s.isEmpty());
        }
    }

    private final McpServer server;
    private final Callers callers;
    private final Function<String, Optional<DeclaredTools>> toolsFor;
    private final Predicate<String> origins;

    /**
     * Allows localhost origins only.
     *
     * @param toolsFor the catalog to serve to a caller, or empty if the caller may not use this server
     */
    public HttpMcpEndpoint(McpServer server, Callers callers, Function<String, Optional<DeclaredTools>> toolsFor) {
        this(server, callers, toolsFor, HttpMcpEndpoint::localOrigin);
    }

    /** @param origins whether a request carrying this {@code Origin} header may be answered */
    public HttpMcpEndpoint(McpServer server, Callers callers, Function<String, Optional<DeclaredTools>> toolsFor,
                           Predicate<String> origins) {
        this.server = Objects.requireNonNull(server, "server");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.toolsFor = Objects.requireNonNull(toolsFor, "toolsFor");
        this.origins = Objects.requireNonNull(origins, "origins");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.isBlank() && !origins.test(origin.strip())) {
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
            Optional<DeclaredTools> tools = callers.identify(exchange.getRequestHeaders()).flatMap(toolsFor);
            if (tools.isEmpty()) {
                send(exchange, 401, McpServer.error(message == null ? null : message.get("id"), -32001,
                        "Unknown caller"));
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

    /** Whether {@code origin} is a page served from this machine. */
    public static boolean localOrigin(String origin) {
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
