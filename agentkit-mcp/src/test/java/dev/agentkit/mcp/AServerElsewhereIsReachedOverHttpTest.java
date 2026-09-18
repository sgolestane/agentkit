package dev.agentkit.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.agentkit.core.tool.ToolDeclaration;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.mcp.server.HttpMcpEndpoint;
import dev.agentkit.mcp.server.McpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A connector a customer runs somewhere else is reached over streamable HTTP, and has to be read exactly as a
 * subprocess would be: the same tools, the same declarations, the same results — whether the server answers with
 * one JSON body or with an event stream, and across a session it forgets.
 */
class AServerElsewhereIsReachedOverHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<Map<String, String>> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpMcpEndpoint endpoint = new HttpMcpEndpoint(new McpServer("example", "1", ""),
                HttpMcpEndpoint.Callers.header("Authorization"),
                caller -> caller.equals("Bearer good") ? Optional.of(ExampleTools.catalog()) : Optional.empty());
        server.createContext("/mcp", recording(endpoint));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void toolsAreListedWithTheirDeclarationsAndCalled() {
        try (HttpMcpConnection connection = HttpMcpConnection.builder(uri("/mcp"))
                .header("Authorization", "Bearer good").connect()) {
            List<McpToolInfo> tools = connection.listTools();

            assertThat(tools).extracting(McpToolInfo::name).containsExactly("list_resources", "grant_access", "send_message");
            McpToolInfo grant = tools.get(1);
            assertThat(McpDeclarations.declare("iam-server", grant, null, false))
                    .contains(new ToolDeclaration("iam", ToolEffect.GRANT, "email"));

            McpCallResult found = connection.callTool("list_resources", Map.of("query", "payments"));
            assertThat(found.isError()).isFalse();
            assertThat(found.text()).contains("db-payments-prod").contains("payments");

            McpCallResult refused = connection.callTool("send_message", Map.of("to_email", "x@example.com"));
            assertThat(refused.isError()).isTrue();
            assertThat(refused.text()).contains("nobody called x@example.com");

            assertThatThrownBy(() -> connection.callTool("nope", Map.of()))
                    .isInstanceOf(McpException.class).hasMessageContaining("-32602");
        }
    }

    @Test
    void theSessionAndProtocolVersionTravelOnEveryRequestAfterInitialize() {
        try (HttpMcpConnection connection = HttpMcpConnection.builder(uri("/mcp"))
                .header("Authorization", "Bearer good").connect()) {
            connection.listTools();

            String session = connection.sessionId().orElseThrow();
            assertThat(seen.get(0)).doesNotContainKeys("mcp-session-id", "mcp-protocol-version");
            for (Map<String, String> later : seen.subList(1, seen.size())) {
                assertThat(later).containsEntry("mcp-session-id", session).containsEntry("mcp-protocol-version", "2025-06-18");
            }
        }
        // Closing ends the session on the server.
        assertThat(seen.get(seen.size() - 1)).containsEntry("method", "DELETE");
    }

    @Test
    void credentialsAreAskedForOnEveryRequestSoARefreshedOneIsUsed() {
        AtomicInteger asked = new AtomicInteger();
        try (HttpMcpConnection connection = HttpMcpConnection.builder(uri("/mcp"))
                .headers(() -> {
                    asked.incrementAndGet();
                    return Map.of("Authorization", "Bearer good");
                }).connect()) {
            connection.listTools();
            connection.listTools();
        }
        assertThat(asked.get()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void aRefusedCallerIsAnErrorThatSaysWhat() {
        assertThatThrownBy(() -> HttpMcpConnection.builder(uri("/mcp")).header("Authorization", "Bearer bad").connect())
                .isInstanceOf(McpException.class).hasMessageContaining("401").hasMessageContaining("Unknown caller");
    }

    @Test
    void anEventStreamAnswerIsReadPastTheNotificationsBeforeIt() {
        server.createContext("/sse", recording(new StreamingServer()));
        try (HttpMcpConnection connection = HttpMcpConnection.connect(uri("/sse"))) {
            McpCallResult result = connection.callTool("echo", Map.of("text", "hi"));

            assertThat(result.isError()).isFalse();
            assertThat(result.text()).isEqualTo("line one\nline two: hi");
        }
    }

    @Test
    void aSessionTheServerForgotIsStartedAgainAndTheRequestRetried() {
        ForgetfulServer forgetful = new ForgetfulServer();
        server.createContext("/forgetful", recording(forgetful));
        try (HttpMcpConnection connection = HttpMcpConnection.connect(uri("/forgetful"))) {
            String first = connection.sessionId().orElseThrow();
            forgetful.forget();

            assertThat(connection.listTools()).extracting(McpToolInfo::name).containsExactly("only");
            assertThat(connection.sessionId()).isPresent().get().isNotEqualTo(first);
            assertThat(forgetful.initializations.get()).isEqualTo(2);
        }
    }

    @Test
    void aListingSpreadOverPagesIsReadWhole() {
        server.createContext("/paged", recording(new PagedServer()));
        try (HttpMcpConnection connection = HttpMcpConnection.connect(uri("/paged"))) {
            assertThat(connection.listTools()).extracting(McpToolInfo::name).containsExactly("a", "b", "c");
        }
    }

    @Test
    void anAnswerLargerThanTheLimitIsRefusedRatherThanRead() {
        server.createContext("/big", recording(new StreamingServer()));
        try (HttpMcpConnection connection = HttpMcpConnection.builder(uri("/big")).maxMessageBytes(4096).connect()) {
            assertThatThrownBy(() -> connection.callTool("huge", Map.of()))
                    .isInstanceOf(McpException.class).hasMessageContaining("exceeded 4096");
        }
    }

    @Test
    void onlyHttpEndpointsAreAccepted() {
        assertThatThrownBy(() -> HttpMcpConnection.builder(URI.create("file:///etc/passwd")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- fixtures

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private HttpHandler recording(HttpHandler handler) {
        return exchange -> {
            Map<String, String> headers = new java.util.TreeMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
            headers.put("method", exchange.getRequestMethod());
            seen.add(headers);
            handler.handle(exchange);
        };
    }

    private static JsonNode body(HttpExchange exchange) throws IOException {
        return MAPPER.readTree(exchange.getRequestBody().readAllBytes());
    }

    private static void reply(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String result(JsonNode request, String result) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id") + ",\"result\":" + result + "}";
    }

    /** Answers calls with an event stream: a log notification, a server request, then the response in two data lines. */
    private static final class StreamingServer implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            JsonNode request = body(exchange);
            String method = request.path("method").asText();
            if (!request.has("id")) {
                reply(exchange, 202, null, "");
                return;
            }
            if (method.equals("initialize")) {
                reply(exchange, 200, "application/json", result(request, "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}"));
                return;
            }
            if (request.path("params").path("name").asText().equals("huge")) {
                reply(exchange, 200, "text/event-stream", "data: " + "x".repeat(10_000) + "\n\n");
                return;
            }
            String text = request.path("params").path("arguments").path("text").asText();
            String response = result(request, "{\"content\":[{\"type\":\"text\",\"text\":\"line one\\nline two: " + text
                    + "\"}],\"isError\":false}");
            int split = response.indexOf("\"content\"");
            String stream = ": keep-alive\n\n"
                    + "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{}}\n\n"
                    + "data: {\"jsonrpc\":\"2.0\",\"id\":\"srv-1\",\"method\":\"ping\"}\n\n"
                    + "id: 7\ndata: " + response.substring(0, split) + "\r\ndata: " + response.substring(split) + "\n\n";
            reply(exchange, 200, "text/event-stream", stream);
        }
    }

    /** Issues a session on initialize and answers 404 to the old one once told to forget it. */
    private static final class ForgetfulServer implements HttpHandler {
        final AtomicInteger initializations = new AtomicInteger();
        private volatile String session;

        void forget() {
            session = "forgotten";
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (exchange.getRequestMethod().equals("DELETE")) {
                reply(exchange, 204, null, "");
                return;
            }
            JsonNode request = body(exchange);
            String method = request.path("method").asText();
            if (method.equals("initialize")) {
                session = "s-" + initializations.incrementAndGet();
                exchange.getResponseHeaders().add("Mcp-Session-Id", session);
                reply(exchange, 200, "application/json", result(request, "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}"));
                return;
            }
            if (!session.equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))) {
                reply(exchange, 404, null, "");
                return;
            }
            if (!request.has("id")) {
                reply(exchange, 202, null, "");
                return;
            }
            reply(exchange, 200, "application/json", result(request, "{\"tools\":[{\"name\":\"only\",\"inputSchema\":{}}]}"));
        }
    }

    /** Lists three tools over three pages. */
    private static final class PagedServer implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            JsonNode request = body(exchange);
            if (!request.has("id")) {
                reply(exchange, 202, null, "");
                return;
            }
            if (request.path("method").asText().equals("initialize")) {
                reply(exchange, 200, "application/json", result(request, "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}"));
                return;
            }
            String cursor = request.path("params").path("cursor").asText("");
            String page = switch (cursor) {
                case "" -> "{\"tools\":[{\"name\":\"a\"}],\"nextCursor\":\"p2\"}";
                case "p2" -> "{\"tools\":[{\"name\":\"b\"}],\"nextCursor\":\"p3\"}";
                default -> "{\"tools\":[{\"name\":\"c\"}]}";
            };
            reply(exchange, 200, "application/json", result(request, page));
        }
    }
}
