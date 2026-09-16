package dev.agentkit.core.knowledge;

import java.util.Collection;
import java.util.List;

/**
 * A pluggable retrieval strategy over a growing set of {@link Chunk chunks}.
 *
 * <p>The framework supplies a lexical (BM25) and a vector implementation; callers
 * can provide their own (e.g. a hybrid or an external vector database) behind this
 * interface. The {@link KnowledgeBase} owns chunking and delegates ranking here.
 */
public interface Retriever {

    /** Adds chunks to the retrievable set. */
    void add(Collection<Chunk> chunks);

    /** Returns up to {@code maxResults} chunks ranked most-relevant first. */
    List<SearchResult> search(String query, int maxResults);

    /** The number of chunks currently indexed. */
    int size();
}
