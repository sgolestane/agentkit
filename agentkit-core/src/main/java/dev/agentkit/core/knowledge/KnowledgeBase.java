package dev.agentkit.core.knowledge;

import java.util.Collection;
import java.util.List;

/**
 * The agent's grounding in application-owned information: ingest documents, then
 * retrieve the most relevant chunks for a query.
 *
 * <p>AgentKit supplies the <em>mechanism</em> — chunking, lexical and vector
 * retrievers, and a search tool — while the application supplies the data (and,
 * for vector search, an {@link EmbeddingModel}).
 *
 * <p><strong>{@link #search} must leave nothing observable behind.</strong>
 * {@code KnowledgeTools.knowledgeSearchTool} declares itself
 * {@link dev.agentkit.core.tool.SideEffects#NONE} on your implementation's behalf, so a
 * rehearsal will run it. An implementation that logs queries to a durable store, bills a
 * per-query quota, or lets one search change what a later search returns breaks that
 * promise on your behalf too. Calling a paid embedding API is fine; leaving something
 * behind is not.
 *
 * <p>A purely internal memo is not "something behind": {@link Bm25Retriever} builds its
 * index on the first search, and that is compatible with
 * {@link dev.agentkit.core.tool.SideEffects#NONE} because it is idempotent, invisible in
 * every result, and safe to run from several threads at once — a rehearsal that triggers
 * it changes no answer any caller can see. The test is observability, not whether a field
 * was written.
 *
 * <p>This paragraph previously named "an implementation that lazily indexes on first
 * query" as an example of what breaks the promise, while the shipped default was one.
 * Two things were wrong then: the retriever's lazy rebuild was genuinely unsafe under
 * concurrent search (it is now guarded), and the rule was stated as "must not change
 * anything", which is stricter than {@code SideEffects.NONE} needs and than any
 * memoising implementation can meet.
 */
public interface KnowledgeBase {

    /** Ingests a document (chunking and indexing it). */
    void ingest(Document document);

    /** Ingests several documents. */
    default void ingestAll(Collection<Document> documents) {
        documents.forEach(this::ingest);
    }

    /** Returns up to {@code maxResults} relevant chunks, most relevant first. */
    List<SearchResult> search(String query, int maxResults);

    /** The number of indexed chunks. */
    int chunkCount();
}
