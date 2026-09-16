package dev.agentkit.core.knowledge;

import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exposes a {@link KnowledgeBase} to the agent as a {@code knowledge_search} tool.
 */
public final class KnowledgeTools {

    public static final String KNOWLEDGE_SEARCH = "knowledge_search";

    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_RESULTS_CAP = 25;
    /** Per-passage character budget, so a large custom chunk cannot flood the context. */
    private static final int MAX_PASSAGE_CHARS = 1000;

    private KnowledgeTools() {
    }

    /** A {@code knowledge_search} tool over {@code knowledgeBase}. */
    public static Tool knowledgeSearchTool(KnowledgeBase knowledgeBase) {
        Objects.requireNonNull(knowledgeBase, "knowledgeBase");
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string",
                                "description", "The information need to search for"),
                        "max_results", Map.of("type", "integer",
                                "description", "Maximum number of results",
                                "default", DEFAULT_MAX_RESULTS,
                                "minimum", 1,
                                "maximum", MAX_RESULTS_CAP)),
                "required", List.of("query"));
        return FunctionTool.builder(KNOWLEDGE_SEARCH,
                        "Search the knowledge base for information relevant to a query and return "
                                + "the most relevant passages with their source ids.")
                .schema(schema)
                .readOnly()
                // Whatever was indexed, which is the point of an index.
                .provenance(Provenance.THIRD_PARTY)
                .handler(inv -> search(knowledgeBase, inv))
                .build();
    }

    private static ToolResult search(KnowledgeBase kb, ToolInvocation inv) {
        String query = inv.stringArgument("query");
        if (query == null || query.isBlank()) {
            return ToolResult.error("The 'query' argument is required.");
        }
        int maxResults = clampMaxResults(inv.argument("max_results"));
        List<SearchResult> results = kb.search(query, maxResults);
        if (results.isEmpty()) {
            return ToolResult.ok("No results found for \"" + query + "\".");
        }
        StringBuilder sb = new StringBuilder("Found ").append(results.size()).append(" passage(s):\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            // Citation and passage inside one fence. The first attempt at this kept the
            // citation outside on the grounds that it was ours — but only the brackets
            // are: the id, title and url all come from the ingested document, so a
            // hostile one could put a closing marker, or a second convincing citation,
            // in the region the model had been told was framework-authored.
            //
            // The label is an ordinal, not the document id, for the same reason one step
            // further out: a label sits on the marker line rather than inside the fence,
            // so deriving it from the document would hand a hostile one a sentence of
            // unfenced prose next to our own markup. The real id is in the citation,
            // inside the fence, where the model is told what it is worth.
            sb.append('\n').append(Spotlight.wrap(Source.of("knowledge-result-" + (i + 1)),
                    citation(result) + "\n" + truncate(result.chunk().text().strip()))).append('\n');
        }
        return ToolResult.ok(sb.toString().stripTrailing());
    }

    private static String citation(SearchResult result) {
        Map<String, Object> meta = result.chunk().metadata();
        StringBuilder cite = new StringBuilder("[").append(result.chunk().id()).append("]");
        Object title = meta.get("title");
        Object url = meta.get("url");
        if (title != null) {
            cite.append(" \"").append(title).append('"');
        }
        if (url != null) {
            cite.append(" <").append(url).append('>');
        }
        cite.append(" (score ").append(String.format(java.util.Locale.ROOT, "%.3f", result.score())).append(')');
        return cite.toString();
    }

    /**
     * A retrieved passage, cut to the per-passage budget.
     *
     * <p>Measured on what the fence will emit, and cut by {@link Cut}. A passage comes from
     * an ingested document, so it is as astral and as marker-shaped as whoever wrote that
     * document chose: a bare {@code substring} at a fixed index splits a surrogate pair, and
     * a budget measured before the fence is not a budget, since NFKC and marker removal both
     * expand what they are given.
     */
    private static String truncate(String text) {
        return Cut.to(Spotlight.sizedAsFenced(text), MAX_PASSAGE_CHARS);
    }

    private static int clampMaxResults(Object raw) {
        int requested = DEFAULT_MAX_RESULTS;
        if (raw instanceof Number n) {
            requested = n.intValue();
        } else if (raw instanceof String s) {
            try {
                requested = Integer.parseInt(s.strip());
            } catch (NumberFormatException ignored) {
                requested = DEFAULT_MAX_RESULTS;
            }
        }
        if (requested < 1) {
            return DEFAULT_MAX_RESULTS;
        }
        return Math.min(requested, MAX_RESULTS_CAP);
    }
}
