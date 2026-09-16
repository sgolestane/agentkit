package dev.agentkit.examples;

import java.util.List;

/**
 * A minimal web-search seam the {@code web_search} tool calls. Keeping it an
 * interface lets the example run against a live provider ({@link TavilyWebSearch})
 * or offline sample data ({@link SampleWebSearch}), and lets tests substitute a
 * deterministic fake.
 *
 * <p>Implementations signal failure by throwing an unchecked exception; the tool
 * turns that into an error {@code ToolResult} the model can react to.
 */
public interface WebSearch {

    /**
     * Runs {@code query} and returns up to {@code maxResults} ranked results
     * (fewer, or an empty list, when little matches).
     */
    List<Result> search(String query, int maxResults);

    /** One search hit. */
    record Result(String title, String url, String snippet) {
        public Result {
            // Null-tolerant because WebSearch is a seam anyone implements, and a backend
            // that omits a title should cost one blank field rather than the whole result
            // set — which is what it cost once the renderer started flattening these.
            title = title == null ? "" : title;
            url = url == null ? "" : url;
            snippet = snippet == null ? "" : snippet;
        }
    }
}
