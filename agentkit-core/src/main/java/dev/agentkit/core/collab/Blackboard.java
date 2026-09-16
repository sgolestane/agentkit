package dev.agentkit.core.collab;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A concurrency-safe shared workspace ("blackboard") that collaborating agents
 * post to and read from, so several agents can build on each other's partial
 * work instead of each starting fresh.
 *
 * <p>Entries are append-only and carry a monotonically increasing {@link Entry#id()}
 * assigned at post time, so a reader can page forward from the last id it saw. The
 * board is safe to share across the threads a {@code Supervisor} fans out onto.
 * Expose it to agents as tools via {@link BlackboardTools}.
 *
 * <p><strong>Paging is the reason for the shape of this class.</strong> A reader walks a
 * long board a page at a time, so the whole-tail forms — {@link #since(long)} and
 * {@link #byTopic(String)} — re-answer the same question with one fewer entry on every
 * call, which is quadratic across a walk: paging a 50,000-note board to exhaustion copied
 * 4.2 × 10^8 entry references and spent 6.3 s inside the store (#93). The bounded forms
 * {@link #since(long, int)} and {@link #byTopic(String, long, int)} answer in
 * O(log n + limit) and are what {@code BlackboardTools} calls; the unbounded ones remain
 * for a caller that really does want everything at once.
 */
public final class Blackboard {

    /**
     * One post on the board.
     *
     * @param id      monotonic, assigned at post time; strictly increasing
     * @param author  who posted it (an agent name)
     * @param topic   a short label used to group and filter related posts
     * @param content the note body
     */
    public record Entry(long id, String author, String topic, String content) {
        public Entry {
            Objects.requireNonNull(author, "author");
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(content, "content");
        }
    }

    /**
     * A bounded window onto a query, and the size of the answer it was cut from.
     *
     * <p>Both halves are needed and neither implies the other. A reader renders
     * {@code entries} and needs {@code total} to say how much of the board it is not
     * showing — the count is what turns a silent truncation into a page with a cursor, and
     * this class can produce it without materialising what it is counting.
     *
     * @param entries the oldest matches, in post order, at most {@code limit} of them
     * @param total   how many entries matched in all; {@code entries} is the front of them
     */
    public record Page(List<Entry> entries, int total) {
        public Page {
            entries = List.copyOf(entries);
            if (total < entries.size()) {
                throw new IllegalArgumentException(
                        "total " + total + " is smaller than the page it describes ("
                                + entries.size() + ")");
            }
        }

        /** Whether the limit bit: matches exist that this page does not carry. */
        public boolean truncated() {
            return entries.size() < total;
        }
    }

    /**
     * The entries as one immutable value: a backing array and how much of it is live.
     *
     * <p>Published by a single volatile write, so a reader that takes the field once holds
     * an array whose first {@code size} slots can never change under it. That is what lets
     * {@link #startOf} binary-search a snapshot without a lock — {@code CopyOnWriteArrayList}
     * could not, because its {@code size()} and {@code get(int)} are two reads of two
     * different arrays and a search across them has no snapshot to be correct about.
     *
     * <p>Copy-on-write in the sense that matters — a published slot is never rewritten —
     * but it grows into spare capacity rather than copying the whole array on every post.
     * The old shape copied per post, so <em>filling</em> a board was quadratic before any
     * reader had asked for anything: 2.5 × 10^9 element copies at n = 50,000, against
     * 1.6 × 10^5 here.
     */
    private record Snapshot(Entry[] items, int size) {

        static final Snapshot EMPTY = new Snapshot(new Entry[0], 0);

        Snapshot append(Entry entry) {
            Entry[] target = items;
            if (size == target.length) {
                target = Arrays.copyOf(items, Math.max(4, size + (size >> 1)));
            }
            // Written before the volatile publication of the new length that the returned
            // Snapshot becomes, and never rewritten afterwards. A reader that sees the new
            // length has seen this slot through that write; a reader still holding the old
            // Snapshot has a smaller size and never looks past it.
            target[size] = entry;
            return new Snapshot(target, size + 1);
        }

        List<Entry> range(int from, int to) {
            return List.of(Arrays.copyOfRange(items, from, to));
        }
    }

    private volatile Snapshot entries = Snapshot.EMPTY;

    /**
     * Entries grouped by {@link #topicKey}, so a topic filter is a lookup rather than a
     * pass over the board. Each bucket is id-sorted for the same reason the board is: it
     * is appended to under the same lock, in the same order.
     *
     * <p>One extra reference per entry, which is what buys {@code byTopic} the same
     * O(log n + limit) paging {@code since} gets. The scan it replaces cost 19.6 s and
     * 27 GB of allocation to page a 50,000-note board (#93) — worse than the unfiltered
     * path, because it copied the topic's entries and then copied them again to drop the
     * ones before the cursor.
     */
    private final Map<String, Snapshot> topics = new ConcurrentHashMap<>();

    private final Object writeLock = new Object();
    private long nextId = 1;

    /**
     * Appends a note and returns the stored entry (with its assigned id). Id
     * assignment and insertion happen together under a lock, so ids are handed out
     * in insertion order — the board stays sorted by id and a concurrent post can
     * never publish a lower id after a reader has paged past it. Reads are lock-free
     * (each read takes one immutable snapshot) and always see a contiguous id prefix.
     */
    public Entry post(String author, String topic, String content) {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(content, "content");
        synchronized (writeLock) {
            Entry entry = new Entry(nextId++, author, topic, content);
            // The topic bucket first, so that an entry is never visible to byTopic() only
            // after it is visible to since() — a reader that saw it on the board and then
            // asked for its topic would otherwise be told the topic is empty.
            topics.compute(topicKey(topic),
                    (key, bucket) -> (bucket == null ? Snapshot.EMPTY : bucket).append(entry));
            entries = entries.append(entry);
            return entry;
        }
    }

    /** A snapshot of every entry, in post order. */
    public List<Entry> entries() {
        Snapshot snapshot = entries;
        return snapshot.range(0, snapshot.size());
    }

    /**
     * Entries whose topic equals {@code topic} (case-insensitive), in post order.
     *
     * <p>The whole topic, however long: prefer {@link #byTopic(String, long, int)} when
     * paging, which is the call that stops being quadratic.
     */
    public List<Entry> byTopic(String topic) {
        Objects.requireNonNull(topic, "topic");
        Snapshot snapshot = topics.getOrDefault(topicKey(topic), Snapshot.EMPTY);
        return snapshot.range(0, snapshot.size());
    }

    /**
     * Entries posted after {@code afterId} (exclusive), in post order.
     *
     * <p>The whole tail, however long: prefer {@link #since(long, int)} when paging, which
     * is the call that stops being quadratic.
     */
    public List<Entry> since(long afterId) {
        Snapshot snapshot = entries;
        return snapshot.range(startOf(snapshot, afterId), snapshot.size());
    }

    /**
     * At most {@code limit} entries posted after {@code afterId} (exclusive), oldest
     * first, with {@link Page#total()} reporting how many come after {@code afterId} in
     * all.
     *
     * @param afterId return entries with a strictly greater id; {@code 0} for the whole board
     * @param limit   the most entries the page may carry; must be &gt; 0
     * @throws IllegalArgumentException if {@code limit} is not positive
     */
    public Page since(long afterId, int limit) {
        requirePositive(limit);
        Snapshot snapshot = entries;
        // Binary search rather than a scan, which is the whole of #93: ids are handed out
        // under the write lock in insertion order, so the snapshot is sorted by id and the
        // first entry after the cursor can be found without touching the ones before it.
        int start = startOf(snapshot, afterId);
        int total = snapshot.size() - start;
        // The count comes out of the arithmetic, so the page is bounded without the tail
        // ever being materialised to be measured.
        return new Page(snapshot.range(start, start + Math.min(total, limit)), total);
    }

    /**
     * At most {@code limit} entries under {@code topic} (case-insensitive) posted after
     * {@code afterId} (exclusive), oldest first, with {@link Page#total()} reporting how
     * many match in all.
     *
     * @param topic   the topic to filter on, compared case-insensitively
     * @param afterId return entries with a strictly greater id; {@code 0} for the whole topic
     * @param limit   the most entries the page may carry; must be &gt; 0
     * @throws IllegalArgumentException if {@code limit} is not positive
     */
    public Page byTopic(String topic, long afterId, int limit) {
        Objects.requireNonNull(topic, "topic");
        requirePositive(limit);
        Snapshot snapshot = topics.getOrDefault(topicKey(topic), Snapshot.EMPTY);
        int start = startOf(snapshot, afterId);
        int total = snapshot.size() - start;
        return new Page(snapshot.range(start, start + Math.min(total, limit)), total);
    }

    /** The number of entries currently on the board. */
    public int size() {
        return entries.size();
    }

    private static void requirePositive(int limit) {
        if (limit <= 0) {
            // Refused rather than clamped to one. A limit of zero is a caller that computed
            // its page size and got it wrong, and a page of nothing over a non-empty board
            // is indistinguishable from the end of the board — the failure a cursor cannot
            // recover from.
            throw new IllegalArgumentException("limit must be > 0, was " + limit);
        }
    }

    /** The index of the first entry with an id greater than {@code afterId}. */
    private static int startOf(Snapshot snapshot, long afterId) {
        Entry[] items = snapshot.items();
        int low = 0;
        int high = snapshot.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (items[mid].id() > afterId) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    /**
     * The key a topic is filed under: {@link String#equalsIgnoreCase} written as a value,
     * so the index answers exactly what the scan it replaced answered.
     *
     * <p>Not {@code toLowerCase}, which is a different relation in both directions.
     * {@code String.toLowerCase} is context-sensitive and can change length — {@code "İ"}
     * lowercases to two characters — while {@code equalsIgnoreCase} is a per-character
     * comparison that cannot. And it disagrees on characters this repo has no reason to
     * exclude: {@code Character.toUpperCase('ı')} is {@code 'I'}, so
     * {@code "ı".equalsIgnoreCase("i")} is true, but {@code "ı".toLowerCase()} is
     * {@code "ı"} and would file the two under different keys — a topic that used to match
     * silently not matching, which is the one way an index may not differ from a scan.
     *
     * <p>Folding each character through {@code toLowerCase(toUpperCase(c))} is that
     * comparison exactly: {@code equalsIgnoreCase} accepts a pair when the upper-cased
     * characters are equal <em>or</em> the lower-cased upper-cased ones are, and the first
     * of those implies the second, so equal keys and {@code equalsIgnoreCase} are the same
     * predicate.
     */
    private static String topicKey(String topic) {
        StringBuilder key = new StringBuilder(topic.length());
        for (int i = 0; i < topic.length(); i++) {
            key.append(Character.toLowerCase(Character.toUpperCase(topic.charAt(i))));
        }
        return key.toString();
    }
}
