package dev.agentkit.core.reflect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.memory.MemoryStore;
import org.junit.jupiter.api.Test;

class LessonBookTest {

    @Test
    void recordsAndRecallsInOrder() {
        LessonBook book = new LessonBook(MemoryStore.inMemory());
        book.record("prefer the search tool for facts");
        book.record("confirm the date before booking");
        assertThat(book.recall()).containsExactly(
                "prefer the search tool for facts", "confirm the date before booking");
    }

    @Test
    void ignoresBlanksAndExactDuplicates() {
        LessonBook book = new LessonBook(MemoryStore.inMemory());
        book.record("   ");
        book.record("a lesson");
        book.record("a lesson"); // exact duplicate
        assertThat(book.recall()).containsExactly("a lesson");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "\n", "\r", "\r\n", "", "", "", " ", " "})
    void flattensEveryLineTerminatorIntoASingleLine(String separator) {
        // ReflectiveAgent.withLessons bullets these one per line, so a lesson carrying a
        // break writes lessons of its own. Every terminator, not just '\n': the single-case
        // test this replaces left narrowing the pattern to '\n' invisible.
        LessonBook book = new LessonBook(MemoryStore.inMemory());

        book.record("first point" + separator + "second point");

        assertThat(book.recall()).containsExactly("first point second point");
    }

    @Test
    void flatteningIsLinearInTheLengthOfAWhitespaceRun() {
        LessonBook book = new LessonBook(MemoryStore.inMemory());
        long start = System.nanoTime();

        book.record("x" + " ".repeat(200_000) + "x");

        assertThat((System.nanoTime() - start) / 1_000_000)
                .as("flattening went quadratic again").isLessThan(2_000);
        assertThat(book.recall()).containsExactly("x x");
    }

    @Test
    void recallIsCappedToTheMostRecent() {
        LessonBook book = new LessonBook(MemoryStore.inMemory(), "topic", 2);
        book.record("one");
        book.record("two");
        book.record("three");
        assertThat(book.recall()).containsExactly("two", "three");
    }

    @Test
    void lessonsPersistAcrossBookInstancesOverTheSameStore() {
        MemoryStore store = MemoryStore.inMemory();
        new LessonBook(store).record("carried over");
        // A fresh book (as a later run would build) sees the earlier lesson.
        assertThat(new LessonBook(store).recall()).containsExactly("carried over");
    }

    @Test
    void topicsAreIsolated() {
        MemoryStore store = MemoryStore.inMemory();
        new LessonBook(store, "booking", 20).record("booking lesson");
        assertThat(new LessonBook(store, "research", 20).recall()).isEmpty();
    }

    @Test
    void rejectsAnInvalidTopicSoTopicsCannotCollideOrAliasTheDefault() {
        MemoryStore store = MemoryStore.inMemory();
        // Blank, slashes, and spaces are rejected (they would collide/alias if sanitized).
        assertThatThrownBy(() -> new LessonBook(store, "", 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LessonBook(store, "a/b", 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LessonBook(store, "a b", 20)).isInstanceOf(IllegalArgumentException.class);
    }
}
