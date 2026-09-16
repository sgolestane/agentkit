package dev.agentkit.core.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns text into a dense vector for semantic retrieval.
 *
 * <p>The framework provides the retrieval <em>mechanism</em> ({@link VectorRetriever})
 * but not an embedding provider — supply one by implementing this interface over
 * your model of choice. All vectors returned by a given model must share the same
 * dimensionality.
 *
 * <p><strong>{@link #embed} must be safe to call from several threads at once.</strong>
 * {@link VectorRetriever#search} calls it on the read path, and a knowledge base is
 * meant to be shared read-only across a fan-out or a graph, so an implementation that
 * is not concurrent makes that sharing unsafe from here rather than from anything the
 * framework owns. Wrapping a non-thread-safe client in a lock is enough.
 */
@FunctionalInterface
public interface EmbeddingModel {

    /** Embeds a single text into a fixed-length vector. */
    float[] embed(String text);

    /** Embeds several texts; override for batched/more-efficient implementations. */
    default List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }
}
