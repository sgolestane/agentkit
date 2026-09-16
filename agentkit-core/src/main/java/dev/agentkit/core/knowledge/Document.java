package dev.agentkit.core.knowledge;

import dev.agentkit.core.util.Frozen;
import java.util.Map;
import java.util.Objects;

/**
 * A unit of source content ingested into a {@link KnowledgeBase}.
 *
 * @param id       unique document id; never {@code null} or blank
 * @param text     the document body; never {@code null}
 * @param metadata arbitrary application metadata (e.g. title, url, source); never
 *                 {@code null}. Stored as a defensive, unmodifiable, order-preserving copy
 *                 reaching every nested map and collection; a value of any other type is
 *                 shared ({@link Frozen#containersOf}).
 *
 *                 <p>Deep since #133, and a knowledge base is where a one-level copy lasts
 *                 longest. {@code Chunkers} carries this map into every {@link Chunk} it
 *                 cuts and a {@code KnowledgeBase} then holds those for the life of the
 *                 process, so one nested object shared with the ingesting caller outlives
 *                 the ingest by the whole run — and {@code KnowledgeTools} renders
 *                 {@code title} and {@code url} from it into the citation inside a fence,
 *                 where a later mutation contradicts a citation the model has already been
 *                 given. Unlike {@code Goal}, this is paid once per document rather than on
 *                 every workflow replay, so the cost side of the decision is not close:
 *                 the copy goes from 280 B to 488 B on a three-key metadata map, once, per
 *                 document.
 *
 *                 <p>{@code Frozen.deeply} would be wrong here for the usual reason —
 *                 metadata is the application's own and may hold an {@code Instant} or a
 *                 {@code URI}, which {@code deeply} refuses
 */
public record Document(String id, String text, Map<String, Object> metadata) {

    public Document {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Document id must not be blank");
        }
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(metadata, "metadata");
        metadata = Frozen.containersOf(metadata);
    }

    public static Document of(String id, String text) {
        return new Document(id, text, Map.of());
    }
}
