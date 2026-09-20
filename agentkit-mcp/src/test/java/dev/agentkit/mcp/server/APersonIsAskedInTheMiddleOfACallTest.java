package dev.agentkit.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.ToolDeclaration;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.mcp.Elicitor;
import dev.agentkit.mcp.HttpMcpConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A tool served over MCP can need a person's word before it acts — a confirmation, an answer. MCP lets the server ask
 * the client's person directly, mid-call, and wait: the question goes out on the call's event stream, the client
 * shows it to the person and posts their answer back, and the tool goes on with it. A client that cannot be asked is
 * never asked, and a question nobody answers in time is treated as not answered.
 */
class APersonIsAskedInTheMiddleOfACallTest {

    private static final Map<String, Object> YES_OR_NO = Map.of("type", "object",
            "properties", Map.of("approve", Map.of("type", "boolean", "title", "Approve")), "required", List.of("approve"));

    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Answering a question is a second request served while the first waits.
        server.setExecutor(Executors.newCachedThreadPool());
        DeclaredTools tools = new DeclaredTools().add(FunctionTool.builder("reset_mfa", "Reset someone's MFA")
                        .schema(Map.of("type", "object", "properties", Map.of("patience_ms", Map.of("type", "integer"))))
                        .sideEffects(SideEffects.EXTERNAL)
                        .handler(inv -> {
                            Optional<McpCall> call = McpCall.current();
                            if (call.isEmpty()) {
                                return ToolResult.ok("nobody to ask");
                            }
                            Object ms = inv.argument("patience_ms");
                            McpCall.Answer answer = call.get().elicit("Reset MFA for priya@acme.example?", YES_OR_NO,
                                    Duration.ofMillis(ms instanceof Number n ? n.longValue() : 10_000));
                            return ToolResult.ok(switch (answer.action()) {
                                case ACCEPT -> Boolean.TRUE.equals(answer.content().get("approve")) ? "reset" : "not approved";
                                case DECLINE -> "declined";
                                case CANCEL -> "not answered";
                            });
                        })
                        .build(),
                new ToolDeclaration("okta", ToolEffect.GRANT, null));
        server.createContext("/mcp", new HttpMcpEndpoint(new McpServer("test", "1", ""),
                HttpMcpEndpoint.Callers.header("Authorization"),
                caller -> caller.equals("Bearer t") ? Optional.of(tools) : Optional.empty()));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    private HttpMcpConnection.Builder client() {
        return HttpMcpConnection.builder(endpoint()).header("Authorization", "Bearer t");
    }

    @Test
    void thePersonIsAskedAndTheToolGoesOnWithTheirAnswer() {
        List<String> asked = new CopyOnWriteArrayList<>();
        try (HttpMcpConnection connection = client().elicitation((message, schema) -> {
            asked.add(message + " " + schema.get("required"));
            return Elicitor.Reply.accept(Map.of("approve", true));
        }).connect()) {
            assertThat(connection.callTool("reset_mfa", Map.of()).text()).isEqualTo("reset");
            assertThat(asked).containsExactly("Reset MFA for priya@acme.example? [approve]");
        }
    }

    @Test
    void aRefusalIsTheirsToGive() {
        try (HttpMcpConnection connection = client().elicitation((m, s) -> Elicitor.Reply.decline()).connect()) {
            assertThat(connection.callTool("reset_mfa", Map.of()).text()).isEqualTo("declined");
        }
        try (HttpMcpConnection connection = client().elicitation((m, s) -> Elicitor.Reply.accept(Map.of("approve", false)))
                .connect()) {
            assertThat(connection.callTool("reset_mfa", Map.of()).text()).isEqualTo("not approved");
        }
    }

    @Test
    void aQuestionNobodyAnswersInTimeIsNotAnAnswer() {
        try (HttpMcpConnection connection = client().elicitation((m, s) -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Elicitor.Reply.accept(Map.of("approve", true));
        }).connect()) {
            assertThat(connection.callTool("reset_mfa", Map.of("patience_ms", 200)).text()).isEqualTo("not answered");
        }
        try (HttpMcpConnection connection = client().elicitation((m, s) -> {
            throw new IllegalStateException("the person closed the window");
        }).connect()) {
            assertThat(connection.callTool("reset_mfa", Map.of()).text()).isEqualTo("not answered");
        }
    }

    @Test
    void aClientThatCannotBeAskedIsNeverAsked() {
        try (HttpMcpConnection connection = client().connect()) {
            assertThat(connection.callTool("reset_mfa", Map.of()).text()).isEqualTo("nobody to ask");
        }
    }

    @Test
    void aSessionTheEndpointDoesNotKnowIsAnsweredNotFound() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(endpoint())
                .header("Authorization", "Bearer t").header("Mcp-Session-Id", "made-up")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
    }
}
