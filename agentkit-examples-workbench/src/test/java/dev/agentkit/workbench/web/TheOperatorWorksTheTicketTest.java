package dev.agentkit.workbench.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.workbench.connector.HttpTransport;
import dev.agentkit.workbench.connector.JiraClient;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.sim.JiraSimulator;
import dev.agentkit.workbench.store.WorkbenchStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The operator's own hands, over HTTP and through to the ALM.
 *
 * <p>Wired with <strong>no workbench and no model</strong>, which is the property worth
 * pinning: a person working a ticket by hand needs neither, and the console should not
 * stop being useful because none is configured. The ticket the agent cannot handle is exactly
 * where this matters.
 */
class TheOperatorWorksTheTicketTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JiraSimulator simulator;
    private JiraClient jira;
    private WorkbenchStore store;
    private WebServer web;

    @BeforeEach
    void boot() throws Exception {
        simulator = JiraSimulator.empty(0);
        simulator.seedIssue("SIM-99", "Laptop will not charge",
                "Neither charger works.", "Medium", "Ana Silva");
        simulator.start();
        jira = new JiraClient(HttpTransport.overTheWire(), simulator.baseUrl(),
                "operator@example.com", "token", null);
        store = new WorkbenchStore();
        web = new WebServer(0, store, "default", "", jira, null, null,
                new Learnings(MemoryStore.inMemory(), "default"), null);
        web.start();
    }

    @AfterEach
    void shutDown() {
        web.close();
        simulator.close();
    }

    @Test
    void theOperatorCommentsAndResolvesWithoutAModelOrAnApproval() throws Exception {
        // The pane offers the moves the ALM will actually accept.
        JsonNode before = get("/api/tickets/SIM-99");
        assertThat(before.path("transitions").toString()).contains("Done");

        JsonNode commented = post("/api/tickets/SIM-99/comment",
                "{\"by\":\"sid@example.com\",\"body\":\"Swapped the charger — sorted.\"}");
        assertThat(commented.path("kind").asText()).isEqualTo("COMMENT");

        JsonNode moved = post("/api/tickets/SIM-99/transition",
                "{\"by\":\"sid@example.com\",\"transition\":\"Done\"}");
        assertThat(moved.path("kind").asText()).isEqualTo("TRANSITION");

        // Both reached the system of record, not just the workbench's own memory.
        assertThat(jira.comments("SIM-99"))
                .anyMatch(comment -> comment.body().contains("Swapped the charger"));
        assertThat(jira.ticket("SIM-99").orElseThrow().status()).isEqualTo("Done");

        // And the trail says a person did it — the question a merged history cannot answer.
        assertThat(store.operatorActions("default", "SIM-99"))
                .hasSize(2)
                .allMatch(action -> action.by().equals("sid@example.com"));
        JsonNode after = get("/api/tickets/SIM-99");
        assertThat(after.path("operatorActions").size()).isEqualTo(2);
        // No run was invented for work a person did on their own authority.
        assertThat(store.runs("default")).isEmpty();
    }

    @Test
    void anEmptyCommentIsRefusedRatherThanPosted() throws Exception {
        assertThat(post("/api/tickets/SIM-99/comment", "{\"body\":\"   \"}")
                .path("error").asText()).contains("something to say");
        assertThat(jira.comments("SIM-99")).isEmpty();
    }

    private JsonNode get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private JsonNode post(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + web.port() + path);
    }

    private static JsonNode send(HttpRequest.Builder request) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(response.body());
    }
}
