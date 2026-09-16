package dev.agentkit.core.knowledge;

import dev.agentkit.core.util.Frozen;
import java.util.Map;
import java.util.Objects;

/**
 * A retrievable fragment of a {@link Document}.
 *
 * @param id         unique chunk id; never {@code null} or blank
 * @param documentId the id of the source document; never {@code null}
 * @param text       the chunk text; never {@code null}
 * @param metadata   metadata inherited from the document plus chunk-specific fields (e.g.
 *                   ordinal); never {@code null}. Copied on the same terms as
 *                   {@link Document#metadata()} and for the same reason — a chunk is the
 *                   thing a {@code KnowledgeBase} actually stores, and
 *                   {@code KnowledgeTools} reads {@code title} and {@code url} off
 *                   <em>this</em> map when it builds the citation it fences into a prompt.
 *                   Copying only the document's would leave the copy one hop short of the
 *                   value that is read ({@link Frozen#containersOf})
 */
public record Chunk(String id, String documentId, String text, Map<String, Object> metadata) {

    public Chunk {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Chunk id must not be blank");
        }
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(metadata, "metadata");
        metadata = Frozen.containersOf(metadata);
    }
}
