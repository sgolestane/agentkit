package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentkit.host.web.AdminApi;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The admin view is read-only and for the admins {@code org.yaml} names: the versions the host serves and their
 * agents, what each agent can do — its tools by effect, with what the definition adds to them — and the rehearsals its
 * pull requests reported, which arrive with the organization's token.
 */
class AnAdminSeesWhatEachVersionCanDoTest {

    private static final String SAM = HelpdeskConnector.SAM;
    private static final String PRIYA = HelpdeskConnector.PRIYA;
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private RepoFixture repo;
    private OrgHost org;
    private HttpServer server;
    private final RehearsalLog rehearsals = RehearsalLog.inMemory();

    @BeforeEach
    void start() throws Exception {
        helpdesk = new HelpdeskConnector();
        repo = RepoFixture.copyInto(dir);
        repo.write("org.yaml", repo.read("org.yaml") + "\nadmins: [security]\n");
        repo.commit("one");
        org = OrgHost.open(repo.root(), AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
        AdminApi admin = new AdminApi(Map.of("acme", org), Map.of(),
                exchange -> Optional.ofNullable(exchange.getRequestHeaders().getFirst("X-Who")),
                rehearsals, name -> name.equals("acme") ? Optional.of("report-token") : Optional.empty(),
                () -> Instant.parse("2026-09-18T20:00:00Z"),
                new dev.agentkit.host.change.Proposals(o -> Optional.empty(), name -> AgentHost.Options.hosted(
                        Secrets.of(Map.of("HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN)))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/host/admin", admin.admin());
        server.createContext("/host/rehearsals/", admin.reports());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        org.close();
        helpdesk.close();
    }

    @Test
    void onlyAnAdminSeesIt() throws Exception {
        assertThat(get("/host/admin", null).statusCode()).isEqualTo(401);
        assertThat(get("/host/admin", "acme/" + PRIYA).statusCode()).isEqualTo(403);
        assertThat(get("/host/admin", "globex/" + SAM).statusCode()).isEqualTo(403);
        assertThat(get("/host/admin", "acme/" + SAM).statusCode()).isEqualTo(200);
        assertThat(send("/host/admin", "acme/" + SAM, "POST", "{}").statusCode()).isEqualTo(405);
    }

    @Test
    void itShowsEachVersionServedAndWhatItsAgentsCanDo() throws Exception {
        String v1 = org.current().repo().version();
        String v2 = repo.write("agents/helpdesk/policy.md", "Policy two.").commit("two");
        org.reload();

        JsonNode overview = json(get("/host/admin", "acme/" + SAM));
        assertThat(overview.path("current").asText()).isEqualTo(v2);
        assertThat(overview.path("admins").toString()).isEqualTo("[\"security\"]");
        assertThat(overview.path("versions").findValuesAsText("version")).containsExactly(v2, v1);
        assertThat(overview.path("versions").get(0).path("agents").findValuesAsText("id"))
                .containsExactlyInAnyOrder("helpdesk", "security-desk");
        assertThat(overview.path("connectors").get(0).path("reached").asBoolean()).isTrue();

        JsonNode helpdeskNow = json(get("/host/admin/agents/helpdesk", "acme/" + SAM));
        JsonNode helpdeskThen = json(get("/host/admin/agents/helpdesk?version=" + v1, "acme/" + SAM));
        assertThat(helpdeskNow.path("prompts").path("policy").asText()).isEqualTo("Policy two.");
        assertThat(helpdeskThen.path("prompts").path("policy").asText()).isNotEqualTo("Policy two.");
        assertThat(helpdeskNow.path("pattern").asText()).isEqualTo("chat");

        Map<String, JsonNode> tools = new java.util.HashMap<>();
        helpdeskNow.path("tools").forEach(t -> tools.put(t.path("name").asText(), t));
        assertThat(tools).containsOnlyKeys("directory_lookup", "open_ticket", "reset_mfa", "send_message");
        assertThat(tools.get("reset_mfa").path("effect").asText()).isEqualTo("grant");
        assertThat(tools.get("reset_mfa").path("confirmed").asBoolean()).isTrue();
        assertThat(tools.get("reset_mfa").path("bound").path("email").asText()).isEqualTo("principal.email");
        assertThat(tools.get("open_ticket").path("bound").path("requester").asText()).isEqualTo("principal.email");
        assertThat(tools.get("open_ticket").path("refusedInRehearsal").asBoolean()).isTrue();
        assertThat(tools.get("directory_lookup").path("refusedInRehearsal").asBoolean()).isFalse();
        assertThat(helpdeskNow.path("mcpDirect").toString()).isEqualTo("[\"helpdesk/directory_lookup\"]");

        assertThat(get("/host/admin/agents/nobody", "acme/" + SAM).statusCode()).isEqualTo(404);
        assertThat(json(get("/host/admin/deferred", "acme/" + SAM)).path("agents").isEmpty()).isTrue();
    }

    @Test
    void aPullRequestsRehearsalIsKeptWhenItCarriesTheOrganizationsToken() throws Exception {
        String report = """
                {"org": "acme", "version": "abc", "held": 1, "cases": 1, "title": "Tighten the policy",
                 "results": [{"agent": "helpdesk", "case": "locked-out", "passed": true}]}""";

        assertThat(post("/host/rehearsals/acme", "wrong", report).statusCode()).isEqualTo(401);
        assertThat(post("/host/rehearsals/globex", "report-token", report).statusCode()).isEqualTo(401);
        assertThat(post("/host/rehearsals/acme", "report-token", report.replace("\"acme\"", "\"globex\"")).statusCode())
                .isEqualTo(400);
        assertThat(post("/host/rehearsals/acme", "report-token", "not json").statusCode()).isEqualTo(400);
        assertThat(post("/host/rehearsals/acme", "report-token", report).statusCode()).isEqualTo(202);

        JsonNode kept = json(get("/host/admin/rehearsals", "acme/" + SAM)).path("reports");
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).path("title").asText()).isEqualTo("Tighten the policy");
        assertThat(kept.get(0).path("receivedAt").asText()).isEqualTo("2026-09-18T20:00:00Z");
        assertThat(get("/host/admin/rehearsals", "acme/" + PRIYA).statusCode()).isEqualTo(403);
    }

    // ---------------------------------------------------------------- helpers

    private HttpResponse<String> get(String path, String who) throws Exception {
        return send(path, who, "GET", null);
    }

    private HttpResponse<String> send(String path, String who, String method, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + server.getAddress().getPort() + path)).method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (who != null) {
            request.header("X-Who", who);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + server.getAddress().getPort() + path)).header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }
}
