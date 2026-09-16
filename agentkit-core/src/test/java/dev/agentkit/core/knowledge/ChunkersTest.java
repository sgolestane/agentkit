package dev.agentkit.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkersTest {

    @Test
    void slidingWindowProducesOverlappingChunks() {
        List<Chunk> chunks = Chunkers.slidingWindow(3, 1).chunk(Document.of("d", "a b c d e"));
        // window=3, step=2 over 5 words: [a b c], [c d e] (overlap of one word).
        assertThat(chunks).extracting(Chunk::text).containsExactly("a b c", "c d e");
        assertThat(chunks).extracting(Chunk::id).containsExactly("d#0", "d#1");
        assertThat(chunks.get(0).metadata()).containsEntry("ordinal", 0);
    }

    @Test
    void slidingWindowRejectsBadOverlap() {
        assertThatThrownBy(() -> Chunkers.slidingWindow(3, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyDocumentYieldsNoChunks() {
        assertThat(Chunkers.slidingWindow(5, 0).chunk(Document.of("d", "   "))).isEmpty();
    }

    @Test
    void chunksInheritDocumentMetadata() {
        Document doc = new Document("d", "a b c d", java.util.Map.of("title", "My Doc", "url", "http://x"));
        List<Chunk> chunks = Chunkers.slidingWindow(2, 0).chunk(doc);
        assertThat(chunks.get(0).metadata())
                .containsEntry("title", "My Doc")
                .containsEntry("url", "http://x")
                .containsEntry("ordinal", 0);
    }

    @Test
    void paragraphsSplitOnBlankLines() {
        Document doc = Document.of("d", "first para\n\nsecond para\n\n\n  third  ");
        assertThat(Chunkers.paragraphs().chunk(doc)).extracting(Chunk::text)
                .containsExactly("first para", "second para", "third");
    }
}
