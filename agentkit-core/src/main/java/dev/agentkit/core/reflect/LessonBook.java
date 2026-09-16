package dev.agentkit.core.reflect;

import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.core.util.OneLine;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A durable, append-only store of lessons, backed by a {@link MemoryStore}. Because
 * it lives in memory, lessons persist across runs — and, with a file-backed store,
 * across processes — so an agent keeps improving on recurring task types rather than
 * relearning within a single run.
 *
 * <p>Lessons are grouped under a {@code topic} (a distinct memory path per topic),
 * one lesson per line. {@link #recall()} returns the most recent up to a cap, keeping
 * the injected context bounded; exact duplicates are not re-recorded.
 *
 * <p><strong>Trust.</strong> A recorded lesson persists and is later replayed into an
 * agent's context. When lessons are produced by reflecting on tool output (see
 * {@code ReflectiveAgent}), that output is untrusted — treat a lesson book fed from
 * untrusted results as a persistent, cross-run injection surface, and do not point
 * it at a shared/durable store in that setting without review.
 *
 * <p>Not safe for concurrent writers to the same topic: {@link #record} is
 * check-then-append, and the underlying file store is single-writer per path.
 */
public final class LessonBook {

    private static final int DEFAULT_MAX_RECALL = 20;
    private static final Pattern VALID_TOPIC = Pattern.compile("[A-Za-z0-9._-]+");

    private final MemoryStore store;
    private final String path;
    private final int maxRecall;

    /** A book under the {@code "default"} topic, recalling up to 20 lessons. */
    public LessonBook(MemoryStore store) {
        this(store, "default", DEFAULT_MAX_RECALL);
    }

    /**
     * @param store     where lessons are persisted
     * @param topic     groups related lessons in their own memory path; must match
     *                  {@code [A-Za-z0-9._-]+} (rejected otherwise, so distinct topics
     *                  never collide or alias)
     * @param maxRecall the most recent lessons {@link #recall()} returns (&gt; 0)
     */
    public LessonBook(MemoryStore store, String topic, int maxRecall) {
        this.store = Objects.requireNonNull(store, "store");
        Objects.requireNonNull(topic, "topic");
        if (!VALID_TOPIC.matcher(topic).matches()) {
            throw new IllegalArgumentException("topic must match [A-Za-z0-9._-]+, was: '" + topic + "'");
        }
        if (maxRecall <= 0) {
            throw new IllegalArgumentException("maxRecall must be > 0");
        }
        this.maxRecall = maxRecall;
        this.path = "lessons/" + topic + ".md";
    }

    /**
     * Records {@code lesson} (ignoring blanks and exact duplicates). Newlines are flattened,
     * because {@code ReflectiveAgent.withLessons} bullets these one per line and a lesson
     * carrying a break would write lessons of its own.
     *
     * <p>{@link OneLine} does the collapsing; it carries the reason that is two passes
     * rather than the one regular expression it looks like.
     */
    public void record(String lesson) {
        Objects.requireNonNull(lesson, "lesson");
        String normalized = OneLine.of(lesson);
        if (normalized.isEmpty() || all().contains(normalized)) {
            return;
        }
        store.append(path, normalized + "\n");
    }

    /**
     * Drops everything past {@code maxRecall}, oldest first, and says whether it had to.
     *
     * <p>{@link #record} caps what is <em>recalled</em> and never trims what is stored, which
     * is right for a book whose writer is one run — {@code ReflectiveAgent} makes a handful of
     * lessons and stops. It is wrong for one fed from outside, where the file grows for ever
     * and a store whose append is a whole-file read-modify-write makes every later write and
     * every later read pay for it (#329).
     *
     * <p>Here rather than in the caller because the path is this class's, and a caller that
     * rebuilt it would fail <em>open</em> the day the scheme changed: an absent file reads as
     * empty, nothing needs trimming, and the growth returns with no exception and no log line.
     */
    public boolean trimToCap() {
        return trimToCap(line -> false);
    }

    /**
     * As {@link #trimToCap()}, never dropping a line {@code keepAlways} claims.
     *
     * <p>Because eviction is a cache policy and some lines are not cache. A
     * {@code CorrectionBook} correction that a person marked as standing is enforced by a
     * gate, and evicting it turns that gate off — silently, with no log line and no audit
     * row. Measured before this: ten ordinary rejections in one capability removed a
     * standing refusal set before them, and the next run proceeded (#329).
     */
    public boolean trimToCap(java.util.function.Predicate<String> keepAlways) {
        Objects.requireNonNull(keepAlways, "keepAlways");
        List<String> all = all();
        if (all.size() <= maxRecall) {
            return false;
        }
        // Counted by position, not by value: indexOf would give two identical lines the
        // same answer, and would be quadratic besides.
        int droppableTotal = 0;
        for (String line : all) {
            if (!keepAlways.test(line)) {
                droppableTotal++;
            }
        }
        int room = Math.max(0, maxRecall - (all.size() - droppableTotal));
        int firstToKeep = droppableTotal - room;
        List<String> survivors = new java.util.ArrayList<>();
        int seen = 0;
        for (String line : all) {
            if (keepAlways.test(line)) {
                survivors.add(line);
                continue;
            }
            if (seen >= firstToKeep) {
                survivors.add(line);
            }
            seen++;
        }
        if (survivors.size() == all.size()) {
            return false;
        }
        store.write(path, String.join("\n", survivors) + "\n");
        return true;
    }

    /**
     * Every line, uncapped — for a reader whose question is not "what should a prompt see".
     *
     * <p>{@link #recall()} is a prompt-sized window, and reading a <em>control</em> through
     * it makes the control expire when the window moves past it.
     */
    public List<String> everything() {
        return all();
    }

    /**
     * Replaces everything with {@code lines}, in order.
     *
     * <p>Here for the same reason as {@link #trimToCap()}: the path is this class's, and a
     * caller that rebuilt it would write beside the file it meant to write.
     */
    public void replaceWith(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        store.write(path, lines.isEmpty() ? "" : String.join("\n", lines) + "\n");
    }

    /** The most recent lessons, oldest first, capped at {@code maxRecall}. */
    public List<String> recall() {
        List<String> all = all();
        int from = Math.max(0, all.size() - maxRecall);
        return List.copyOf(all.subList(from, all.size()));
    }

    private List<String> all() {
        return store.read(path)
                .map(content -> content.lines().map(String::strip).filter(line -> !line.isEmpty()).toList())
                .orElse(List.of());
    }
}
