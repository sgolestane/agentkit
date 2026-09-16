package dev.agentkit.core.util;

import java.util.List;
import java.util.function.Supplier;

/**
 * Text that cannot restructure the line it is printed into.
 *
 * <p>Not "for the operator" — {@code MemoryKeys} quotes a refused key through here and
 * hands it to a <em>model</em>. The choice is about the text, not the reader: use this
 * where the exact characters identify the thing being named, so losing them loses the
 * point. A filename, a key, a tool name.
 *
 * <p>This repo now has four strategies for text somebody else chose, and they are not
 * interchangeable. <strong>Refuse</strong> it, where the value is wiring and a bad one is a
 * programming error ({@code MemoryKeys.normalize}, {@code Peer}'s constructor).
 * <strong>Fence</strong> it behind an unforgeable marker, where it is a body a model will
 * read ({@code Spotlight}). <strong>Escape</strong> it, here, where it is a name and
 * fidelity matters. <strong>Collapse</strong> it ({@link OneLine}) where it is prose that
 * has to stay legible and only its shape is dangerous.
 *
 * <h2>What this stops, and what it does not</h2>
 *
 * <p>Two things a raw string does to a log line:
 *
 * <ul>
 *   <li><strong>It writes lines.</strong> {@code "Ignoring: a.md\n2026-08-21 WARN nothing
 *       is wrong"} is two entries, and the second one is a lie in the framework's voice.
 *       This is the same defect as {@code OneLine}'s, one reader over. Brute-forced over
 *       every code point: nothing survives {@link #of} that {@code String.lines} treats as
 *       a break.</li>
 *   <li><strong>It moves the cursor.</strong> Logs are read in terminals, and
 *       {@code U+001B} starts a sequence that can recolour, erase the line, or scroll what
 *       is above it away. A name is a fine place to hide one. {@code isISOControl} covers
 *       the C1 set too, so {@code U+009B} CSI and {@code U+009D} OSC are closed with
 *       it.</li>
 * </ul>
 *
 * <p>It does <em>not</em> make two names render differently. That claim was here and it was
 * false: {@code U+FE00}, {@code U+034F}, {@code U+0301} and {@code U+3164} all measure to
 * the same advance width as nothing at all, and none is {@code FORMAT}; a name of Hebrew
 * letters reorders the line under the Bidi algorithm with no formatting character present.
 * Only an allowlist could promise that, and an allowlist wide enough for {@code 日本語.md}
 * would not promise it either. What is escaped here is what can end a line or drive a
 * terminal, plus the formatting characters, which are cheap to include and often are the
 * confusable. Anything relying on visual distinctness needs a different tool.
 *
 * <h2>Escaped rather than collapsed</h2>
 *
 * <p>Which is where this parts company with {@link OneLine}. A model reading a listing needs
 * it to look like prose; an operator reading a log needs to know what was actually there,
 * so {@code \\u000A} is more use than a space. That promise only holds if the escaping is
 * reversible, so {@code \} is escaped too — without that, the six literal characters
 * {@code \u001B} in a filename produce output identical to a real escape character, and an
 * operator un-escaping the name to see what it was would manufacture a live ANSI sequence
 * out of one that never had one.
 *
 * <p>The rule is deliberately the same one {@code MemoryKeys} applies to a key it refuses,
 * and that class now asks here rather than keeping its own copy — a rule about what may
 * restructure text should not have two spellings that can drift.
 */
public final class Quoted {

    /** Hex digits, so escaping never runs a formatter. */
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** How deep a cause chain {@link #failure} will walk before it stops. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /**
     * How much of one throwable's message {@link #failure} will escape.
     *
     * <p><strong>The depth cap was a bound on the shape of a chain and not on its
     * size.</strong> {@link #failure} closed the injection half of "a hostile throwable on a
     * log line" and left the volume half open: nothing here cut, sampled or measured a
     * length, so a tool or an MCP server that threw with a large message wrote it verbatim
     * into the operator's pipeline, once per call. Measured through {@code slf4j-simple} on
     * the {@code Agent.runTool} line that carries it, one record:
     *
     * <pre>
     * message chars   record bytes   ns/record
     *            40            200     117,157
     *         4,000          4,160     882,749
     *       200,000        200,160   4,322,483
     *     1,000,000      1,000,160   8,913,420
     * </pre>
     *
     * <p>Linear and uncapped, and it is the same shape as #151 one path over: {@code
     * ToolResult.MAX_FAILURE_CHARS} bounds what the failure says to the <em>model</em> at
     * 4,000 characters and nothing bounded what the same message says to the operator. CI is
     * where that is not free — a run whose tests threw dozens of 200,000-character messages
     * took 25+ minutes against a 3.5 minute norm, 37 seconds of it in one such record, while
     * locally a fast pipe swallowed the whole thing.
     *
     * <p>4,000 rather than a number picked for the log, because the two records are read
     * together. This is exactly the window {@code ToolResult.MAX_FAILURE_CHARS} gives the
     * model, so an operator asking "what did the model read" is not answered by a line
     * carrying <em>less</em> of the message than the transcript does. Ordinary messages are
     * nowhere near it: seven real ones from this framework and its adapters measure a mean of
     * 35 characters and a maximum of 47.
     */
    private static final int MAX_MESSAGE_CHARS = 4_000;

    /**
     * How much of the <em>escaped</em> message reaches the line.
     *
     * <p>A second ceiling because {@link #of} expands, which is the half a single bound gets
     * wrong. Measured over 10,000-character inputs: plain ASCII 1.00, backslashes 2.00, an
     * astral tag character 5.00, and a newline or a zero-width formatting character
     * <strong>6.00</strong> — the {@code \\uXXXX} form costs six characters to say one. So a
     * message of 200,000 newlines, already inside any bound on its own length, wrote
     * 1,200,160 bytes.
     *
     * <p>This is the {@code cut -> expand -> cut} seam {@code Spotlight.fenceBounded} runs,
     * with one difference worth stating rather than copying past: {@code Spotlight.neutralise}
     * <em>shrinks</em> as well as grows, which is why its work ceiling is set far above its
     * output ceiling. {@link #of} never shrinks — every code point maps to at least itself —
     * so there is nothing to feed in beyond what could be printed, and the inner ceiling is
     * the model's window rather than a multiple of it.
     *
     * <p>Twice {@link #MAX_MESSAGE_CHARS}, so the escaping does not take the window the
     * inner ceiling just granted. A message may be one character in five a newline or a
     * formatting character and still arrive whole ({@code 4000 + 5k <= 8000} gives
     * {@code k <= 800}); past that it is cut and says so. It is still a ceiling: 4,000
     * newlines emit 8,015 characters here where unbounded they emit 24,000.
     */
    private static final int MAX_ESCAPED_MESSAGE_CHARS = 8_000;

    /**
     * How many suppressed exceptions on one node {@link #failure} will walk.
     *
     * <p>The other axis of the same walk {@link #MAX_CAUSE_DEPTH} bounds, and it was left
     * open on the argument corrected in {@link #failure}'s javadoc. Measured through
     * {@code Observations.LOGGING} with a throwing observer, which is the reachable path:
     * one entry costs <strong>139 bytes</strong> of record, and the total is exactly linear
     * in the count.
     *
     * <pre>
     * suppressed   record bytes   ns/record
     *          0            263     209,012
     *         16          2,493     633,687
     *      1,000        141,153   6,608,879
     *    100,000     14,289,153 338,289,269
     * </pre>
     *
     * <p>There is a cheaper shape still, because {@code addSuppressed} does not deduplicate:
     * <em>one</em> allocated throwable added a hundred thousand times writes 5,800,330 bytes
     * of "already reported above" — the identity map keeps it from being walked twice and
     * does not keep it from being <em>printed</em> a hundred thousand times.
     *
     * <p>Sixteen, and the measurement that fixes it is what a real suppressed list looks
     * like. A try-with-resources contributes one suppressed exception per resource it fails
     * to close, and <strong>all 27 try-with-resources statements in this repository declare
     * exactly one resource</strong>; nesting a handful is the realistic worst case. So
     * sixteen is more than an order of magnitude past the only arity that actually occurs,
     * and costs 2,493 bytes against the 263 an entry-free record costs — the same order as
     * the record itself.
     *
     * <p>It is deliberately the same number as {@link #MAX_CAUSE_DEPTH} rather than a second
     * one derived to three significant figures. The two bound one traversal on its two axes,
     * and a reader who has to hold two different numbers for the same walk will misremember
     * one of them.
     *
     * <p><strong>What the pair does and does not bound.</strong> Together with the identity
     * map they bound the <em>shape</em> the walk explores and what any one node contributes;
     * they do not bound the whole graph, because every node emitted is a distinct throwable
     * the caller allocated and is holding. That is the same claim {@link #MAX_CAUSE_DEPTH}
     * has always made for its own axis, and it is worth stating rather than leaving to be
     * inferred from a constant.
     */
    private static final int MAX_SUPPRESSED = 16;

    private Quoted() {
    }

    /**
     * {@code raw} with everything that could restructure a line replaced by an escape.
     *
     * <p>{@code \\uXXXX} for a code point in the basic plane and {@code \\UXXXXXXXX} for one
     * above it — {@code \\u} takes four hex digits, so a five-digit {@code \\uE0001} is not
     * an escape any decoder would read back as {@code U+E0001}, and every astral formatting
     * character (the tags block, the musical controls) is one.
     *
     * <p>{@code \} is escaped as {@code \\}, which is what makes the output injective: two
     * different names always quote differently, and un-escaping recovers the original
     * exactly. Ordinary text pays for it — a Windows-shaped name reads as
     * {@code a\\b.md} — and that is the trade for a log that can be read back.
     *
     * <p>{@code null} becomes {@code "null"} rather than throwing: this is called on the
     * failure path, and a logging helper that can itself fail is a bad trade.
     */
    public static String of(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(raw.length() + 8);
        int i = 0;
        while (i < raw.length()) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\\') {
                out.append("\\\\");
            } else if (restructures(cp)) {
                escape(out, cp);
            } else {
                out.appendCodePoint(cp);
            }
        }
        return out.toString();
    }


    /**
     * {@code raw} bounded and escaped to ASCII, so it still names what it names after a
     * fence and a normalisation pass have been over it, and inside single quotes.
     *
     * <h4>What this is for, and why {@link #of} is not enough</h4>
     *
     * <p>{@link #of} escapes the alphabet that can restructure a <em>line</em> — a
     * terminator, a format character, an unpaired surrogate — and that is the right
     * alphabet for a log record. It is not the right alphabet for text that will pass
     * through {@code Spotlight.neutralise}, which runs NFKC: folding is a property of the
     * string rather than of a code point, so {@code e} and {@code U+0301} are each
     * NFKC-stable alone and compose to {@code é} together, and {@code U+FF54 U+FF11} folds
     * to {@code t1}. Every ASCII string is an NFKC fixed point, carries no {@code \p{Cf}}
     * and cannot be folded by {@code strip}, so escaping to ASCII is the condition that
     * actually holds. It costs nothing in the ordinary case, where an id and a tool name
     * are ASCII already and this method is the identity.
     *
     * <p>The apostrophe is escaped last and separately, because every caller here drops the
     * result <em>inside</em> single quotes in a sentence somebody reads next, and
     * {@link #of} deliberately leaves {@code '} alone. Without it a name of the shape
     * {@code x' and also '} closes the framework's quote and re-opens it, so the reader is
     * shown a sentence with a clause the framework did not write in it.
     *
     * <h4>Bounded before it expands and again after</h4>
     *
     * <p>The same seam {@code Spotlight.fenceBounded} and {@link #failure} each run.
     * Escaping never shrinks, so nothing beyond {@code max} raw characters can reach the
     * first {@code max} escaped ones. Measured on the input {@code AgentTest} uses for exactly
     * this — 200,000 astral code points, 400,000 characters — the largest string this
     * method builds is <strong>615</strong> characters with the pre-cut and
     * <strong>2,000,000</strong> without it, for the same 135 characters out. Astral code
     * points are the case that needs it: {@link #of} passes an emoji through unescaped, so
     * until this escaping existed it was the identity on precisely the input the bound was
     * measured against.
     *
     * <p>The residual, stated rather than glossed: {@code Cut.bounded} backs off a
     * character rather than splitting a surrogate pair, so an input whose {@code max}-th
     * character is half a pair loses that pair before the escaping sees it, where cutting
     * afterwards would have carried the first character of its escape. The difference is
     * one character immediately before {@code Cut.MARKER}, in text that was being cut
     * either way, and it is not worth an unbounded intermediate.
     *
     * @param raw the caller-chosen text to echo; {@code null} becomes {@code "null"},
     *            for {@link #of}'s reason
     * @param max the ceiling in characters, applied on both sides of the escaping; must
     *            be &gt; 0
     */
    public static String distinguishably(String raw, int max) {
        String escaped = of(Cut.to(raw == null ? "null" : raw, max));
        StringBuilder out = new StringBuilder(escaped.length());
        int i = 0;
        while (i < escaped.length()) {
            int cp = escaped.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x80) {
                out.appendCodePoint(cp);
            } else if (cp > 0xFFFF) {
                out.append("\\U%08X".formatted(cp));
            } else {
                out.append("\\u%04X".formatted(cp));
            }
        }
        return Cut.to(out.toString().replace("'", "\\u0027"), max);
    }

    /**
     * {@link #of} deferred until something renders it.
     *
     * <p>An argument to {@code log.info} is evaluated whether or not the level is on, and
     * these inputs are model-written and unbounded: escaping half a megabyte of hostile text
     * for a statement that is then discarded is pure cost. Passed as an {@code Object}, slf4j
     * calls {@code toString} only if it is going to print.
     */
    public static Object lazily(Supplier<String> text) {
        return new Object() {
            @Override
            public String toString() {
                return text.get();
            }
        };
    }

    /**
     * {@code raw} with the messages in its cause chain escaped, for logging alongside a line.
     *
     * <p>The trailing {@code Throwable} argument is not a {@code {}} placeholder — the
     * logging framework renders it itself, message and stack trace, and no amount of
     * escaping the other arguments touches it. That is a live channel and not a theoretical
     * one: {@code OpenRouterLlmClient} puts a remote HTTP error body into an
     * {@code LlmException}, a filesystem exception carries the name of the file that was
     * planted, and a tool's exception message is whatever the tool's author put there, which
     * may be the model's own argument.
     *
     * <p>Keeps the original class name and stack trace, so the diagnostic survives; only the
     * messages are escaped. The class name is carried in the <em>message</em> and not only
     * in {@code toString}, because backends disagree about which they render: slf4j-simple
     * and log4j2's {@code %throwable} go through {@code printStackTrace}, which uses
     * {@code toString}, while logback builds its header from
     * {@code throwable.getClass().getName()} and never calls {@code toString} at all. A
     * wrapper that announced its type only through {@code toString} lost every exception
     * class under the more common of the two, so {@code IOException} and
     * {@code LlmException} — the first thing an operator triages — both read as this class.
     *
     * <p><strong>What this does cost.</strong> The object handed to the logger is not the
     * original, so anything downstream of the log call that filters by exception <em>type</em>
     * stops matching: a logback {@code EvaluatorFilter} on {@code IOException}, an
     * aggregator that groups by class. There is no way around that from a library — the
     * class of a {@code Throwable} cannot be borrowed — and it is the price of the message
     * being escaped at all. Filter on the escaped class instead, or escape in a layout
     * converter, which an application that owns its appender config can do and a framework
     * cannot.
     *
     * <p>Every throwable is wrapped once, tracked by identity the way
     * {@code Throwable.printStackTrace} tracks its own, so a chain is walked in nodes rather
     * than in paths. Without that a cause chain and a suppressed list that share nodes is a
     * graph, and re-wrapping per path turned nineteen objects into sixty-five thousand
     * wrappers and a hundred and seventeen megabytes. A repeat is reported as a circular
     * reference rather than followed.
     *
     * <p>The depth cap stays as a second bound, and where it stops it says so rather than
     * simply ending — the argument {@link Cut} makes for model-facing text is the same one
     * here: a chain that just stops reads as a chain that ended, and the root cause is the
     * end an operator was looking for.
     *
     * <p><strong>Each message is bounded too, which is a different question from how deep
     * the chain goes.</strong> See {@link #MAX_MESSAGE_CHARS} and
     * {@link #MAX_ESCAPED_MESSAGE_CHARS} for the two ceilings and the measurements behind
     * them. A cut is said twice: {@code Cut.MARKER} inside the message, and {@code
     * [message cut]} between the exception's class name and its colon — the one span here a
     * message cannot reach, which is what makes it worth printing. {@code Cut.MARKER} alone
     * is not enough and {@code Cut} says so of itself: it "is a hint to a reader, not
     * evidence about the writer", and any message can contain those characters.
     *
     * <p><strong>No log line, where every other bounded site in this framework writes
     * one.</strong> {@code ToolResult.failed} and {@code Synthesizers.fenceOf} both cut for
     * a <em>model</em> and tell the operator separately, because the party losing the text
     * and the party who can act on it are different. Here they are the same party: the
     * output of this method is itself the operator's line. A helper called from inside a
     * {@code log.warn} argument list, on paths written expressly to be un-abortable, is also
     * the last place that should start a second logging call.
     *
     * <p><strong>The suppressed list is bounded too, and the argument for leaving it open is
     * kept here because it was wrong in an instructive way.</strong> It ran: a suppressed
     * list is built by whoever wrote the {@code try}, so nothing in this framework produces
     * one and no input it accepts can. The first clause is true; the second does not follow
     * from it. <em>Whoever wrote the {@code try} is exactly who this framework catches.</em>
     * {@code Observations.ran} exists because an {@code AgentObserver} is somebody else's
     * code, and its default handler is {@code Observations.LOGGING}, which calls this method; a
     * {@code Tool} reaches the same helper through {@code Agent.runTool}. One
     * try-with-resources inside a loop over a large collection of closeables hands back a
     * single throwable carrying a hundred thousand suppressed nodes. The framework does not
     * construct that — it <em>receives</em> it, which is the same relationship it has to the
     * message on the line above, bounded for exactly that reason. Measured on the reachable
     * path, one record grew to 14,289,153 bytes and 338 ms. See {@link #MAX_SUPPRESSED}.
     *
     * <p>The generalisation worth keeping from the mistake: "the framework does not build
     * it" is not a bound. What matters is whether anything the framework <em>accepts</em>
     * chooses the size, and a callback interface is an input like any other.
     *
     * <p>What remains unbounded is the number of stack frames, and that answer is unchanged
     * because the reasoning differs rather than because the same reasoning was reused. A
     * real trace is filled in by the JVM and capped by it — {@code MaxJavaStackTraceDepth},
     * 1,024 by default. Only an explicit {@code setStackTrace} with a large synthesised
     * array escapes that, and unlike a suppressed list, which ordinary try-with-resources
     * produces as a side effect, that is a deliberate act rather than something a caller
     * arrives at by writing normal code. It is a residue, and it is named here rather than
     * argued away.
     *
     * <p>Eager, unlike {@link #lazily}, and it cannot be otherwise: slf4j treats a trailing
     * argument as a throwable only if it <em>is</em> one, so there is no deferred form to
     * pass. The cost is one wrapper per node on a path that is already failing.
     *
     * <p>The frames themselves are copied verbatim and rendered by {@code Throwable}. Every
     * field of a {@code StackTraceElement} is a string a public constructor accepts, so a
     * caller that synthesises frames can still put anything in a trace; nothing in this
     * framework does, and no input it accepts can.
     */
    public static Throwable failure(Throwable raw) {
        if (raw == null) {
            return null;
        }
        try {
            return escaped(raw, new java.util.IdentityHashMap<>(), MAX_CAUSE_DEPTH);
        } catch (RuntimeException | StackOverflowError helperFailed) {
            // The same trade {@link #of} makes for null, for the same reason and on a worse
            // path. getMessage and getStackTrace are overridable, so a throwable can be
            // built whose message computation throws — and several of the call sites are
            // catch blocks written expressly to be un-abortable ("a thrown tool must not
            // abort the run", "a failing tick must not cancel the timer, silently and for
            // good"). A logging helper that turns one failure into a different, fatal one
            // is worse than an unescaped line, so this reports what it can and stops.
            // Raw, not pre-escaped: head() escapes and bounds, and a message that reached
            // this branch is one a broken throwable produced, so it is no more trustworthy
            // and no shorter than the one that could not be read.
            return new Escaped(String.valueOf(helperFailed.getMessage()),
                    "a failure whose own message could not be read: "
                            + helperFailed.getClass().getName());
        }
    }

    private static Throwable escaped(Throwable raw, java.util.Map<Throwable, Boolean> seen,
                                     int depth) {
        if (seen.put(raw, Boolean.TRUE) != null) {
            return new Escaped("already reported above", "[circular reference]");
        }
        Throwable cause = raw.getCause() == null
                ? null
                : depth > 0 ? escaped(raw.getCause(), seen, depth - 1) : truncatedAt(raw.getCause());
        Escaped wrapped = new Escaped(raw, cause);
        Throwable[] suppressed = raw.getSuppressed();
        // Both reasons for stopping produce the same tail, which is why the count is taken
        // once rather than per branch: past MAX_SUPPRESSED, and past the depth cap, which
        // used to drop the whole list in silence. Measured on a 21-layer chain each
        // carrying one suppressed resource: five layers lost theirs with nothing anywhere
        // saying so, and the only "not walked" line in the render was the cause chain's.
        int shown = depth > 0 ? Math.min(suppressed.length, MAX_SUPPRESSED) : 0;
        for (int i = 0; i < shown; i++) {
            wrapped.addSuppressed(escaped(suppressed[i], seen, depth - 1));
        }
        if (suppressed.length > shown) {
            wrapped.addSuppressed(notWalked(suppressed.length - shown));
        }
        return wrapped;
    }

    /**
     * The marker that stands where a suppressed list too wide to walk was cut.
     *
     * <p>The sibling of {@link #truncatedAt} and deliberately the same shape: an entry in
     * the list itself, saying how many are missing, so the loss is where the loss happened.
     * Dropping it would be {@code [message cut]}'s defect one field over — a reader cannot
     * see a suppressed exception that is not there, and a list that simply stops reads as a
     * list that ended.
     *
     * <p>It is a count this class computed and a fixed sentence it wrote, so nothing the
     * throwable chose reaches it.
     */
    private static Throwable notWalked(int more) {
        return new Escaped(more + " more suppressed exception(s), not walked",
                "[suppressed list cut]");
    }

    /** The marker that stands where a chain too deep to walk was cut. */
    private static Throwable truncatedAt(Throwable remaining) {
        int more = 1;
        Throwable next = remaining;
        // Counted, not guessed, and bounded so a cycle here cannot spin either.
        while (more < 1_000 && next.getCause() != null && next.getCause() != next) {
            next = next.getCause();
            more++;
        }
        return new Escaped(more + " more cause(s), not walked", of(remaining.getClass().getName()));
    }

    /**
     * {@code raw} as one delimited string, for the log sites that report a batch.
     *
     * <p>Returns a {@code String} rather than a {@code List}, and that is the fix for the
     * same defect one level up. Handing back a list left the rendering to
     * {@code AbstractCollection.toString}, whose delimiter is {@code ", "} and whose
     * brackets are unescaped — so a file named {@code "innocent.md, secrets.md"} rendered
     * exactly as two ignored files, and a {@code ]} in a name closed the list early. The
     * line was safe and the list printed inside it was not.
     *
     * <p>Each element is quoted the way {@code MemoryKeys} quotes a key it refuses, with
     * the quote itself escaped, so an element cannot end its own entry.
     *
     * <p>{@code limit} caps what is printed. These lists are unbounded — one entry per
     * non-key file in a directory anything may write to — and a ninety-thousand-character
     * warning is not read by the operator it is for.
     */
    public static String each(List<String> raw, int limit) {
        if (raw == null || raw.isEmpty()) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[");
        // Clamped rather than trusted. A negative limit made the loop run zero times and
        // the tail over-count what it had skipped: each(List.of("a", "b"), -1) rendered
        // "[, and 3 more]", a count of three from a list of two.
        int shown = Math.min(Math.max(limit, 0), raw.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append('\'').append(of(raw.get(i)).replace("'", "\\u0027")).append('\'');
        }
        if (raw.size() > shown) {
            out.append(", and ").append(raw.size() - shown).append(" more");
        }
        return out.append(']').toString();
    }

    /** {@link #each(List, int)} showing at most twenty. */
    public static String each(List<String> raw) {
        return each(raw, 20);
    }

    /**
     * Whether {@code codePoint} could restructure the text it lands in.
     *
     * <p>Wider than the C0 controls on purpose. {@code U+2028} and {@code U+2029} are line
     * breaks to most renderers and are not ISO controls; the bidi overrides and the
     * zero-width formatters are neither. An unpaired surrogate is not a character at all and
     * has no business being written down.
     */
    public static boolean restructures(int codePoint) {
        return restructuresALine(codePoint) || isUnpairedSurrogate(codePoint);
    }

    /**
     * Whether {@code codePoint} could end or reorder the line it lands in.
     *
     * <p>Separate from {@link #isUnpairedSurrogate} because {@code MemoryKeys} refuses keys
     * for the two reasons distinctly and says which — a caller told only "bad key" cannot
     * fix it. {@link #restructures} is their disjunction, which is what a log needs.
     *
     * <p>{@code Character.FORMAT} is included because it is cheap, not because it is the
     * cut: it catches the bidi overrides and isolates and the zero-width joiners, and it
     * also catches {@code U+00AD} and {@code U+200C}, which some scripts need
     * orthographically. It misses {@code U+3164} HANGUL FILLER, {@code U+115F},
     * {@code U+FE00} and {@code U+034F}, all of which take no advance width and show
     * nothing. Combining marks in general are the wrong example — {@code U+0301} takes no
     * width either but draws a visible accent, so {@code a.md} and {@code á.md} do not look
     * alike. See the class note: invisibility is not the property this promises.
     */
    public static boolean restructuresALine(int codePoint) {
        if (Character.isISOControl(codePoint)) {
            return true;
        }
        return switch (Character.getType(codePoint)) {
            case Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.FORMAT -> true;
            default -> false;
        };
    }

    /** Whether {@code codePoint} is half of a surrogate pair with no other half. */
    public static boolean isUnpairedSurrogate(int codePoint) {
        // The magnitude test first: (char) truncation turns U+1D800 into U+D800, so casting
        // alone would call perfectly good supplementary code points surrogates.
        return codePoint <= 0xFFFF && Character.isSurrogate((char) codePoint);
    }

    /** Appends {@code \\uXXXX} or {@code \\UXXXXXXXX} without running a formatter. */
    private static void escape(StringBuilder out, int codePoint) {
        // String.format cost 369ns per escaped character against 6ns for pass-through, a
        // sixty-fold constant paid on exactly the input an attacker chooses the length of.
        if (codePoint > 0xFFFF) {
            out.append("\\U");
            for (int shift = 28; shift >= 0; shift -= 4) {
                out.append(HEX[(codePoint >> shift) & 0xF]);
            }
            return;
        }
        out.append("\\u");
        for (int shift = 12; shift >= 0; shift -= 4) {
            out.append(HEX[(codePoint >> shift) & 0xF]);
        }
    }

    /**
     * A {@link Throwable} that renders like the one it wraps, with its message escaped.
     *
     * <p>{@code toString} is what the logging framework prints as the header of a stack
     * trace and after {@code "Caused by: "}, so overriding it keeps the original class name
     * visible while the message goes through {@link #of}.
     */
    private static final class Escaped extends Throwable {

        private static final long serialVersionUID = 1L;

        Escaped(Throwable raw, Throwable cause) {
            // One string, used as both the message and the toString. A backend that renders
            // the message alongside its own idea of the class shows the original type after
            // this one; a backend that renders toString shows exactly what the original
            // would have. Neither loses it, and getMessage and toString cannot disagree.
            super(header(raw), cause);
            setStackTrace(raw.getStackTrace());
        }

        Escaped(String message, String ofClass) {
            super(head(ofClass, message), null);
            setStackTrace(new StackTraceElement[0]);
        }

        private static String header(Throwable raw) {
            return raw.getMessage() == null
                    ? raw.getClass().getName()
                    : head(raw.getClass().getName(), raw.getMessage());
        }

        /**
         * {@code name}, then {@code message} escaped and bounded, with the cut declared in
         * the half a message cannot reach.
         *
         * <p>One seam for all four throwables this class builds — a wrapped one, a circular
         * reference, a chain too deep to walk, and the fallback for a throwable whose own
         * message could not be read. They differ in where {@code name} comes from and not in
         * what may be done to a message, and two spellings of a bound are two bounds that
         * come to disagree.
         *
         * <p><strong>Cut, escape, cut.</strong> The inner cut bounds the work — {@link #of}
         * is the expanding pass, so escaping first would be doing the expensive thing to
         * text chosen by whoever threw — and the outer bounds what is written.
         *
         * <p>The two answers are OR-ed rather than recovered from lengths (#155), and
         * <strong>neither disjunct is redundant, because the ceilings differ</strong>. Each
         * has a witness the other misses: 4,001 plain characters cut at
         * {@link #MAX_MESSAGE_CHARS} to 4,016, which is well inside
         * {@link #MAX_ESCAPED_MESSAGE_CHARS}, so only the inner answer is true; 4,000
         * newlines pass the inner ceiling untouched and escape to 24,000, so only the outer
         * one is. Both are pinned. Had the two ceilings been equal the inner answer would
         * have been subsumed — an inner cut appends {@link Cut#MARKER} and {@link #of} never
         * shrinks — and the OR would have been a dead disjunct dressed as a control.
         *
         * <p>{@code name} itself is not bounded and not escaped, which is unchanged. It is a
         * loaded class's binary name or a sentence this class wrote; neither is reachable
         * from anything the framework accepts, and shortening a class name is the one thing
         * an operator triaging by type cannot afford.
         */
        private static String head(String name, String message) {
            Cut.Bounded work = Cut.bounded(message, MAX_MESSAGE_CHARS);
            Cut.Bounded emitted = Cut.bounded(of(work.text()), MAX_ESCAPED_MESSAGE_CHARS);
            return name + (work.cut() || emitted.cut() ? " [message cut]" : "")
                    + ": " + emitted.text();
        }

        @Override
        public String toString() {
            return getMessage();
        }
    }
}
