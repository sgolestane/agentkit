package dev.agentkit.core.knowledge;

import java.util.List;
import java.util.Objects;

/**
 * A {@link KnowledgeBase} that chunks with a {@link Chunker} and ranks with a
 * {@link Retriever}, both pluggable. In-memory: ingest during setup, then share
 * read-only for the run.
 *
 * <p><strong>Sharing it read-only for the run means what it says</strong> — several
 * threads may {@link #search} one instance at once. That is the ordinary wiring, since
 * {@code KnowledgeTools.knowledgeSearchTool(kb)} builds one tool over one base and
 * {@code Supervisor.fanOut} and {@code AgentGraph} run over a shared registry. Both
 * retrievers this class ships honour it: {@link Bm25Retriever} guards the lazy rebuild
 * its first search performs, and {@link VectorRetriever} touches no state of its own in
 * {@code search} — though it does call your {@link EmbeddingModel} there, so for
 * {@link #vector(EmbeddingModel)} the promise is only as good as that model's own
 * thread-safety. A third-party {@link Retriever} must make its own {@code search} safe
 * to call concurrently.
 *
 * <p><strong>Ingestion is not concurrent.</strong> {@link #ingest} mutates, and neither
 * shipped retriever guards {@code add}. Finish ingesting before sharing the instance.
 *
 * <p>This class previously said only "In-memory and not thread-safe: ingest during
 * setup, then share read-only for the run." The second half was not achievable with
 * {@link #bm25()} as it stood, because that retriever's first search still wrote; the
 * retriever has since been fixed rather than the sentence weakened.
 */
public final class InMemoryKnowledgeBase implements KnowledgeBase {

    /** A sensible default chunker: ~120-word windows overlapping by 20 words. */
    public static final Chunker DEFAULT_CHUNKER = Chunkers.slidingWindow(120, 20);

    private final Chunker chunker;
    private final Retriever retriever;

    public InMemoryKnowledgeBase(Chunker chunker, Retriever retriever) {
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.retriever = Objects.requireNonNull(retriever, "retriever");
    }

    /**
     * A BM25 (lexical) knowledge base with the default chunker. Good for exact-term,
     * keyword, and code-like queries, and needs no embedding model. The index is built
     * on the first search rather than during ingestion, which keeps batch ingestion
     * linear; that rebuild is guarded, so the first search may be a concurrent one.
     */
    public static KnowledgeBase bm25() {
        return new InMemoryKnowledgeBase(DEFAULT_CHUNKER, new Bm25Retriever());
    }

    /** A vector (semantic) knowledge base with the default chunker. */
    public static KnowledgeBase vector(EmbeddingModel model) {
        return new InMemoryKnowledgeBase(DEFAULT_CHUNKER, new VectorRetriever(model));
    }

    @Override
    public void ingest(Document document) {
        Objects.requireNonNull(document, "document");
        retriever.add(chunker.chunk(document));
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        Objects.requireNonNull(query, "query");
        return retriever.search(query, maxResults);
    }

    @Override
    public int chunkCount() {
        return retriever.size();
    }
}
