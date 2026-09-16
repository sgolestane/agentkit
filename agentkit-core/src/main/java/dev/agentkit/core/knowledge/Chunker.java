package dev.agentkit.core.knowledge;

import java.util.List;

/**
 * Splits a {@link Document} into retrievable {@link Chunk chunks}.
 *
 * <p>Chunking granularity is a key retrieval-quality lever: chunks that are too
 * large dilute relevance, too small lose context. Implementations are pure
 * functions of the document.
 */
@FunctionalInterface
public interface Chunker {

    List<Chunk> chunk(Document document);
}
