package dev.agentkit.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.ToolDeclaration;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.mcp.server.HttpMcpEndpoint;
import dev.agentkit.mcp.server.McpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every rule a deployment applies to tools — what a deferred action may use, what a model may be handed at all — is
 * written against declarations. So a connector's tool is declared by the operator's file, by the server's own
 * {@code _meta}, or by a read-only hint from a server the operator trusts; and a tool none of them declare is left
 * out rather than guessed at.
 */
class AToolNobodyDeclaredIsLeftOutTest {

    private static final McpToolInfo SELF_DESCRIBED = new McpToolInfo("grant_access", "", Map.of(),
            Map.of(McpDeclarations.EFFECT, "grant", McpDeclarations.SUBJECT, "email"));
    private static final McpToolInfo HINTED = new McpToolInfo("peek", "", Map.of(), Map.of(),
            new McpToolAnnotations(true, null, null, null));
    private static final McpToolInfo SILENT = new McpToolInfo("mystery", "", Map.of());

    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/declared", new HttpMcpEndpoint(new McpServer("declared", "1", ""),
                HttpMcpEndpoint.Callers.header("Authorization"),
                caller -> caller.equals("Bearer s3cret") ? Optional.of(ExampleTools.catalog()) : Optional.empty()));
        // A third-party server that says nothing about its tools but that one only reads.
        server.createContext("/silent", exchange -> {
            var request = McpMessages.MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            String body = !request.has("id") ? ""
                    : request.path("method").asText().equals("initialize")
                    ? "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"result\":{\"capabilities\":{}}}"
                    : "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"result\":{\"tools\":["
                    + "{\"name\":\"peek\",\"annotations\":{\"readOnlyHint\":true}},{\"name\":\"poke\"},{\"name\":\"notify\"}]}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(body.isEmpty() ? 202 : 200, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theServersOwnDeclarationIsUsedAndTheConnectorNamesTheSystemWhenItDoesNot() {
        assertThat(McpDeclarations.declare("iam", SELF_DESCRIBED, null, false))
                .contains(new ToolDeclaration("iam", ToolEffect.GRANT, "email"));
    }

    @Test
    void theOperatorsDeclarationWinsFieldByField() {
        assertThat(McpDeclarations.declare("iam", SELF_DESCRIBED, new McpDeclarations.Override("request", null, null), false))
                .contains(new ToolDeclaration("iam", ToolEffect.REQUEST, "email"));
        assertThat(McpDeclarations.declare("iam", SILENT, new McpDeclarations.Override("notify", "chat", "to"), false))
                .contains(new ToolDeclaration("chat", ToolEffect.NOTIFY, "to"));
    }

    @Test
    void aReadOnlyHintDeclaresAReadOnlyForATrustedServer() {
        assertThat(McpDeclarations.declare("x", HINTED, null, true)).contains(new ToolDeclaration("x", ToolEffect.READ, null));
        assertThat(McpDeclarations.declare("x", HINTED, null, false)).isEmpty();
        assertThat(McpDeclarations.declare("x", SILENT, null, true)).isEmpty();
    }

    @Test
    void anUnknownEffectDeclaresNothing() {
        McpToolInfo odd = new McpToolInfo("odd", "", Map.of(), Map.of(McpDeclarations.EFFECT, "delete-everything"));
        assertThat(McpDeclarations.declare("x", odd, null, true)).isEmpty();
    }

    @Test
    void aConnectorsFileReachesServersOverHttpWithCredentialsFromPlaceholders() {
        String file = """
                {"servers": [
                  {"name": "desk", "url": "http://127.0.0.1:%1$d/declared",
                   "headers": {"Authorization": "Bearer ${token}"}},
                  {"name": "vendor", "url": "http://127.0.0.1:%1$d/silent", "trustAnnotations": true,
                   "tools": {"notify": {"effect": "notify", "subject": "to_email"}}}
                ]}""".formatted(server.getAddress().getPort());

        try (McpConnectors.Connected connected = McpConnectors.connect(file, Map.of("token", "s3cret"))) {
            DeclaredTools catalog = connected.catalog();

            assertThat(catalog.entries()).extracting(e -> e.tool().name())
                    .containsExactly("list_resources", "grant_access", "send_message", "peek", "notify");
            assertThat(catalog.declaration("grant_access")).contains(new ToolDeclaration("iam", ToolEffect.GRANT, "email"));
            assertThat(catalog.declaration("peek")).contains(new ToolDeclaration("vendor", ToolEffect.READ, null));
            assertThat(catalog.declaration("notify")).contains(new ToolDeclaration("vendor", ToolEffect.NOTIFY, "to_email"));
            // "poke" said nothing, so it is not there to be handed to anyone.
            assertThat(catalog.entry("poke")).isEmpty();

            assertThat(connected.client("desk").callTool("list_resources", Map.of("query", "q")).text())
                    .contains("db-payments-prod");
        }
    }

    @Test
    void aServerNeedsExactlyOneWayToReachIt() {
        assertThatThrownBy(() -> McpConnectors.connect("""
                {"servers": [{"name": "both", "command": ["x"], "url": "http://127.0.0.1:1/mcp"}]}""", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
        assertThatThrownBy(() -> McpConnectors.connect("""
                {"servers": [{"name": "neither"}]}""", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
    }
}
