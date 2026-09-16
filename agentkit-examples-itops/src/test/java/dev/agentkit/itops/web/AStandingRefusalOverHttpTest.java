package dev.agentkit.itops.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.memory.InMemoryMemoryStore;
import dev.agentkit.core.reflect.CorrectionBook;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.ServiceNowConnector;
import dev.agentkit.itops.domain.ApprovalRequest;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.llm.ScriptedOpsLlm;
import dev.agentkit.itops.runtime.ExecutionRunner;
import dev.agentkit.itops.runtime.IntakeWorker;
import dev.agentkit.itops.runtime.Reviewers;
import dev.agentkit.itops.store.OpsStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The only production way to install a standing refusal, and the only way to take it back
 * (#329).
 *
 * <p>Both were untested. Replacing the {@code standing} parse in {@code WebServer} with
 * {@code false} left all 187 tests in this module green — the endpoint the README documents
 * could be deleted and nothing noticed. Nothing exercised the reject route at all.
 */
class AStandingRefusalOverHttpTest {

    private static final String TENANT = "acme";
    private static final String CAPABILITY = "identity.lifecycle.write";

    private OpsStore store;
    private CorrectionBook corrections;
    private WebServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        store = new OpsStore();
        corrections = new CorrectionBook(new InMemoryMemoryStore());
        ServiceNowConnector tickets = new ServiceNowConnector();
        ExecutionRunner runner = new ExecutionRunner(store, new ScriptedOpsLlm(), "scripted",
                tickets, new DirectoryConnector(), new IdentityConnector(),
                "agentkit-integration", Reviewers.goalAlignment(), Risk.HIGH, corrections);
        new IntakeWorker(store, tickets, runner, TENANT, "it-ops-agent")
                .tick("AgentKit", Duration.ofHours(1), 10);

        server = new WebServer(0, store, TENANT, tickets, runner, null, null, null, List.of());
        server.start();
        port = server.port();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private String post(String path, String body) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private ApprovalRequest pending(String capability) {
        return store.approvals(TENANT).stream()
                .filter(a -> a.state() == ApprovalRequest.State.PENDING)
                .filter(a -> dev.agentkit.itops.tools.ToolCatalog
                        .policyOrUnknown(a.toolName()).capability().equals(capability))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("rejecting with standing:true installs the gate, and without it does not")
    void theStandingFlagTravelsOverHttp() throws Exception {
        post("/api/approvals/" + pending(CAPABILITY).id() + "/reject",
                "{\"by\":\"sam@example.com\",\"note\":\"never tear down accounts from a ticket\","
                        + "\"standing\":true}");

        assertThat(corrections.standing(TENANT, List.of(CAPABILITY)))
                .as("standing:true did not reach the book")
                .hasSize(1);
        assertThat(corrections.recall(TENANT, List.of(CAPABILITY))).hasSize(1);
    }

    /** The negative: the same request without the flag records advice and no gate. */
    @Test
    @DisplayName("rejecting without the flag records advice and installs no gate")
    void withoutTheFlagNoGateIsInstalled() throws Exception {
        post("/api/approvals/" + pending(CAPABILITY).id() + "/reject",
                "{\"by\":\"sam@example.com\",\"note\":\"wrong person that time\"}");

        assertThat(corrections.standing(TENANT, List.of(CAPABILITY)))
                .as("a routine rejection over HTTP became policy")
                .isEmpty();
        assertThat(corrections.recall(TENANT, List.of(CAPABILITY)))
                .as("the advice was not recorded either")
                .hasSize(1);
    }

    /**
     * And the console can take it back.
     *
     * <p>Every denial says an operator can lift it, and the argument that standing refusals
     * are defensible rests on that being true. It was a Java method with no endpoint, so the
     * remedy was real only for whoever could restart the process.
     */
    @Test
    @DisplayName("an operator can lift a standing refusal from the console")
    void anOperatorCanLiftFromTheConsole() throws Exception {
        post("/api/approvals/" + pending(CAPABILITY).id() + "/reject",
                "{\"by\":\"sam@example.com\",\"note\":\"never from a ticket\",\"standing\":true}");
        assertThat(corrections.standing(TENANT, List.of(CAPABILITY))).hasSize(1);

        String body = post("/api/corrections/" + CAPABILITY + "/lift", "{}");

        assertThat(body).contains("\"lifted\"").contains("1");
        assertThat(corrections.standing(TENANT, List.of(CAPABILITY)))
                .as("the console could not undo what the console installed")
                .isEmpty();
        assertThat(corrections.recall(TENANT, List.of(CAPABILITY)))
                .as("lifting threw the operator's words away")
                .hasSize(1);
    }
}
