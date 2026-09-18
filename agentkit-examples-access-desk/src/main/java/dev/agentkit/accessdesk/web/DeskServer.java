package dev.agentkit.accessdesk.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agentkit.core.deferred.DeferredActionStore;
import dev.agentkit.core.deferred.DeferredRunner;
import dev.agentkit.mcp.server.HttpMcpEndpoint;
import dev.agentkit.mcp.server.McpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The desk's own HTTP server, on localhost: a landing page linking each person's console, the demo clock, the
 * list of deferred actions, and the MCP endpoint.
 *
 * <ul>
 *   <li>{@code GET /} — the landing page</li>
 *   <li>{@code GET /api/desk} — who has a console where, and the MCP endpoint</li>
 *   <li>{@code GET /api/clock}, {@code POST /api/clock/advance?minutes=N} — read the clock, or move it forward and
 *       run whatever is now due</li>
 *   <li>{@code GET /api/deferred} — every deferred action and how it ended</li>
 *   <li>{@code POST /mcp} — Access Desk as an MCP server (streamable HTTP)</li>
 * </ul>
 */
public final class DeskServer implements AutoCloseable {

    /** One person's console. */
    public record Console(String email, String name, String title, String url) {
    }

    /**
     * The header an MCP client names its person in. A demo identity: nothing authenticates it, which is why the
     * endpoint only answers on localhost.
     */
    public static final String USER_HEADER = "X-Access-Desk-User";

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final HttpServer server;
    private final DemoClock clock;
    private final DeferredRunner runner;
    private final DeferredActionStore store;
    private final List<Console> consoles;

    public DeskServer(int port, DemoClock clock, DeferredRunner runner, DeferredActionStore store, List<Console> consoles,
                      McpBridge bridge) throws IOException {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runner = runner;
        this.store = Objects.requireNonNull(store, "store");
        this.consoles = List.copyOf(consoles);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/desk", this::desk);
        server.createContext("/api/clock", this::clock);
        server.createContext("/api/deferred", this::deferred);
        server.createContext("/mcp", new HttpMcpEndpoint(new McpServer("access-desk", "0.1.0",
                "Access Desk grants temporary access under the company's access policy. Use ask_access_desk to ask for "
                        + "access, check on requests, or decide requests waiting for you."), HttpMcpEndpoint.Callers.header(USER_HEADER),
                bridge::toolsFor));
        server.createContext("/", this::landing);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void desk(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("consoles", consoles);
        body.put("mcp", "http://localhost:" + port() + "/mcp");
        body.put("mcpUserHeader", USER_HEADER);
        body.put("now", clock.get());
        json(exchange, 200, body);
    }

    private void clock(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getPath().endsWith("/advance")) {
            String query = String.valueOf(exchange.getRequestURI().getQuery());
            long minutes;
            try {
                minutes = Long.parseLong(query.replaceFirst(".*minutes=(-?\\d+).*", "$1"));
            } catch (NumberFormatException e) {
                json(exchange, 400, Map.of("error", "Give minutes, e.g. /api/clock/advance?minutes=90"));
                return;
            }
            if (minutes < 0) {
                json(exchange, 400, Map.of("error", "The clock only moves forward"));
                return;
            }
            clock.advance(Duration.ofMinutes(minutes));
            int ran = runner == null ? 0 : runner.runDue();
            json(exchange, 200, Map.of("now", clock.get(), "offsetMinutes", clock.offset().toMinutes(), "ran", ran));
            return;
        }
        json(exchange, 200, Map.of("now", clock.get(), "offsetMinutes", clock.offset().toMinutes()));
    }

    private void deferred(HttpExchange exchange) throws IOException {
        json(exchange, 200, store.all());
    }

    private void landing(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] page;
        try (InputStream in = DeskServer.class.getClassLoader().getResourceAsStream("access-desk/landing.html")) {
            page = Objects.requireNonNull(in, "access-desk/landing.html").readAllBytes();
        }
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().add("Content-Security-Policy",
                "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'");
        exchange.sendResponseHeaders(200, page.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(page);
        }
    }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
