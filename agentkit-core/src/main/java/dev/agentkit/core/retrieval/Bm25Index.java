package dev.agentkit.core.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A small, dependency-free BM25 lexical index over a fixed corpus of text
 * documents keyed by an opaque id.
 *
 * <p>BM25 is the Okapi ranking function: it scores documents by term frequency,
 * discounted for document length and weighted by inverse document frequency.
 * This implementation is immutable after construction and used both for
 * progressive tool disclosure (Phase 2) and knowledge-base retrieval (Phase 4).
 *
 * <p>Tokenisation is intentionally simple: lower-cased runs of letters and
 * digits. It is not language-aware; callers needing stemming or stop-word
 * removal can pre-process text before indexing.
 */
public final class Bm25Index {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    /** A scored search hit. */
    public record Scored(String id, double score) {
    }

    private final List<String> ids;
    private final List<Map<String, Integer>> termFreqs;
    private final Map<String, Integer> docFreq;
    private final double[] docLengths;
    private final double avgDocLength;

    private Bm25Index(List<String> ids, List<Map<String, Integer>> termFreqs,
                      Map<String, Integer> docFreq, double[] docLengths, double avgDocLength) {
        this.ids = ids;
        this.termFreqs = termFreqs;
        this.docFreq = docFreq;
        this.docLengths = docLengths;
        this.avgDocLength = avgDocLength;
    }

    /**
     * Builds an index over the given documents.
     *
     * @param documents id → text; ids must be unique and non-null
     */
    public static Bm25Index of(Map<String, String> documents) {
        Objects.requireNonNull(documents, "documents");
        List<String> ids = new ArrayList<>(documents.size());
        List<Map<String, Integer>> termFreqs = new ArrayList<>(documents.size());
        Map<String, Integer> docFreq = new HashMap<>();
        double[] docLengths = new double[documents.size()];
        double totalLength = 0;

        int i = 0;
        for (Map.Entry<String, String> entry : documents.entrySet()) {
            String id = Objects.requireNonNull(entry.getKey(), "document id");
            List<String> tokens = tokenize(entry.getValue());
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }
            for (String term : tf.keySet()) {
                docFreq.merge(term, 1, Integer::sum);
            }
            ids.add(id);
            termFreqs.add(tf);
            docLengths[i] = tokens.size();
            totalLength += tokens.size();
            i++;
        }
        double avg = ids.isEmpty() ? 0 : totalLength / ids.size();
        return new Bm25Index(ids, termFreqs, docFreq, docLengths, avg);
    }

    public int size() {
        return ids.size();
    }

    /**
     * Returns up to {@code limit} documents ranked by BM25 score, highest first.
     * Documents with a non-positive score (no query term matched) are excluded.
     * Ties are broken by id for reproducibility.
     *
     * <p>This is a linear scan over the corpus (O(N·|query terms|) per call), with
     * no inverted index — appropriate for the small-to-moderate corpora used for
     * tool disclosure and knowledge-base retrieval, not for web-scale search.
     */
    public List<Scored> search(String query, int limit) {
        Objects.requireNonNull(query, "query");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty() || ids.isEmpty()) {
            return List.of();
        }

        int n = ids.size();
        List<Scored> scored = new ArrayList<>();
        for (int d = 0; d < n; d++) {
            double score = 0;
            Map<String, Integer> tf = termFreqs.get(d);
            for (String term : queryTerms) {
                Integer f = tf.get(term);
                if (f == null) {
                    continue;
                }
                int df = docFreq.getOrDefault(term, 0);
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                // avgDocLength is > 0 here: a matching term (f != null) implies a
                // non-empty document, which implies a non-empty corpus.
                double lengthNorm = avgDocLength == 0 ? 0 : docLengths[d] / avgDocLength;
                double denom = f + K1 * (1 - B + B * lengthNorm);
                score += idf * (f * (K1 + 1)) / denom;
            }
            if (score > 0) {
                scored.add(new Scored(ids.get(d), score));
            }
        }
        // Descending by score, with id as a deterministic tiebreak so ranking is
        // reproducible regardless of the input map's iteration order.
        scored.sort(java.util.Comparator.comparingDouble(Bm25Index.Scored::score).reversed()
                .thenComparing(Bm25Index.Scored::id));
        return scored.size() > limit ? List.copyOf(scored.subList(0, limit)) : List.copyOf(scored);
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                current.append(c);
            } else if (current.length() > 0) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
