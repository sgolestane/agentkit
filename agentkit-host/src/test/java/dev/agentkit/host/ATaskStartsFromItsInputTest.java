package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.chat.web.ChatServer;
import dev.agentkit.host.web.DevSignIn;
import dev.agentkit.mcp.HttpMcpConnection;
import dev.agentkit.mcp.McpCallResult;
import dev.agentkit.mcp.McpToolInfo;
import dev.agentkit.mcp.server.HttpMcpEndpoint;
import dev.agentkit.mcp.server.McpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task agent with an input is started from its fields: from a form in the console, or over MCP with
 * {@code run_<agent>}, whose arguments are the form. Either way the input is checked first — with every problem at once
 * — and becomes the request through the agent's goal template. A request in plain words still works, and the agent is
 * told which fields a complete one has.
 */
class ATaskStartsFromItsInputTest {

    private static final String PRIYA = "acme/" + HelpdeskConnector.PRIYA;
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private OrgHost org;
    private ChatRuntime runtime;
    private HttpServer mcpServer;
    private ChatServer console;

    private void start(ScriptedLlm llm) throws Exception {
        helpdesk = new HelpdeskConnector();
        RepoFixture repo = RepoFixture.copyInto(dir)
                .write("agents/replacement/agent.yaml", """
                        name: Laptop Replacement
                        description: Sorts out a lost or stolen laptop.
                        pattern: plan-execute
                        prompt: {planner: planner.md, executor: executor.md}
                        tools: [{connector: helpdesk, effects: [read, request, notify]}]
                        bind: {helpdesk/open_ticket: {requester: principal.email}}
                        input:
                          schema: input.yaml
                          goal: goal.md
                        """)
                .write("agents/replacement/planner.md", "Plan it.")
                .write("agents/replacement/executor.md", "Do one step.")
                .write("agents/replacement/input.yaml", """
                        type: object
                        required: [what_happened, mfa_device_lost]
                        properties:
                          what_happened: {type: string, title: What happened}
                          mfa_device_lost: {type: boolean, title: MFA device lost too}
                          deliver_to: {type: string, enum: [home, office], title: Deliver to}
                        """)
                .write("agents/replacement/goal.md", "Replace my laptop.\n\n{{input}}");
        org = OrgHost.open(repo.root(), AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        HostChat chat = new HostChat(Map.of("acme", org), Optional.of(llm), Instant::now, self::get);
        runtime = new ChatRuntime(new InMemoryChatStore(), new ChatEvents(), chat);
        self.set(runtime);

        HostMcp mcp = new HostMcp(Map.of("acme", org), chat, self::get, id -> "http://console/c/" + id,
                Duration.ofSeconds(20));
        mcpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mcpServer.setExecutor(Executors.newCachedThreadPool());
        mcpServer.createContext("/mcp", new HttpMcpEndpoint(new McpServer("agentkit-host", "1", ""),
                DevSignIn.MCP_CALLERS, mcp::toolsFor));
        mcpServer.start();

        console = new ChatServer(0, runtime, exchange -> Optional.ofNullable(
                exchange.getRequestHeaders().getFirst(DevSignIn.MCP_HEADER)), tenant -> Map.of(), chat, null);
        console.start();
    }

    @AfterEach
    void stop() {
        if (console != null) {
            console.close();
        }
        if (mcpServer != null) {
            mcpServer.stop(0);
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

    @Test
    void overMcpTheFormIsTheToolsArgumentsAndABadOneIsRefusedWithEveryReason() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(ScriptedLlm.text("1. Open a ticket."),
                ScriptedLlm.toolUse("s1", "open_ticket", Map.of("summary", "stolen")), ScriptedLlm.text("Opened TICKET-1001."));
        start(llm);
        try (HttpMcpConnection priya = HttpMcpConnection.builder(URI.create("http://127.0.0.1:"
                + mcpServer.getAddress().getPort() + "/mcp")).header(DevSignIn.MCP_HEADER, PRIYA).connect()) {
            McpToolInfo run = priya.listTools().stream().filter(t -> t.name().equals("run_replacement")).findFirst()
                    .orElseThrow();
            assertThat(run.inputSchema().get("required").toString()).isEqualTo("[what_happened, mfa_device_lost]");

            McpCallResult refused = priya.callTool("run_replacement", Map.of("mfa_device_lost", "sometimes",
                    "deliver_to", "moon"));
            assertThat(refused.isError()).isTrue();
            assertThat(refused.text()).contains("What happened is required").contains("MFA device lost too must be "
                    + "true or false").contains("Deliver to must be one of [home, office]");
            assertThat(llm.received()).isEmpty();

            McpCallResult answer = priya.callTool("run_replacement", Map.of("what_happened", "stolen from my car",
                    "mfa_device_lost", false));
            assertThat(answer.text()).contains("Done: Opened TICKET-1001.");
        }
        String planned = llm.received().get(0).messages().toString();
        assertThat(planned).contains("Replace my laptop.").contains("- what_happened: stolen from my car")
                .contains("- mfa_device_lost: false");
        assertThat(helpdesk.calls("open_ticket")).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("requester", HelpdeskConnector.PRIYA));
    }

    @Test
    void inTheConsoleTheFormIsOfferedAndSubmittedAsTheRequest() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(ScriptedLlm.text("1. Say it is done."), ScriptedLlm.text("Done."));
        start(llm);

        JsonNode agents = get("/api/agents");
        JsonNode replacement = null;
        for (JsonNode agent : agents) {
            if (agent.path("id").asText().equals("replacement")) {
                replacement = agent;
            }
        }
        assertThat(replacement.path("input").path("properties").path("deliver_to").path("enum").toString())
                .isEqualTo("[\"home\",\"office\"]");

        String conversation = post("/api/conversations", "{\"agent\":\"replacement\"}").path("id").asText();
        HttpResponse<String> refused = send("/api/conversations/" + conversation + "/messages",
                "{\"input\":{\"mfa_device_lost\":true}}");
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(refused.body()).path("error").asText()).isEqualTo("What happened is required.");

        JsonNode turn = post("/api/conversations/" + conversation + "/messages",
                "{\"input\":{\"what_happened\":\"left on a train\",\"mfa_device_lost\":true,\"deliver_to\":\"home\"}}");
        assertThat(turn.path("userText").asText()).isEqualTo("""
                Replace my laptop.

                - what_happened: left on a train
                - mfa_device_lost: true
                - deliver_to: home""");

        String helpdeskConversation = post("/api/conversations", "{\"agent\":\"helpdesk\"}").path("id").asText();
        HttpResponse<String> noForm = send("/api/conversations/" + helpdeskConversation + "/messages",
                "{\"input\":{\"x\":1}}");
        assertThat(JSON.readTree(noForm.body()).path("error").asText())
                .isEqualTo("IT Helpdesk takes no form; say what you need instead.");
    }

    @Test
    void aRequestInPlainWordsStillWorksAndThePlannerIsToldWhatACompleteOneHas() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(ScriptedLlm.text("1. Ask whether the MFA device was lost."),
                ScriptedLlm.text("Asked."));
        start(llm);
        String conversation = post("/api/conversations", "{\"agent\":\"replacement\"}").path("id").asText();
        post("/api/conversations/" + conversation + "/messages", "{\"text\":\"my laptop is gone\"}");

        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (llm.received().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(llm.received().get(0).system()).hasValueSatisfying(system -> assertThat(system)
                .contains("works from these fields")
                .contains("- mfa_device_lost (boolean, required)")
                .contains("- deliver_to (string: one of [home, office], optional)"));
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(path))
                .header(DevSignIn.MCP_HEADER, PRIYA).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private JsonNode post(String path, String body) throws Exception {
        HttpResponse<String> response = send(path, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(path))
                .header(DevSignIn.MCP_HEADER, PRIYA).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + console.port() + path);
    }
}
