package dev.agentkit.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetrieverTest {

    private static Chunk chunk(String id, String text) {
        return new Chunk(id, "doc", text, Map.of());
    }

    @Test
    void bm25RanksLexicalMatchFirst() {
        Bm25Retriever retriever = new Bm25Retriever();
        retriever.add(List.of(
                chunk("c1", "the weather forecast shows rain tomorrow"),
                chunk("c2", "the stock market fell today")));

        List<SearchResult> results = retriever.search("weather rain", 5);
        assertThat(results.get(0).chunk().id()).isEqualTo("c1");
    }

    @Test
    void bm25RebuildsAfterIncrementalAdd() {
        Bm25Retriever retriever = new Bm25Retriever();
        retriever.add(List.of(chunk("c1", "alpha")));
        assertThat(retriever.search("beta", 5)).isEmpty();

        retriever.add(List.of(chunk("c2", "beta gamma")));
        assertThat(retriever.search("beta", 5)).extracting(r -> r.chunk().id()).containsExactly("c2");
        assertThat(retriever.size()).isEqualTo(2);
    }

    @Test
    void vectorRetrieverRanksBySharedTerms() {
        VectorRetriever retriever = new VectorRetriever(new HashingEmbeddingModel(64));
        retriever.add(List.of(
                chunk("c1", "database migration schema"),
                chunk("c2", "cooking recipe dinner")));

        List<SearchResult> results = retriever.search("database schema", 5);
        assertThat(results.get(0).chunk().id()).isEqualTo("c1");
    }

    @Test
    void vectorReAddReplacesNotDuplicates() {
        VectorRetriever retriever = new VectorRetriever(new HashingEmbeddingModel(32));
        retriever.add(List.of(chunk("c1", "alpha beta")));
        retriever.add(List.of(chunk("c1", "alpha beta"))); // same id again
        assertThat(retriever.size()).isEqualTo(1);
        assertThat(retriever.search("alpha", 5)).hasSize(1);
    }

    @Test
    void vectorRetrieverEmptyReturnsNothing() {
        assertThat(new VectorRetriever(new HashingEmbeddingModel(8)).search("x", 5)).isEmpty();
    }

    @Test
    void vectorRetrieverRejectsDimensionMismatch() {
        // A model whose dimension depends on the text triggers a mismatch.
        // Seed a non-zero component so the cosine reaches the dimension check.
        EmbeddingModel jagged = text -> {
            float[] v = new float[text.length() + 1];
            v[0] = 1f;
            return v;
        };
        VectorRetriever retriever = new VectorRetriever(jagged);
        retriever.add(List.of(chunk("c1", "abc")));
        assertThatThrownBy(() -> retriever.search("abcdef", 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void searchRejectsNonPositiveMaxResults() {
        assertThatThrownBy(() -> new Bm25Retriever().search("x", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
