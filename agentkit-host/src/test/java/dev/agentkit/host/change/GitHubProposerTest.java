package dev.agentkit.host.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentkit.host.repo.OrgRepo;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * On GitHub a proposal is one commit, built on the commit the host runs, on a new branch, and a pull request from it —
 * the organization's files at the path the repository keeps them, and the token sent as the API expects.
 */
class GitHubProposerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "1111111111111111111111111111111111111111";

    private HttpServer github;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        github.createContext("/", exchange -> {
            String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            calls.add(call + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] in = exchange.getRequestBody().readAllBytes();
            bodies.add(in.length == 0 ? JSON.nullNode() : JSON.readTree(in));
            String answer = switch (call) {
                case "GET /repos/acme/agents/git/commits/" + BASE -> "{\"sha\":\"" + BASE + "\",\"tree\":{\"sha\":\"tree-0\"}}";
                case "POST /repos/acme/agents/git/trees" -> "{\"sha\":\"tree-1\"}";
                case "POST /repos/acme/agents/git/commits" -> "{\"sha\":\"commit-1\"}";
                case "POST /repos/acme/agents/git/refs" -> "{\"ref\":\"refs/heads/x\"}";
                case "POST /repos/acme/agents/pulls" -> "{\"html_url\":\"https://github.com/acme/agents/pull/7\"}";
                default -> null;
            };
            byte[] out = (answer == null ? "{\"message\":\"Not Found\"}" : answer).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer == null ? 404 : 201, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        github.start();
    }

    @AfterEach
    void stop() {
        github.stop(0);
    }

    private GitHubProposer proposer() {
        return new GitHubProposer(new OrgRepo.RepositorySpec("acme/agents", "orgs/acme", "main",
                "http://127.0.0.1:" + github.getAddress().getPort()), "gh-token");
    }

    @Test
    void itIsOneCommitOnTheRunningOneAndAPullRequest() {
        ChangeProposer.Opened opened = proposer().open(BASE, "agentkit/shorter-policy-abc123", "Shorter policy",
                "Proposed by sam.", Map.of("agents/helpdesk/policy.md", "Be brief."));

        assertThat(opened.url()).isEqualTo("https://github.com/acme/agents/pull/7");
        assertThat(calls).extracting(c -> c.replace(" Bearer gh-token", "")).containsExactly(
                "GET /repos/acme/agents/git/commits/" + BASE, "POST /repos/acme/agents/git/trees",
                "POST /repos/acme/agents/git/commits", "POST /repos/acme/agents/git/refs", "POST /repos/acme/agents/pulls");
        assertThat(calls).allMatch(c -> c.endsWith("Bearer gh-token"));
        assertThat(bodies.get(1).path("base_tree").asText()).isEqualTo("tree-0");
        assertThat(bodies.get(1).path("tree").get(0).path("path").asText()).isEqualTo("orgs/acme/agents/helpdesk/policy.md");
        assertThat(bodies.get(1).path("tree").get(0).path("content").asText()).isEqualTo("Be brief.");
        assertThat(bodies.get(2).path("parents").toString()).isEqualTo("[\"" + BASE + "\"]");
        assertThat(bodies.get(3).path("ref").asText()).isEqualTo("refs/heads/agentkit/shorter-policy-abc123");
        assertThat(bodies.get(4).path("base").asText()).isEqualTo("main");
        assertThat(bodies.get(4).path("head").asText()).isEqualTo("agentkit/shorter-policy-abc123");
        assertThat(proposer().where()).isEqualTo("pull requests on acme/agents (into main)");
    }

    @Test
    void aRunningCommitGitHubDoesNotHaveIsSaidPlainly() {
        assertThatThrownBy(() -> proposer().open("2222222222222222222222222222222222222222", "b", "t", "b",
                Map.of("agents/helpdesk/policy.md", "x")))
                .isInstanceOf(ChangeProposer.ProposalException.class)
                .hasMessage("The commit the host runs, 2222222222222222222222222222222222222222, is not on GitHub; "
                        + "push it first. (GitHub answered 404: Not Found)");
    }
}
