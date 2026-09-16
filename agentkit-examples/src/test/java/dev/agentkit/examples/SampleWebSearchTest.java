package dev.agentkit.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The offline sample provider's {@code maxResults} bounds (no network). */
class SampleWebSearchTest {

    private final SampleWebSearch search = new SampleWebSearch();

    @Test
    void returnsTheRequestedCountWhenBelowTheSampleSize() {
        assertThat(search.search("anything", 2)).hasSize(2);
    }

    @Test
    void capsAtTheSampleSizeWhenMoreAreRequested() {
        // Never overflows even when asked for far more than exist.
        assertThat(search.search("anything", 99)).hasSize(3);
    }

    @Test
    void returnsAtLeastOneResultForZeroOrNegativeRequests() {
        // The max(1, …) floor guards against an empty/negative sublist bound.
        assertThat(search.search("anything", 0)).hasSize(1);
        assertThat(search.search("anything", -5)).hasSize(1);
    }

    @Test
    void resultsAreWellFormed() {
        for (WebSearch.Result r : search.search("anything", 3)) {
            assertThat(r.title()).isNotBlank();
            assertThat(r.url()).startsWith("https://");
            assertThat(r.snippet()).isNotBlank();
        }
        // The sample is about the demo topic.
        List<WebSearch.Result> results = search.search("anything", 3);
        assertThat(results).anySatisfy(r -> assertThat(r.url()).contains("graph"));
    }
}
