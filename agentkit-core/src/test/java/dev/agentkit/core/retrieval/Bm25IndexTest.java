package dev.agentkit.core.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Bm25IndexTest {

    private static Bm25Index sampleIndex() {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("weather", "get the current weather forecast for a city");
        docs.put("stocks", "look up the latest stock market price for a ticker");
        docs.put("email", "send an email message to a recipient");
        return Bm25Index.of(docs);
    }

    @Test
    void ranksRelevantDocumentFirst() {
        List<Bm25Index.Scored> hits = sampleIndex().search("weather forecast city", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).id()).isEqualTo("weather");
    }

    @Test
    void excludesNonMatchingDocuments() {
        List<Bm25Index.Scored> hits = sampleIndex().search("stock price", 5);
        assertThat(hits).extracting(Bm25Index.Scored::id).containsExactly("stocks");
    }

    @Test
    void emptyQueryReturnsNothing() {
        assertThat(sampleIndex().search("   ", 5)).isEmpty();
    }

    @Test
    void limitIsRespected() {
        assertThat(sampleIndex().search("the a for to", 2)).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void emptyCorpusReturnsNothing() {
        assertThat(Bm25Index.of(Map.of()).search("anything", 5)).isEmpty();
    }

    @Test
    void nonPositiveLimitThrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sampleIndex().search("weather", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tiesAreBrokenByIdDeterministically() {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("b", "apple");
        docs.put("a", "apple");
        List<Bm25Index.Scored> hits = Bm25Index.of(docs).search("apple", 5);
        assertThat(hits).extracting(Bm25Index.Scored::id).containsExactly("a", "b");
    }

    @Test
    void tokenizerSplitsOnNonAlphanumeric() {
        assertThat(Bm25Index.tokenize("Hello, World! foo_bar-baz42"))
                .containsExactly("hello", "world", "foo", "bar", "baz42");
    }
}
