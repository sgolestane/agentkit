package dev.agentkit.core.knowledge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A dense-vector {@link Retriever} using cosine similarity over embeddings from a
 * caller-supplied {@link EmbeddingModel}. Good for semantic matches where the
 * query and source use different wording.
 *
 * <p>This is an in-memory reference implementation with a linear similarity scan —
 * suitable for moderate corpora. For large corpora, implement {@link Retriever}
 * over an external vector database.
 *
 * <p><strong>Concurrent search is safe; concurrent ingestion is not.</strong>
 * {@link #search} mutates nothing — it reads the entry map and delegates to the
 * {@link EmbeddingModel}, so several threads may call it at once provided that model is
 * itself safe to call concurrently. {@link #add} writes to a plain
 * {@link LinkedHashMap}: finish ingesting before sharing the instance.
 *
 * <p>This class previously said only "Not thread-safe.", which understated it in one
 * direction and overstated it in the other: the read path was already safe, and the
 * condition that actually matters — the caller's embedding model — went unmentioned.
 */
public final class VectorRetriever implements Retriever {

    private record Entry(Chunk chunk, float[] vector, double norm) {
    }

    private final EmbeddingModel model;
    // Keyed by chunk id so re-adding the same chunk replaces (not duplicates) it,
    // matching Bm25Retriever's set semantics.
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public VectorRetriever(EmbeddingModel model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    @Override
    public void add(Collection<Chunk> chunks) {
        Objects.requireNonNull(chunks, "chunks");
        for (Chunk chunk : chunks) {
            float[] vector = model.embed(chunk.text());
            entries.put(chunk.id(), new Entry(chunk, vector, norm(vector)));
        }
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be > 0");
        }
        if (entries.isEmpty()) {
            return List.of();
        }
        float[] q = model.embed(query);
        double qNorm = norm(q);
        List<SearchResult> results = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) {
            // Rank by cosine including non-positive similarities: unlike lexical
            // BM25, a low/negative cosine still identifies the nearest available
            // chunk. Callers filter by score if they want a relevance threshold.
            double sim = cosine(q, qNorm, entry.vector(), entry.norm());
            results.add(new SearchResult(entry.chunk(), sim));
        }
        results.sort(Comparator.comparingDouble(SearchResult::score).reversed()
                .thenComparing(r -> r.chunk().id()));
        return results.size() > maxResults ? List.copyOf(results.subList(0, maxResults)) : List.copyOf(results);
    }

    @Override
    public int size() {
        return entries.size();
    }

    private static double norm(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        return Math.sqrt(sum);
    }

    private static double cosine(float[] a, double aNorm, float[] b, double bNorm) {
        // Dimension check first, so a misconfigured model is caught even when a
        // zero-magnitude vector is involved.
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Embedding dimension mismatch: " + a.length + " vs " + b.length);
        }
        if (aNorm == 0 || bNorm == 0) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot / (aNorm * bNorm);
    }
}
