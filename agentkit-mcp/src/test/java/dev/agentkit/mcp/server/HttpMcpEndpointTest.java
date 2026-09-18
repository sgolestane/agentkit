package dev.agentkit.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.mcp.ExampleTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

/** The streamable HTTP transport, spoken to the way an MCP client such as Claude Code speaks to it. */
class HttpMcpEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER_HEADER = "X-Test-User";
    private static final String PRIYA = "priya.natarajan@acme.example";

    private HttpServer server;
    private URI endpoint;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        DeclaredTools catalog = ExampleTools.catalog();
        McpServer mcp = new McpServer("access-desk", "0.1.0", "Ask for access.");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", new HttpMcpEndpoint(mcp, HttpMcpEndpoint.Callers.header(USER_HEADER), user -> PRIYA.equals(user)
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

    @Test
    void eachToolCarriesItsDeclarationBesideTheStandardHints() throws Exception {
        JsonNode tools = MAPPER.readTree(post("""
                {"jsonrpc":"2.0","id":7,"method":"tools/list"}""", PRIYA, null).body()).path("result").path("tools");

        JsonNode grant = null;
        JsonNode read = null;
        for (JsonNode tool : tools) {
            if (tool.path("name").asText().equals("grant_access")) {
                grant = tool;
            } else if (tool.path("name").asText().equals("list_resources")) {
                read = tool;
            }
        }
        assertThat(grant.path("_meta").path("dev.agentkit/effect").asText()).isEqualTo("grant");
        assertThat(grant.path("_meta").path("dev.agentkit/system").asText()).isEqualTo("iam");
        assertThat(grant.path("_meta").path("dev.agentkit/subject").asText()).isEqualTo("email");
        assertThat(grant.path("annotations").path("readOnlyHint").asBoolean()).isFalse();
        assertThat(read.path("_meta").has("dev.agentkit/subject")).isFalse();
        assertThat(read.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
    }

    @Test
    void theOriginsAllowedCanBeWidenedForADeployedServer() throws Exception {
        server.createContext("/wide", new HttpMcpEndpoint(new McpServer("wide", "1", ""),
                HttpMcpEndpoint.Callers.header(USER_HEADER), user -> Optional.of(ExampleTools.catalog()),
                origin -> origin.equals("https://console.example.com")));
        URI wide = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/wide");
        String ping = """
                {"jsonrpc":"2.0","id":8,"method":"ping"}""";

        assertThat(post(wide, ping, PRIYA, "https://console.example.com").statusCode()).isEqualTo(200);
        assertThat(post(wide, ping, PRIYA, "http://localhost:3000").statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> post(String body, String user, String origin) throws Exception {
        return post(endpoint, body, user, origin);
    }

    private HttpResponse<String> post(URI endpoint, String body, String user, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (user != null) {
            request.header(USER_HEADER, user);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
