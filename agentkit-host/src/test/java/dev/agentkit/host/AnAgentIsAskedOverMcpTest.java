package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.host.web.DevSignIn;
import dev.agentkit.mcp.Elicitor;
import dev.agentkit.mcp.HttpMcpConnection;
import dev.agentkit.mcp.McpCallResult;
import dev.agentkit.mcp.McpException;
import dev.agentkit.mcp.McpToolInfo;
import dev.agentkit.mcp.server.HttpMcpEndpoint;
import dev.agentkit.mcp.server.McpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The host as an MCP server: each agent a person may use is {@code ask_<agent>}, a turn with it as them, in their
 * conversation with it — and when the turn needs the person's word, the MCP client's person is asked directly, not its
 * model. A client that cannot be asked is told what is waiting and where to decide it.
 */
class AnAgentIsAskedOverMcpTest {

    private static final String PRIYA = "acme/" + HelpdeskConnector.PRIYA;
    private static final String SAM = "acme/" + HelpdeskConnector.SAM;

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private OrgHost org;
    private ChatRuntime runtime;
    private HttpServer server;

    private void start(ScriptedLlm llm) throws Exception {
        helpdesk = new HelpdeskConnector();
        org = OrgHost.open(RepoFixture.copyInto(dir).root(), AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        HostChat chat = new HostChat(Map.of("acme", org), Optional.of(llm), Instant::now, self::get);
        runtime = new ChatRuntime(new InMemoryChatStore(), new ChatEvents(), chat);
        self.set(runtime);
        HostMcp mcp = new HostMcp(Map.of("acme", org), chat, self::get, id -> "http://console/c/" + id,
                Duration.ofSeconds(20));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/mcp", new HttpMcpEndpoint(new McpServer("agentkit-host", "1", ""), DevSignIn.MCP_CALLERS,
                mcp::toolsFor));
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (runtime != null) {
            runtime.close();
        }
        if (org != null) {
            org.close();
        }
        if (helpdesk != null) {
            helpdesk.close();
        }
    }

    private HttpMcpConnection.Builder as(String tenant) {
        return HttpMcpConnection.builder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"))
                .header(DevSignIn.MCP_HEADER, tenant);
    }

    @Test
    void eachPersonIsOfferedTheAgentsTheyMayUseAndTheirDirectReads() throws Exception {
        start(new ScriptedLlm());
        try (HttpMcpConnection priya = as(PRIYA).connect(); HttpMcpConnection sam = as(SAM).connect()) {
            assertThat(priya.listTools()).extracting(McpToolInfo::name).containsExactly("ask_helpdesk", "directory_lookup");
            assertThat(sam.listTools()).extracting(McpToolInfo::name)
                    .containsExactlyInAnyOrder("ask_helpdesk", "directory_lookup", "ask_security_desk");

            McpCallResult read = priya.callTool("directory_lookup", Map.of("email", HelpdeskConnector.DANA));
            assertThat(read.isError()).isFalse();
            assertThat(read.text()).contains("Engineering Manager").doesNotContain("untrusted");
        }
        assertThatThrownBy(() -> as("acme/stranger@acme.example").connect())
                .isInstanceOf(McpException.class).hasMessageContaining("401");
    }

    @Test
    void askingRunsATurnAsThePersonInTheirConversationWithTheAgent() throws Exception {
        start(new ScriptedLlm(
                ScriptedLlm.toolUse("c1", "open_ticket", Map.of("summary", "laptop dead", "requester", "ceo@acme.example")),
                ScriptedLlm.text("Opened TICKET-1001.")));
        try (HttpMcpConnection priya = as(PRIYA).connect()) {
            McpCallResult answer = priya.callTool("ask_helpdesk", Map.of("message", "my laptop will not start"));

            assertThat(answer.text()).isEqualTo("Opened TICKET-1001.");
            assertThat(helpdesk.calls("open_ticket")).singleElement().satisfies(call ->
                    assertThat(call.arguments()).containsEntry("requester", HelpdeskConnector.PRIYA));
            assertThat(runtime.store().conversations(PRIYA)).singleElement().satisfies(c -> {
                assertThat(c.title()).isEqualTo("IT Helpdesk over MCP");
                assertThat(c.agent().id()).isEqualTo("helpdesk");
            });
        }
    }

    @Test
    void aConfirmationIsAskedOfThePersonAndTheirAnswerDecides() throws Exception {
        start(new ScriptedLlm(
                ScriptedLlm.toolUse("c1", "reset_mfa", Map.of()), ScriptedLlm.text("Your MFA is reset."),
                ScriptedLlm.toolUse("c2", "reset_mfa", Map.of()), ScriptedLlm.text("Understood, I did not reset it.")));
        List<String> asked = new CopyOnWriteArrayList<>();
        try (HttpMcpConnection approves = as(PRIYA).elicitation((message, schema) -> {
            asked.add(message);
            return Elicitor.Reply.accept(Map.of("approve", true));
        }).connect()) {
            assertThat(approves.callTool("ask_helpdesk", Map.of("message", "I lost my phone")).text())
                    .isEqualTo("Your MFA is reset.");
            assertThat(asked).singleElement().satisfies(m -> assertThat(m).startsWith("Allow reset_mfa?"));
            assertThat(helpdesk.calls("reset_mfa")).singleElement().satisfies(call ->
                    assertThat(call.arguments()).containsEntry("email", HelpdeskConnector.PRIYA));
        }
        try (HttpMcpConnection refuses = as(PRIYA).elicitation((m, s) -> Elicitor.Reply.decline()).connect()) {
            assertThat(refuses.callTool("ask_helpdesk", Map.of("message", "reset it again")).text())
                    .isEqualTo("Understood, I did not reset it.");
            assertThat(helpdesk.calls("reset_mfa")).hasSize(1);
        }
    }

    @Test
    void aClientThatCannotBeAskedIsToldWhatIsWaitingAndWhere() throws Exception {
        start(new ScriptedLlm(ScriptedLlm.toolUse("c1", "reset_mfa", Map.of()), ScriptedLlm.text("Reset.")));
        try (HttpMcpConnection priya = as(PRIYA).connect()) {
            long started = System.nanoTime();
            String answer = priya.callTool("ask_helpdesk", Map.of("message", "I lost my phone")).text();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
            Conversation conversation = runtime.store().conversations(PRIYA).get(0);
            assertThat(answer).startsWith("Waiting for your confirmation to run reset_mfa")
                    .contains("http://console/c/" + conversation.id());
            assertThat(runtime.pending(PRIYA)).singleElement()
                    .satisfies(p -> assertThat(p.tool()).isEqualTo("reset_mfa"));
            assertThat(helpdesk.calls("reset_mfa")).isEmpty();
        }
    }
}
