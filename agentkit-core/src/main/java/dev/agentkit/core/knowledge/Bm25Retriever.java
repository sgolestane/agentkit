package dev.agentkit.core.knowledge;

import dev.agentkit.core.retrieval.Bm25Index;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A lexical {@link Retriever} backed by {@link Bm25Index}. Good for exact-term,
 * keyword, and code-like queries; requires no embedding model.
 *
 * <p>The BM25 index is rebuilt lazily on the first search after chunks change, so
 * batch ingestion followed by queries is efficient. Rebuilding eagerly instead was
 * measured and rejected: {@link Bm25Index#of} re-tokenises the whole corpus, so one
 * rebuild per ingested document is quadratic — over a 1000-document corpus it took 41
 * seconds against 136 milliseconds for a single rebuild, and 2.9 minutes at 2000
 * documents.
 *
 * <p><strong>Concurrent search is safe; concurrent ingestion is not.</strong> Ingest
 * during setup, then share the instance read-only for the run, exactly as
 * {@link InMemoryKnowledgeBase} says: several threads may call {@link #search} at once,
 * including the very first one that triggers the rebuild, and each sees the whole index.
 * Calling {@link #add} while another thread searches is still unsupported — the chunk
 * map is a plain {@link LinkedHashMap} — so finish ingesting before sharing.
 *
 * <p>This class previously carried the advice: <em>"Not thread-safe — because the first
 * search after ingestion mutates internal state (the rebuild), perform a warm-up search
 * before sharing an instance read-only across threads."</em> The hazard was real (the
 * fields below were non-volatile, so a thread could observe {@code dirty == false}
 * without observing the index that made it false, and dereference null), but the
 * mitigation was written on a class {@link InMemoryKnowledgeBase#bm25()} constructs for
 * you and never names — so the reader who needed it had no reason to look. The rebuild
 * is now guarded rather than merely documented, and no warm-up is required.
 */
public final class Bm25Retriever implements Retriever {

    private final Map<String, Chunk> chunks = new LinkedHashMap<>();

    /**
     * Volatile so a thread that observes {@link #dirty} as false is guaranteed to observe
     * the index that cleared it. Without it the two writes in {@link #rebuild} are
     * unordered for a reader and {@link #search} could dereference null.
     */
    private volatile Bm25Index index;

    /** Volatile for the same reason; written last in {@link #rebuild}, read first here. */
    private volatile boolean dirty = true;

    @Override
    public void add(Collection<Chunk> newChunks) {
        Objects.requireNonNull(newChunks, "newChunks");
        for (Chunk chunk : newChunks) {
            chunks.put(chunk.id(), chunk);
        }
        dirty = true;
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be > 0");
        }
        // Read the field once into a local. Even with a volatile field, re-reading it
        // between the staleness check and the search would let a concurrent rebuild swap
        // the index underneath this call; one read makes the rest of the method operate
        // on a single, complete index.
        Bm25Index snapshot = index;
        if (dirty || snapshot == null) {
            snapshot = rebuild();
        }
        List<SearchResult> results = new ArrayList<>();
        for (Bm25Index.Scored hit : snapshot.search(query, maxResults)) {
            results.add(new SearchResult(chunks.get(hit.id()), hit.score()));
        }
        return results;
    }

    @Override
    public int size() {
        return chunks.size();
    }

    /**
     * Rebuilds the index if it is still needed, and returns the current one. Synchronized
     * so that N threads racing the first search of a run do N-1 fewer rebuilds and all
     * leave with a non-null, complete index. The monitor is this instance rather than a
     * private lock object so that the guard is visible to reflection, which is how
     * {@code Bm25RetrieverConcurrencyTest} pins it; nothing else in this final class
     * locks on {@code this}, and the instance is not documented as a lock, so there is
     * nothing for the choice to collide with.
     *
     * @return the index to search; never {@code null}
     */
    private synchronized Bm25Index rebuild() {
        Bm25Index current = index;
        if (!dirty && current != null) {
            // Another thread rebuilt while this one waited for the lock.
            return current;
        }
        Map<String, String> corpus = new LinkedHashMap<>();
        chunks.forEach((id, chunk) -> corpus.put(id, chunk.text()));
        Bm25Index built = Bm25Index.of(corpus);
        // Order matters and is load-bearing: the index is published before the flag that
        // says it is ready, so no reader can see the flag without the index.
        index = built;
        dirty = false;
        return built;
    }
}
