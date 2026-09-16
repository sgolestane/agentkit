package dev.agentkit.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A live {@link WebSearch} backed by the <a href="https://tavily.com">Tavily</a>
 * search API — a search service aimed at LLM agents that returns clean,
 * snippet-sized results. Set {@code TAVILY_API_KEY} to use it.
 *
 * <p>Only the JDK HTTP client and Jackson are used, so there is no vendor SDK to
 * pull in. Swap this class for any other provider by implementing
 * {@link WebSearch} the same way.
 */
public final class TavilyWebSearch implements WebSearch {

    private static final URI ENDPOINT = URI.create("https://api.tavily.com/search");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;

    public TavilyWebSearch(String apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
    }

    /** Reads {@code TAVILY_API_KEY} from the environment. */
    public static TavilyWebSearch fromEnv() {
        String key = System.getenv("TAVILY_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("TAVILY_API_KEY is not set");
        }
        return new TavilyWebSearch(key.strip());
    }

    @Override
    public List<Result> search(String query, int maxResults) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("api_key", apiKey);
            body.put("query", query);
            body.put("max_results", Math.max(1, maxResults));
            body.put("include_answer", false);

            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Tavily HTTP " + response.statusCode() + ": " + response.body());
            }

            List<Result> results = new ArrayList<>();
            for (JsonNode node : mapper.readTree(response.body()).path("results")) {
                results.add(new Result(
                        node.path("title").asText(""),
                        node.path("url").asText(""),
                        node.path("content").asText("")));
            }
            return results;
        } catch (IOException e) {
            throw new IllegalStateException("Tavily request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Tavily request interrupted", e);
        }
    }
}
