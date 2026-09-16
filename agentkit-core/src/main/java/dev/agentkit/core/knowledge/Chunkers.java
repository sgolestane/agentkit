package dev.agentkit.core.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ready-made {@link Chunker} strategies.
 */
public final class Chunkers {

    private Chunkers() {
    }

    /**
     * A word-based sliding window: each chunk holds {@code windowWords} words and
     * successive chunks overlap by {@code overlapWords} words (so context spanning
     * a boundary is not lost).
     *
     * @throws IllegalArgumentException if {@code windowWords <= 0} or
     *                                  {@code overlapWords} is negative or {@code >= windowWords}
     */
    public static Chunker slidingWindow(int windowWords, int overlapWords) {
        if (windowWords <= 0) {
            throw new IllegalArgumentException("windowWords must be > 0");
        }
        if (overlapWords < 0 || overlapWords >= windowWords) {
            throw new IllegalArgumentException("overlapWords must be in [0, windowWords)");
        }
        int step = windowWords - overlapWords;
        return document -> {
            String[] words = document.text().strip().split("\\s+");
            List<Chunk> chunks = new ArrayList<>();
            if (words.length == 1 && words[0].isEmpty()) {
                return chunks;
            }
            int ordinal = 0;
            for (int start = 0; start < words.length; start += step) {
                int end = Math.min(start + windowWords, words.length);
                String text = String.join(" ", java.util.Arrays.copyOfRange(words, start, end));
                chunks.add(chunk(document, ordinal++, text));
                if (end == words.length) {
                    break;
                }
            }
            return chunks;
        };
    }

    /**
     * One chunk per blank-line-separated paragraph. Empty paragraphs are dropped.
     */
    public static Chunker paragraphs() {
        return document -> {
            String[] paragraphs = document.text().split("\\n\\s*\\n");
            List<Chunk> chunks = new ArrayList<>();
            int ordinal = 0;
            for (String paragraph : paragraphs) {
                String text = paragraph.strip();
                if (!text.isEmpty()) {
                    chunks.add(chunk(document, ordinal++, text));
                }
            }
            return chunks;
        };
    }

    private static Chunk chunk(Document document, int ordinal, String text) {
        Map<String, Object> metadata = new LinkedHashMap<>(document.metadata());
        metadata.put("ordinal", ordinal);
        return new Chunk(document.id() + "#" + ordinal, document.id(), text, metadata);
    }
}
