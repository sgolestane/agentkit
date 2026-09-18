package dev.agentkit.core.tool;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Remembers what a read returned, so the same read in the same breath is not paid for twice.
 *
 * <p>An agent that looks a person up, reasons, and looks them up again makes two calls where one would do; a run
 * replayed from a {@link dev.agentkit.core.routine.Routine} does the same, faster. Wrapping the registry's reads in
 * a memo removes the second call:
 *
 * <pre>{@code
 * ToolMemo memo = new ToolMemo(Duration.ofSeconds(30));
 * ToolRegistry cached = new SimpleToolRegistry(memo.wrapAll(tools));
 * }</pre>
 *
 * <h2>What is cached, and what is deliberately not</h2>
 *
 * <ul>
 *   <li><strong>Only {@link SideEffects#NONE}.</strong> A read is the only call whose answer can be reused;
 *       {@code IDEMPOTENT} means "doing it twice is as safe as once", which is a promise about the write, not about
 *       the answer. Everything else is returned unwrapped, so wrapping a whole registry is safe.</li>
 *   <li><strong>Never an error.</strong> A failure is usually about this moment — a timeout, a rate limit — and
 *       caching it turns one bad second into a bad minute.</li>
 *   <li><strong>Never for long.</strong> The time to live is the caller's, and there is no unbounded option: a read
 *       cached past the change it should have seen is a wrong answer delivered confidently.</li>
 * </ul>
 *
 * <h2>One memo, one reader</h2>
 *
 * <p>Entries are keyed by tool and arguments and by nothing else, so a memo shared between two people shows one what
 * the other asked for. Where a tool's answer depends on who is asking — most deployments — give each run, session or
 * tenant its own memo. The framework cannot tell which tools those are, so it does not try.
 */
public final class ToolMemo {

    /** Entries kept before the oldest is dropped. */
    public static final int DEFAULT_MAX_ENTRIES = 1_000;

    private record Remembered(ToolResult result, Instant storedAt) {
    }

    private final Duration timeToLive;
    private final int maxEntries;
    private final Supplier<Instant> clock;
    private final Map<String, Remembered> entries;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public ToolMemo(Duration timeToLive) {
        this(timeToLive, DEFAULT_MAX_ENTRIES, Instant::now);
    }

    public ToolMemo(Duration timeToLive, int maxEntries, Supplier<Instant> clock) {
        Objects.requireNonNull(timeToLive, "timeToLive");
        if (timeToLive.isNegative() || timeToLive.isZero()) {
            throw new IllegalArgumentException("a memo's time to live is positive, not " + timeToLive);
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("a memo holds at least one entry");
        }
        this.timeToLive = timeToLive;
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Remembered> eldest) {
                return size() > ToolMemo.this.maxEntries;
            }
        };
    }

    /** {@code tool} with its reads remembered, or {@code tool} itself if it is not a read. */
    public Tool wrap(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        return tool.sideEffects() == SideEffects.NONE ? new MemoizingTool(tool, this) : tool;
    }

    /** Every tool, reads wrapped and the rest left alone. */
    public List<Tool> wrapAll(List<Tool> tools) {
        List<Tool> wrapped = new ArrayList<>(tools.size());
        tools.forEach(tool -> wrapped.add(wrap(tool)));
        return List.copyOf(wrapped);
    }

    /** How many calls were answered from the memo. */
    public long hits() {
        return hits.get();
    }

    /** How many calls went to the tool. */
    public long misses() {
        return misses.get();
    }

    /** Forgets everything, for a caller that knows the world just changed. */
    public synchronized void clear() {
        entries.clear();
    }

    private ToolResult through(Tool tool, ToolInvocation invocation) {
        String key = keyFor(invocation);
        Instant now = clock.get();
        synchronized (this) {
            Remembered entry = entries.get(key);
            if (entry != null && Duration.between(entry.storedAt(), now).compareTo(timeToLive) < 0) {
                hits.incrementAndGet();
                return entry.result();
            }
            entries.remove(key);
        }
        misses.incrementAndGet();
        ToolResult result = tool.execute(invocation);
        if (!result.isError()) {
            synchronized (this) {
                entries.put(key, new Remembered(result, now));
            }
        }
        return result;
    }

    /**
     * The key for one call: its tool and its arguments, rendered so that two calls that ask the same thing agree
     * however their maps were built. Nested maps are sorted; everything else is its own text.
     */
    static String keyFor(ToolInvocation invocation) {
        return invocation.name() + "\0" + canonical(invocation.arguments());
    }

    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            SortedMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            StringBuilder out = new StringBuilder("{");
            sorted.forEach((k, v) -> out.append(k).append('=').append(canonical(v)).append(','));
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            list.forEach(item -> out.append(canonical(item)).append(','));
            return out.append(']').toString();
        }
        return String.valueOf(value);
    }

    /** A read that asks the memo first. */
    private static final class MemoizingTool extends ForwardingTool {

        private final Tool delegate;
        private final ToolMemo memo;

        MemoizingTool(Tool delegate, ToolMemo memo) {
            this.delegate = delegate;
            this.memo = memo;
        }

        @Override
        protected Tool delegate() {
            return delegate;
        }

        @Override
        protected Tool rebuiltAround(Tool bound) {
            return new MemoizingTool(bound, memo);
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            return memo.through(delegate, invocation);
        }
    }
}
