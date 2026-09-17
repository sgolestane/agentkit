package dev.agentkit.accessdesk.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.accessdesk.desk.AccessLedger;
import dev.agentkit.accessdesk.desk.CompanyClient;
import dev.agentkit.accessdesk.desk.DeskAgent;
import dev.agentkit.accessdesk.desk.DeskTools;
import dev.agentkit.accessdesk.systems.CompanySystems;
import dev.agentkit.accessdesk.web.DemoClock;
import dev.agentkit.accessdesk.web.DeskServer;
import dev.agentkit.accessdesk.web.McpBridge;
import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.deferred.DeferredActionScheduler;
import dev.agentkit.core.deferred.DeferredActionStore;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.tool.DeclaredTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Access Desk as an MCP server, end to end without a real model: an MCP client posts {@code tools/call} for
 * {@code ask_access_desk} to the desk server, which runs a real chat turn as the caller, whose (scripted) agent
 * grants access through the desk's tools.
 */
class AccessDeskOverMcpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRIYA = "priya.natarajan@acme.example";

    private final CompanySystems systems = CompanySystems.open(null);
    private final CompanyClient company = new CompanyClient(new InProcessMcpConnection(systems.catalog()));
    private final AccessLedger ledger = AccessLedger.open(null);
    private final DemoClock clock = new DemoClock();
    private final DeferredActionStore store = DeferredActionStore.inMemory();
    private final DeferredActionScheduler scheduler = new DeferredActionScheduler(DeskTools.grantSubjects(ledger), store,
            clock, DeskTools::holdings);
    private ChatRuntime runtime;
    private DeskServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        AtomicInteger turn = new AtomicInteger();
        LlmClient scripted = request -> turn.getAndIncrement() == 0
                ? LlmResponse.of(Message.of(Role.ASSISTANT, ProposedCall.of("c1", "grant_low_risk_access", Map.of(
                "resource_id", "datadog-payments", "level", "read", "hours", 2, "justification", "debugging"))),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO)
                : LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of("Granted read on the payments dashboards as GR-1001.")),
                LlmStopReason.END_TURN, TokenUsage.ZERO);
        runtime = new ChatRuntime(new InMemoryChatStore(), new ChatEvents(), session -> {
            DeskTools desk = new DeskTools(session.tenantId(), ledger, company, scheduler, clock);
            DeclaredTools tools = DeskAgent.conversationTools(desk, systems.catalog());
            return session.agent(scripted, tools.registry(), AgentConfig.builder("scripted").systemPrompt("desk").build())
                    .toolGate(DeskAgent.gate(session.approver()))
                    .build();
        });
        McpBridge bridge = new McpBridge(runtime, Set.of(PRIYA), caller -> new DeskTools(caller, ledger, company, scheduler, clock),
                caller -> "http://localhost:8101", Duration.ofSeconds(20));
        server = new DeskServer(0, clock, null, store, List.of(), bridge);
        server.start();
    }

    @AfterEach
    void stop() {
        server.close();
        runtime.close();
    }

    @Test
    void askAccessDeskRunsATurnAsTheCallerAndReturnsItsAnswer() throws Exception {
        JsonNode listed = mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", PRIYA);
        assertThat(listed.path("result").path("tools").findValuesAsText("name"))
                .containsExactlyInAnyOrder("ask_access_desk", "my_access", "my_requests", "pending_approvals");

        JsonNode asked = mcp("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ask_access_desk",
                 "arguments":{"message":"I need read on the payments dashboards for 2 hours to debug"}}}""", PRIYA);
        assertThat(asked.path("result").path("isError").asBoolean()).isFalse();
        assertThat(asked.path("result").path("content").get(0).path("text").asText()).contains("GR-1001");
        assertThat(ledger.grant("GR-1001")).hasValueSatisfying(g -> assertThat(g.email()).isEqualTo(PRIYA));
        // The turn is in Priya's own console, in its own conversation.
        assertThat(runtime.store().conversations(PRIYA)).anyMatch(c -> c.title().equals("Access Desk over MCP"));

        JsonNode access = mcp("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"my_access","arguments":{}}}""", PRIYA);
        assertThat(access.path("result").path("content").get(0).path("text").asText()).contains("GR-1001");
    }

    @Test
    void anUnknownCallerGetsNothing() throws Exception {
        HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", "eve@evil.example");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    private JsonNode mcp(String body, String user) throws Exception {
        HttpResponse<String> response = post(body, user);
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> post(String body, String user) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Content-Type", "application/json").header(HttpMcpEndpoint.USER_HEADER, user)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
