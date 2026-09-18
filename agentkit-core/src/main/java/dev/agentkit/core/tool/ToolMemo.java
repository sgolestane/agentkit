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
 * List<Tool> tools = List.of(directoryLookup, listAccess, grantAccess);
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
 *   <li><strong>Never across a write.</strong> {@link #wrapAll} also wraps every tool that is <em>not</em> a read,
 *       so that calling one forgets everything remembered — the lookup after a create sees the create. A read that
 *       was already under way when the write happened does not put its answer back afterwards.</li>
 *   <li><strong>Never for a tool bound to one run.</strong> Its answer may be that run's, so it is not shared.</li>
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
    /** Bumped by every write and every clear; an answer fetched under an older generation is not stored. */
    private long generation;

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

    /**
     * Every tool: reads remembered, and everything else made to forget what is remembered once it has run, so a
     * read after a write is answered by the tool.
     */
    public List<Tool> wrapAll(List<Tool> tools) {
        List<Tool> wrapped = new ArrayList<>(tools.size());
        tools.forEach(tool -> wrapped.add(tool.sideEffects() == SideEffects.NONE
                ? new MemoizingTool(tool, this) : new ForgettingTool(tool, this)));
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
        generation++;
    }

    private ToolResult through(Tool tool, ToolInvocation invocation) {
        String key = keyFor(invocation);
        Instant now = clock.get();
        long fetchedUnder;
        synchronized (this) {
            Remembered entry = entries.get(key);
            if (entry != null) {
                Duration age = Duration.between(entry.storedAt(), now);
                // A clock that went backwards makes the age negative; that is not a young answer, it is an answer
                // of unknown age, and it is not trusted.
                if (!age.isNegative() && age.compareTo(timeToLive) < 0) {
                    hits.incrementAndGet();
                    return entry.result();
                }
                entries.remove(key);
            }
            fetchedUnder = generation;
        }
        misses.incrementAndGet();
        ToolResult result = tool.execute(invocation);
        if (!result.isError()) {
            synchronized (this) {
                if (generation == fetchedUnder) {
                    entries.put(key, new Remembered(result, now));
                }
            }
        }
        return result;
    }

    /**
     * The key for one call: its tool and its arguments, rendered so that two calls agree exactly when they ask the
     * same thing, however their maps were built.
     *
     * <p>Every value carries its type and every string is quoted and escaped, so no two different calls share a key:
     * {@code {a: "1,b=2"}} is not {@code {a: "1", b: "2"}}, and the number 7 is not the string {@code "7"}. Map
     * entries are ordered by their rendered keys. Anything that is not a string, number, boolean, map or list is
     * rendered by its type and its text, which is as good as that text.
     */
    static String keyFor(ToolInvocation invocation) {
        StringBuilder key = new StringBuilder();
        quote(invocation.name(), key);
        key.append(':');
        canonical(invocation.arguments(), key);
        return key.toString();
    }

    private static void canonical(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            quote(text, out);
        } else if (value instanceof Boolean flag) {
            out.append(flag);
        } else if (value instanceof Number number) {
            out.append(number.getClass().getSimpleName()).append('(').append(number).append(')');
        } else if (value instanceof Map<?, ?> map) {
            SortedMap<String, String> sorted = new TreeMap<>();
            map.forEach((k, v) -> {
                StringBuilder renderedKey = new StringBuilder();
                canonical(k, renderedKey);
                StringBuilder renderedValue = new StringBuilder();
                canonical(v, renderedValue);
                sorted.put(renderedKey.toString(), renderedValue.toString());
            });
            out.append('{');
            sorted.forEach((k, v) -> out.append(k).append(':').append(v).append(','));
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            list.forEach(item -> {
                canonical(item, out);
                out.append(',');
            });
            out.append(']');
        } else {
            out.append(value.getClass().getName()).append('(');
            quote(String.valueOf(value), out);
            out.append(')');
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        out.append('"');
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
            // Bound to one run, its answers may be that run's: not shared through a memo other runs read.
            return bound;
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            return memo.through(delegate, invocation);
        }
    }

    /** A tool that changes something, and makes the memo forget once it has — whether or not it reported success. */
    private static final class ForgettingTool extends ForwardingTool {

        private final Tool delegate;
        private final ToolMemo memo;

        ForgettingTool(Tool delegate, ToolMemo memo) {
            this.delegate = delegate;
            this.memo = memo;
        }

        @Override
        protected Tool delegate() {
            return delegate;
        }

        @Override
        protected Tool rebuiltAround(Tool bound) {
            return new ForgettingTool(bound, memo);
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            try {
                return delegate.execute(invocation);
            } finally {
                // A failed write may still have changed something; forgetting costs a read, not a wrong answer.
                memo.clear();
            }
        }
    }
}
