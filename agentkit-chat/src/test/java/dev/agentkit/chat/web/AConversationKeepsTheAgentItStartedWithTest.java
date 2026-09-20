package dev.agentkit.chat.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.ChatUnavailable;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.store.FileChatStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One console, many people, several agents: each request is someone's, each sees only their own conversations, and
 * each conversation is pinned when it starts to the agent and the version it was started with — through a restart.
 */
class AConversationKeepsTheAgentItStartedWithTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WHO = "X-Test-Tenant";

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private ChatRuntime runtime;
    private ChatServer server;

    /** Two agents; the helpdesk at version v7, and the security desk only for the security team. */
    private final ChatServer.AgentCatalog catalog = new ChatServer.AgentCatalog() {
        @Override
        public List<Map<String, Object>> available(String tenant) {
            return tenant.startsWith("acme/sec")
                    ? List.of(agent("helpdesk", "IT Helpdesk"), agent("security-desk", "Security Desk"))
                    : List.of(agent("helpdesk", "IT Helpdesk"));
        }

        @Override
        public Conversation.Pin pin(String tenant, String agentId) {
            if (agentId == null) {
                throw new ChatUnavailable("Choose which agent to talk to.");
            }
            if (available(tenant).stream().noneMatch(a -> a.get("id").equals(agentId))) {
                throw new ChatUnavailable("There is no agent " + agentId + " for you.");
            }
            return new Conversation.Pin(agentId, "v7");
        }
    };

    @BeforeEach
    void start() throws Exception {
        open();
    }

    private void open() throws Exception {
        runtime = new ChatRuntime(new FileChatStore(dir), new ChatEvents(), session -> {
            throw new ChatUnavailable("not in this test");
        });
        server = new ChatServer(0, runtime,
                exchange -> Optional.ofNullable(exchange.getRequestHeaders().getFirst(WHO)),
                tenant -> Map.of("user", tenant), catalog, null);
        server.start();
    }

    @AfterEach
    void stop() {
        server.close();
        runtime.close();
    }

    @Test
    void aConversationIsPinnedToTheAgentChosenAndSaysSo() throws Exception {
        JsonNode made = json(send("POST", "/api/conversations", "acme/priya", "{\"agent\":\"helpdesk\"}"), 200);
        assertThat(made.path("agent").path("id").asText()).isEqualTo("helpdesk");
        assertThat(made.path("agent").path("version").asText()).isEqualTo("v7");

        JsonNode listed = json(send("GET", "/api/conversations", "acme/priya", null), 200);
        assertThat(listed.get(0).path("agent").path("id").asText()).isEqualTo("helpdesk");

        JsonNode overview = json(send("GET", "/api/overview", "acme/priya", null), 200);
        assertThat(overview.path("tenant").asText()).isEqualTo("acme/priya");
        assertThat(overview.path("user").asText()).isEqualTo("acme/priya");
        assertThat(overview.path("agents").findValuesAsText("id")).containsExactly("helpdesk");
    }

    @Test
    void anAgentSomeoneMayNotUseOrNoChoiceIsRefusedInASentence() throws Exception {
        HttpResponse<String> refused = send("POST", "/api/conversations", "acme/priya", "{\"agent\":\"security-desk\"}");
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(json(refused, 409).path("error").asText()).isEqualTo("There is no agent security-desk for you.");

        assertThat(json(send("POST", "/api/conversations", "acme/priya", "{}"), 409).path("error").asText())
                .isEqualTo("Choose which agent to talk to.");

        assertThat(json(send("POST", "/api/conversations", "acme/sec-sam", "{\"agent\":\"security-desk\"}"), 200)
                .path("agent").path("id").asText()).isEqualTo("security-desk");
    }

    @Test
    void eachPersonSeesOnlyTheirOwnAndNobodyIsNobody() throws Exception {
        String id = json(send("POST", "/api/conversations", "acme/priya", "{\"agent\":\"helpdesk\"}"), 200)
                .path("id").asText();

        assertThat(json(send("GET", "/api/conversations", "acme/dana", null), 200)).isEmpty();
        assertThat(send("GET", "/api/conversations/" + id, "acme/dana", null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/conversations", null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/", null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void aPinSurvivesARestart() throws Exception {
        String id = json(send("POST", "/api/conversations", "acme/priya", "{\"agent\":\"helpdesk\"}"), 200)
                .path("id").asText();
        stop();
        open();

        assertThat(runtime.store().conversation("acme/priya", id))
                .hasValueSatisfying(c -> assertThat(c.agent()).isEqualTo(new Conversation.Pin("helpdesk", "v7")));
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> agent(String id, String name) {
        return Map.of("id", id, "name", name, "description", "");
    }

    private HttpResponse<String> send(String method, String path, String tenant, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (tenant != null) {
            request.header(WHO, tenant);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }
}
