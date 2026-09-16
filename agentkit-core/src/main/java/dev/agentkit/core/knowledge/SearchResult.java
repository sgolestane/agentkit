package dev.agentkit.core.knowledge;

import java.util.Objects;

/**
 * A scored retrieval hit.
 *
 * @param chunk the retrieved chunk; never {@code null}
 * @param score the retriever's relevance score (higher is more relevant); the
 *              scale is retriever-specific and only meaningful for ranking
 */
public record SearchResult(Chunk chunk, double score) {

    public SearchResult {
        Objects.requireNonNull(chunk, "chunk");
    }
}
