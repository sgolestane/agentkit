package dev.agentkit.accessdesk.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentkit.accessdesk.systems.CompanySystems;
import dev.agentkit.accessdesk.tools.ToolCatalog;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The streamable HTTP transport, spoken to the way an MCP client such as Claude Code speaks to it. */
class HttpMcpEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRIYA = "priya.natarajan@acme.example";

    private HttpServer server;
    private URI endpoint;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        ToolCatalog catalog = CompanySystems.open(null).catalog();
        McpServer mcp = new McpServer("access-desk", "0.1.0", "Ask for access.");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", new HttpMcpEndpoint(mcp, user -> PRIYA.equals(user)
                ? Optional.of(catalog) : Optional.empty()));
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void initializeNegotiatesTheProtocolAndIssuesASession() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},
                 "clientInfo":{"name":"test","version":"1"}}}""", PRIYA, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Mcp-Session-Id")).isPresent();
        JsonNode result = MAPPER.readTree(response.body()).path("result");
        assertThat(result.path("protocolVersion").asText()).isEqualTo("2025-03-26");
        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("access-desk");
        assertThat(result.path("capabilities").has("tools")).isTrue();
        assertThat(result.path("instructions").asText()).isEqualTo("Ask for access.");
    }

    @Test
    void aNotificationIsAcceptedWithNoBody() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}""", PRIYA, null);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void toolsAreListedAndCalled() throws Exception {
        JsonNode listed = MAPPER.readTree(post("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}""", PRIYA, null).body());
        assertThat(listed.path("result").path("tools").findValuesAsText("name")).contains("list_resources");

        JsonNode called = MAPPER.readTree(post("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_resources","arguments":{"query":"payments"}}}""",
                PRIYA, null).body());
        assertThat(called.path("result").path("isError").asBoolean()).isFalse();
        assertThat(called.path("result").path("content").get(0).path("text").asText()).contains("db-payments-prod");

        JsonNode unknown = MAPPER.readTree(post("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"nope"}}""", PRIYA, null).body());
        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32602);

        JsonNode method = MAPPER.readTree(post("""
                {"jsonrpc":"2.0","id":5,"method":"resources/list"}""", PRIYA, null).body());
        assertThat(method.path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void anUnknownCallerAForeignOriginAndAGetAreRefused() throws Exception {
        String ping = """
                {"jsonrpc":"2.0","id":6,"method":"ping"}""";
        assertThat(post(ping, null, null).statusCode()).isEqualTo(401);
        assertThat(post(ping, "eve@evil.example", null).statusCode()).isEqualTo(401);
        assertThat(post(ping, PRIYA, "https://evil.example").statusCode()).isEqualTo(403);
        assertThat(post(ping, PRIYA, "http://localhost:3000").statusCode()).isEqualTo(200);

        HttpResponse<String> get = http.send(HttpRequest.newBuilder(endpoint).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(405);
    }

    private HttpResponse<String> post(String body, String user, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (user != null) {
            request.header(HttpMcpEndpoint.USER_HEADER, user);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
