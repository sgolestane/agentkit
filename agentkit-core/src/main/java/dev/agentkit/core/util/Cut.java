package dev.agentkit.core.util;

import java.util.Objects;

/**
 * Cuts model-written text to a character ceiling, and says so where it cut.
 *
 * <p>Two things a naive {@code substring} gets wrong, both of which this repo has shipped
 * and had to fix.
 *
 * <p>The first is the surrogate pair. A cut at a fixed index can land between the halves of
 * an astral character, and the resulting string does not survive a UTF-8 round trip — it is
 * not text any more, and whatever reads it next (a trace, an eval, a JSON encoder) is
 * holding something malformed. Every cut in this repository that is not code-point-safe has
 * had to earn it by reducing to ASCII first; {@code Spotlight.label} was the one that did,
 * and #233 removed it, so nothing is exempt any more.
 *
 * <p>The second is silence. Text that simply stops reads to a model as text that ended,
 * so a cut plan looks like a complete one and a cut post looks like a finished thought.
 * {@link #MARKER} is what says otherwise, and it is inside the cut text rather than in a
 * header because the header is a different span — a reader of one entry among many needs
 * to know which entry lost its tail.
 *
 * <p>A ceiling here is a ceiling on the text, not on what it costs once fenced: NFKC and
 * marker removal both expand, and both are chosen by whoever wrote the body. Pass the text
 * through {@code Spotlight.sizedAsFenced} first where the number has to hold — the callers
 * that budget a prompt all do.
 *
 * <p><strong>Three periods, not an ellipsis</strong> (#108). U+2026 has an NFKC
 * compatibility decomposition to {@code ...}, so the marker this class appended was itself
 * the one thing {@code Spotlight.sizedAsFenced} would go on expanding — two characters,
 * every time, in text that had just been sized precisely so the number would hold. Measured
 * through {@code MessagingTools.fenced} at a 4,000-character bound: a body of 4,001 emitted
 * 4,015 where the ceiling said 4,013.
 *
 * <p>The magnitude never mattered and the invariant did. Two callers do
 * {@code cut -> sizedAsFenced -> cut} <em>because</em> that ordering is supposed to make the
 * emitted length knowable, and a 40,000-case fuzz over marker fragments, format characters,
 * fullwidth forms, combining marks, astral characters and cut offsets found the worst growth
 * anywhere was 2, on the input {@code "g[ellipsis] [truncated]"}. The seam was exactly as
 * advertised and its own marker was the single thing falsifying it. Three periods read the
 * same and survive every pass.
 *
 * <p>Not every truncation in the repo comes through here. {@code BlackboardTools.topicLabel}
 * cuts a topic it has already reduced to ASCII, so the surrogate question cannot arise. This
 * is the cut for a body a model or a peer wrote that is about to be shown to a model, and it
 * is used at every such site: {@code WorkingMemory}, {@code BlackboardTools},
 * {@code PlanningAgent} and {@code KnowledgeTools}. {@code NodeInput} had its own wording
 * for a dependency's output until #72 moved that rendering onto
 * {@code Spotlight.fenceBounded}, which cuts through here.
 */
public final class Cut {

    /**
     * What a cut leaves in place of the text it dropped.
     *
     * <p>Note that a cut result is therefore up to this many characters longer than the
     * ceiling asked for. A caller budgeting a whole rendering should measure the result
     * rather than assume the argument.
     *
     * <p>It is a hint to a reader, not evidence about the writer. Any body can contain
     * these characters, so nothing may treat its presence as proof that this class put it
     * there, or its absence as proof that nothing was dropped. The ellipsis form it
     * replaced was no better on that count — {@code Spotlight}'s NFKC pass turned U+2026
     * into the same three periods before any reader saw it — so #108 changed what the
     * marker costs, not what it proves.
     */
    public static final String MARKER = "... [truncated]";

    private Cut() {
    }

    /**
     * A cut result and whether the cut fired, for a caller that has to say so.
     *
     * <p><strong>Cut-ness is carried, not re-derived</strong> (#155). Every attempt to
     * recover it afterwards from lengths alone has been wrong at least once. {@code !=}
     * is wrong because a body of exactly {@code max + MARKER.length()} comes back the
     * length it went in, so equality calls a cut body complete; {@code >} on the
     * <em>post</em>-normalisation length is wrong in the other direction, because
     * {@code Spotlight.neutralise} shrinks as well as grows — {@code \p{Cf}} removal
     * takes a body of a hundred thousand zero-width joiners down to nothing, so a reply
     * cut at the work ceiling normalises to under the output ceiling and reports itself
     * complete. Only the cut itself knows, and only at the moment it happens.
     *
     * @param text what the cut left; never {@code null}
     * @param cut  whether anything was dropped, which is not recoverable from
     *             {@code text.length()}
     */
    public record Bounded(String text, boolean cut) {
    }

    /**
     * {@code text} cut to at most {@code max} characters plus {@link #MARKER}, never
     * between a surrogate pair.
     *
     * <p>Prefer {@link #bounded(String, int)} at a site that reports the cut in a header
     * the model reads. This overload answers only what the text became, and a caller that
     * asks the result how it got there is asking a question lengths cannot answer (#155).
     *
     * @param text the text to cut; never {@code null}
     * @param max  the ceiling in characters; must be &gt; 0
     * @return {@code text} unchanged when it already fits
     */
    public static String to(String text, int max) {
        return bounded(text, max).text();
    }

    /**
     * As {@link #to(String, int)}, and says whether it cut.
     *
     * <p><strong>For the {@code cut -> sizedAsFenced -> cut} seam.</strong> Neither cut
     * can be recovered from the other's lengths — the inner one is taken on the raw text
     * and the pass between the two both expands and shrinks — so a caller doing both ORs
     * the two answers rather than comparing anything. That is what
     * {@code Spotlight.fenceBounded} and {@code MessagingTools.fenced} do, and it is what
     * the latter did not, which is how a reply of 406,682 characters arrived carrying 15
     * of them under a header that reported no cut at all.
     *
     * <p>{@code BlackboardTools.render} runs the same seam and has not been moved onto
     * this yet, so the defect is still live there — a post of format characters ahead of
     * the real text renders as a marker under a header that counts no cut posts (#155).
     *
     * @param text the text to cut; never {@code null}
     * @param max  the ceiling in characters; must be &gt; 0
     * @return what the cut left, and whether it fired
     */
    public static Bounded bounded(String text, int max) {
        Objects.requireNonNull(text, "text");
        if (max <= 0) {
            throw new IllegalArgumentException("max must be > 0");
        }
        if (text.length() <= max) {
            return new Bounded(text, false);
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return new Bounded(text.substring(0, end) + MARKER, true);
    }
}
