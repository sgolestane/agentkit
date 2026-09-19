package dev.agentkit.host.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.host.repo.OrgRepo;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Opens a change as a pull request on GitHub, through its API: one commit, built on the commit the host runs, on a new
 * branch, and a pull request from it to the repository's base branch. Built on the running commit rather than the
 * branch's head, so a change made while the base moved shows as a conflict to resolve rather than silently undoing what
 * was merged in between.
 */
public final class GitHubProposer implements ChangeProposer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OrgRepo.RepositorySpec repository;
    private final String token;
    private final HttpClient http;

    public GitHubProposer(OrgRepo.RepositorySpec repository, String token) {
        this(repository, token, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    GitHubProposer(OrgRepo.RepositorySpec repository, String token, HttpClient http) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.token = Objects.requireNonNull(token, "token");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public String where() {
        return "pull requests on " + repository.github() + " (into " + repository.base() + ")";
    }

    @Override
    public Opened open(String base, String branch, String title, String body, Map<String, String> files) {
        String repo = "/repos/" + repository.github();
        String baseTree = call("GET", repo + "/git/commits/" + base, null,
                "The commit the host runs, " + base + ", is not on GitHub; push it first.").path("tree").path("sha").asText();

        List<Map<String, Object>> tree = new ArrayList<>();
        files.forEach((path, content) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", repository.path().isEmpty() ? path : repository.path() + "/" + path);
            entry.put("mode", "100644");
            entry.put("type", "blob");
            entry.put("content", content);
            tree.add(entry);
        });
        String newTree = call("POST", repo + "/git/trees", Map.of("base_tree", baseTree, "tree", tree),
                "GitHub would not take the files.").path("sha").asText();
        String commit = call("POST", repo + "/git/commits", Map.of("message", title + "\n\n" + body, "tree", newTree,
                "parents", List.of(base)), "GitHub would not make the commit.").path("sha").asText();
        call("POST", repo + "/git/refs", Map.of("ref", "refs/heads/" + branch, "sha", commit),
                "GitHub would not make the branch " + branch + ".");
        JsonNode pull = call("POST", repo + "/pulls", Map.of("title", title, "head", branch, "base", repository.base(),
                "body", body), "GitHub would not open the pull request.");
        return new Opened(branch, pull.path("html_url").asText());
    }

    private JsonNode call(String method, String path, Object body, String failure) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(repository.api() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Authorization", "Bearer " + token);
            if (body == null) {
                request.GET();
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                String said = "";
                try {
                    said = JSON.readTree(response.body()).path("message").asText("");
                } catch (IOException ignored) {
                    // Not JSON: the status is all there is to say.
                }
                throw new ProposalException(failure + " (GitHub answered " + response.statusCode()
                        + (said.isBlank() ? "" : ": " + said) + ")");
            }
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new ProposalException(failure + " (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProposalException(failure + " (interrupted)");
        }
    }
}
