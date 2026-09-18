package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentkit.host.change.LocalBranchProposer;
import dev.agentkit.host.change.Proposals;
import dev.agentkit.host.web.AdminApi;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An admin's change to an agent is proposed, not applied: checked the way the host would load it — against the
 * connectors, with every problem — and opened for review on a branch of its own, built on the commit the host runs.
 * What the host serves changes when the branch is merged, and not before.
 */
class AChangeIsProposedForReviewNotAppliedTest {

    private static final String SAM = HelpdeskConnector.SAM;
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private RepoFixture repo;
    private OrgHost org;
    private Proposals proposals;
    private Principal sam;

    @BeforeEach
    void start() throws Exception {
        helpdesk = new HelpdeskConnector();
        repo = RepoFixture.copyInto(dir);
        repo.write("org.yaml", repo.read("org.yaml") + "\nadmins: [security]\n");
        repo.commit("one");
        AgentHost.Options options = AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN)));
        org = OrgHost.open(repo.root(), options);
        proposals = new Proposals(o -> Optional.of(new LocalBranchProposer(o.checkout())), name -> options);
        sam = org.current().principal(SAM).orElseThrow();
    }

    @AfterEach
    void stop() {
        org.close();
        helpdesk.close();
    }

    @Test
    void aChangeTheHostWouldLoadIsOpenedOnABranchAndWhatItDoesIsSaid() {
        String running = org.current().repo().version();
        String agent = repo.read("agents/helpdesk/agent.yaml");

        Proposals.Outcome outcome = proposals.propose(org, sam, "Confirm tickets too", "Tickets cost money.", Map.of(
                "agents/helpdesk/agent.yaml", agent.replace("  - helpdesk/reset_mfa", "  - helpdesk/reset_mfa\n  - helpdesk/open_ticket"),
                "agents/helpdesk/policy.md", "Open a ticket only when asked.",
                "agents/helpdesk/prompts/system.md", repo.read("agents/helpdesk/prompts/system.md")));

        assertThat(outcome.problems()).isEmpty();
        assertThat(outcome.opened()).isTrue();
        assertThat(outcome.branch()).startsWith("agentkit/confirm-tickets-too-");
        assertThat(outcome.where()).startsWith("branches in ");
        assertThat(outcome.summary()).contains("**helpdesk** — files: agent.yaml, policy.md")
                .contains("- can now: request helpdesk/open_ticket, confirmed by the person, with requester ← principal.email")
                .contains("- no longer: request helpdesk/open_ticket, with requester ← principal.email");
        // On the branch, as one commit on the running one; the checkout and what the host serves are untouched.
        assertThat(git("show", outcome.branch() + ":agents/helpdesk/policy.md")).isEqualTo("Open a ticket only when asked.");
        assertThat(git("rev-parse", outcome.branch() + "^").strip()).isEqualTo(running);
        assertThat(git("log", "-1", "--format=%B", outcome.branch())).startsWith("Confirm tickets too\n\n")
                .contains("Proposed in the agent host's admin view by " + SAM).contains("Tickets cost money.");
        assertThat(repo.read("agents/helpdesk/policy.md")).isNotEqualTo("Open a ticket only when asked.");
        assertThat(git("status", "--porcelain")).isBlank();
        assertThat(org.reload()).isEqualTo(running);
    }

    @Test
    void aChangeTheHostWouldRefuseIsRefusedWithEveryProblemAndNothingIsOpened() {
        String agent = repo.read("agents/helpdesk/agent.yaml");

        Proposals.Outcome outcome = proposals.propose(org, sam, "Stop confirming", "", Map.of(
                "agents/helpdesk/agent.yaml", agent.replace("confirm:\n  - helpdesk/reset_mfa\n", "")
                        .replace("limits:", "limts:")));

        assertThat(outcome.opened()).isFalse();
        assertThat(outcome.problems()).anyMatch(p -> p.contains("limts: is not a field here"));
        assertThat(git("branch", "--list", "agentkit/*")).isBlank();

        Proposals.Outcome grants = proposals.propose(org, sam, "Stop confirming", "", Map.of(
                "agents/helpdesk/agent.yaml", agent.replace("confirm:\n  - helpdesk/reset_mfa\n", "")));
        assertThat(grants.problems()).singleElement().asString()
                .contains("helpdesk/reset_mfa grants something, so it must be confirmed");
        assertThat(git("branch", "--list", "agentkit/*")).isBlank();
    }

    @Test
    void onlyAnAgentsOwnFilesMayBeProposedAndSomethingMustChange() {
        assertThat(proposals.propose(org, sam, "Sneaky", "", Map.of("org.yaml", "org: evil\n")).problems())
                .containsExactly("org.yaml: only an agent's own files, agents/<id>/…, may be proposed here");
        assertThat(proposals.propose(org, sam, "Sneaky", "", Map.of(
                "agents/../connectors/helpdesk.yaml", "x", "agents/helpdesk/.env", "x")).problems()).hasSize(2);
        assertThat(proposals.propose(org, sam, "", "", Map.of("agents/helpdesk/policy.md", "x")).problems())
                .containsExactly("A proposal has a title of one line, up to 120 characters.");
        assertThat(proposals.propose(org, sam, "Same", "", Map.of("agents/helpdesk/policy.md",
                repo.read("agents/helpdesk/policy.md"))).problems())
                .containsExactly("Nothing changes: every file is as the running version has it.");
    }

    @Test
    void aHostRunningUncommittedChangesHasNothingToBuildOn() {
        repo.write("agents/helpdesk/policy.md", "Being edited.");
        org.reload();

        Proposals.Availability availability = proposals.availability(org);

        assertThat(availability.enabled()).isFalse();
        assertThat(availability.why()).contains("which is not a commit");
    }

    @Test
    void theAdminViewOffersAnAgentsFilesAndTakesAProposalAsJson() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/host/admin", new AdminApi(Map.of("acme", org), Map.of(),
                exchange -> Optional.ofNullable(exchange.getRequestHeaders().getFirst("X-Who")), RehearsalLog.inMemory(),
                name -> Optional.empty(), Instant::now, proposals).admin());
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/host/admin";
            JsonNode overview = JSON.readTree(send(base, "GET", null, null).body());
            assertThat(overview.path("proposals").path("enabled").asBoolean()).isTrue();

            JsonNode files = JSON.readTree(send(base + "/agents/helpdesk/files", "GET", null, null).body());
            assertThat(files.path("files").findValuesAsText("path")).containsExactly("agents/helpdesk/agent.yaml",
                    "agents/helpdesk/policy.md", "agents/helpdesk/prompts/system.md");

            String proposal = JSON.writeValueAsString(Map.of("title", "Shorter policy", "files",
                    Map.of("agents/helpdesk/policy.md", "Be brief.")));
            assertThat(send(base + "/proposals", "POST", "text/plain", proposal).statusCode()).isEqualTo(415);
            HttpResponse<String> opened = send(base + "/proposals", "POST", "application/json", proposal);
            assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
            assertThat(JSON.readTree(opened.body()).path("branch").asText()).startsWith("agentkit/shorter-policy-");

            HttpResponse<String> refused = send(base + "/proposals", "POST", "application/json",
                    JSON.writeValueAsString(Map.of("title", "Break it", "files", Map.of("agents/helpdesk/agent.yaml", "name: X"))));
            assertThat(refused.statusCode()).isEqualTo(422);
            assertThat(JSON.readTree(refused.body()).path("problems").size()).isGreaterThan(0);
            assertThat(send(base, "POST", "application/json", "{}").statusCode()).isEqualTo(405);
        } finally {
            server.stop(0);
        }
    }

    private HttpResponse<String> send(String url, String method, String type, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).header("X-Who", "acme/" + SAM)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (type != null) {
            request.header("Content-Type", type);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String git(String... args) {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", repo.root().toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return output;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
