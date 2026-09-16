package dev.agentkit.core.memory;

import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.OneLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * In-session scratch memory: an ordered list of short notes the agent jots to
 * itself while pursuing a goal.
 *
 * <p>Unlike {@link MemoryStore}, working memory is <em>transient</em> — it lives
 * for the duration of one run and is not persisted. Two ways to surface notes to
 * the model: expose the {@code remember}/{@code recall} tools (so the model reads
 * them back on demand), or call {@link #render()} and inject the result into the
 * system prompt / a context message yourself. Automatic injection into the
 * engineered context is a caller responsibility, not built into the agent loop.
 * Either way the notes arrive fenced. If you place {@link #render()} yourself rather than
 * letting an {@code Agent} build the request, the fence is only half of it — pass the
 * prompt through {@link Spotlight#withInstruction} too, since a fence the prompt never
 * explains is decoration. Note also that {@code AgentConfig.systemPrompt} is fixed when
 * the agent is built, so notes rendered into it are whatever existed then; a per-turn
 * context message is the position that keeps up. Not thread-safe.
 */
public final class WorkingMemory {

    /**
     * A note with nothing in it to read. {@code \s} is ASCII-only, so a note of one
     * non-breaking space passed {@code isBlank} and rendered as an empty bullet;
     * {@code \p{Z}} is what covers the rest, the same pairing {@code Spotlight} uses.
     */
    private static final Pattern BLANK = Pattern.compile("[\\s\\p{Z}]*+");

    /**
     * The characters {@link #render()} may spend, when a caller does not say.
     *
     * <p>Characters and not a count of notes, because characters are the cost. Fifty notes
     * is anywhere between three hundred bytes and twenty-five thousand, and it is the
     * second number the system prompt pays on every turn. Three of the four bounds this
     * class was measured against — {@code PlanningAgent}, {@code NodeInput},
     * {@code Spotlight} — bound characters for that reason; only {@code LessonBook} bounds
     * a count, and a lesson block enters the goal once per attempt rather than the system
     * prompt every turn.
     */
    public static final int DEFAULT_MAX_RENDER_CHARS = 8_000;

    /** How much of one note {@link #render()} will show before cutting it. */
    private static final int MAX_RENDERED_NOTE_CHARS = 500;

    /**
     * A ceiling on what one note may occupy in memory. Not a prompt bound — that is
     * {@link #DEFAULT_MAX_RENDER_CHARS} — but a guard on the allocation, set far above
     * anything anyone would call a note so it cannot clip a real one.
     */
    private static final int MAX_STORED_CHARS = 20_000;

    private final List<String> notes = new ArrayList<>();

    private final int maxRenderChars;

    /** Working memory spending at most {@link #DEFAULT_MAX_RENDER_CHARS} on a render. */
    public WorkingMemory() {
        this(DEFAULT_MAX_RENDER_CHARS);
    }

    /**
     * Working memory spending at most {@code maxRenderChars} on {@link #render()}.
     *
     * <p>Bounded at the prompt and not at the store, and the distinction is narrower than
     * it first looks — worth stating plainly, because a first version of this class got it
     * wrong in exactly the way the wording invites. A note the render leaves out is
     * invisible to the model whether or not it still sits in the list. What the store buys
     * is not the model's access but everyone else's: {@link #notes()} answers for an audit,
     * an eval, or a trace, and {@link #renderAll()} lets the {@code recall} tool hand back
     * what the prompt could not afford.
     *
     * <p>So the rule is about <em>where the cost falls</em>, not about who can see what. A
     * tool result is paid once; the system prompt is paid every turn. Bounding the second
     * hard and the first loosely is the whole of it.
     *
     * @param maxRenderChars the characters {@link #render()} may spend; must be &gt; 0
     */
    public WorkingMemory(int maxRenderChars) {
        if (maxRenderChars <= 0) {
            throw new IllegalArgumentException("maxRenderChars must be > 0");
        }
        this.maxRenderChars = maxRenderChars;
    }

    /**
     * Appends a note. Blank notes are ignored, and line breaks within one are flattened.
     *
     * <p>Flattened because {@link #render()} lays the notes out as one bullet per line, so
     * a note carrying a newline writes bullets of its own — notes the model reads back as
     * things it decided earlier and nobody wrote. A note is a short fact or decision, as
     * the {@code remember} tool describes it, so it has no use for the line break; the same
     * treatment {@code LessonBook.record} gives a lesson, which is bulleted the same way.
     *
     * <p>Flattened rather than refused, where {@link MemoryKeys} refuses a key carrying the
     * same characters. The difference is what the writer can do about it: a key is an
     * identifier the model can simply pick again, so a rejection costs one retry, while a
     * note is prose it cannot re-say — refusing it would lose the thought to punish the
     * formatting.
     *
     * <p>{@link OneLine} does the collapsing, and carries the reason it is not the one
     * regular expression it looks like — the note argument is the field a hostile turn most
     * directly controls, and the obvious spelling is quadratic on it.
     *
     * <p>Stored as written, past a ceiling so far above a note that it cannot clip one.
     * Cutting here is what a first version did, and it was the thing this class had just
     * argued against: a bound at the store loses the end of a thought irrecoverably, so
     * {@link #notes()} — the audit path — answers with something the model never wrote.
     * The prompt is where the cost is, so the prompt is where the cut is.
     */
    public WorkingMemory note(String text) {
        Objects.requireNonNull(text, "text");
        String flattened = OneLine.of(text);
        if (BLANK.matcher(flattened).matches()) {
            return this;
        }
        notes.add(Cut.to(flattened, MAX_STORED_CHARS));
        return this;
    }

    /**
     * An unmodifiable snapshot of the notes, oldest first — as recorded, which is not
     * always as rendered.
     *
     * <p>{@link #render()} fences, and fencing NFKC-normalises: a note stored as
     * {@code ﬁle} is shown to the model as {@code file}, and two notes differing only by a
     * ligature or a zero-width character render alike. This is the method to read for an
     * audit or an eval, and {@code render()} is the one to read for what the model saw.
     */
    public List<String> notes() {
        return List.copyOf(notes);
    }

    public boolean isEmpty() {
        return notes.isEmpty();
    }

    public void clear() {
        notes.clear();
    }

    /**
     * Renders the notes as a header plus a fenced bulleted block, or empty string if there
     * are none. Already fenced: place it, do not wrap it again.
     *
     * <p>Fenced because a note is the model's own text and this method's whole purpose is
     * to put it back where the model will read it — including, as the class doc suggests,
     * the system prompt, which is the one position carrying the operator's authority. That
     * is worth doing even though the notes are the run's own, because a note <em>outlives
     * compaction</em>: {@code SummarizingCompactor} drops the turn that wrote it out of the
     * history while the note stays, so what comes back may be the residue of a turn the
     * model can no longer see and cannot re-examine.
     *
     * <p>{@link Spotlight.Kind#EVIDENCE}, which is the strict reading and costs something
     * real, so the trade is worth stating. A note is usually a fact — <em>the user is
     * Alice</em>, <em>the flight is $500</em> — and evidence is exactly right for a fact.
     * Where it bites is the directive note, <em>confirm before booking</em>, which the
     * model is now told to weigh rather than obey. That is the case a prompt-injected turn
     * would write, so the kind that degrades is the kind that degrades the attack; and
     * {@code ADVISORY} would have this method hand back "act on it where it improves the
     * work" over text an injected turn chose, which is more endorsement than these notes
     * had before they were fenced at all.
     *
     * <p>Not the reading {@code SummarizingCompactor} gets for its summary, though both are
     * this run's own progress returning to it. A summary is written by a separate,
     * tool-less call that reads the transcript already fenced as evidence, so an injected
     * instruction has to survive that pass to reach the prompt; a note is stored verbatim,
     * and it replaces nothing, so the argument that a summary must be advisory because it
     * stands in for the history it replaced does not carry over.
     *
     * <p>One cost of fencing here rather than in {@code recallTool}: a recall result now
     * carries markers, and {@code SummarizingCompactor} fences the whole transcript, so
     * under compaction these markers are neutralised to {@code [fence marker removed]}
     * inside the outer fence. Safe — the outer fence is the stricter one — but the notes
     * reach the summariser as noise rather than as a labelled span.
     *
     * <p>The header stays outside the fence — it is ours, and fencing it would tell the
     * model to disregard our own framing. Use {@link #notes()} where you want the text
     * itself rather than something to show a model.
     */
    public String render() {
        return render(maxRenderChars, MAX_RENDERED_NOTE_CHARS);
    }

    /**
     * Every note, however many characters that takes — what the {@code recall} tool hands
     * back.
     *
     * <p>Unbounded on purpose, and it is the other half of the rule {@link #render()}
     * follows rather than an exception to it. A tool result is paid once, when the model
     * asked for it; the system prompt is paid on every turn whether it is read or not. A
     * first version bounded both, which left the model told it had a hundred and thirty
     * notes, shown fifty, and given no way to reach the rest — amnesia it could see and
     * could not do anything about.
     */
    public String renderAll() {
        return render(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    private String render(int budget, int perNote) {
        if (notes.isEmpty()) {
            return "";
        }
        // Newest first, because the last thing decided is the thing most likely to matter,
        // then flipped back so the block still reads in the order it was written.
        List<String> shown = new ArrayList<>();
        int spent = 0;
        int cutNotes = 0;
        for (int i = notes.size() - 1; i >= 0; i--) {
            // Normalised before it is measured, because everything the fence does to a body
            // it does after a naive budget has measured, and two of those passes expand on
            // text the writer chose: NFKC, where one U+FDFA becomes eighteen characters, and
            // marker removal, where ten characters of '<untrusted' become twenty-two. The
            // first turned an eight-thousand character budget into a hundred and thirty-five
            // thousand; the second, once the first was fixed, into 16,976.
            String normalised = Spotlight.sizedAsFenced(notes.get(i));
            String note = Cut.to(normalised, perNote);
            int cost = note.length() + 3;
            if (!shown.isEmpty() && spent + cost > budget) {
                break;
            }
            shown.add(note);
            spent += cost;
            // Counted after the break and not before it, or the header reports a note that
            // was cut and then dropped: fifteen shown, sixteen said to have been cut.
            //
            // Measured against the ceiling rather than by comparing the two lengths, for
            // the reason Cut.Bounded writes out: Cut.to appends a marker, so a note of
            // exactly perNote + Cut.MARKER.length() comes back the length it went in, and
            // equality reads it as complete while a marker's worth of characters has gone
            // (#149). Sound here because there is one cut and it is taken on the text this
            // comparison measures — perNote is the ceiling handed to that cut. A caller
            // that cuts twice cannot ask a length at all and takes Cut.bounded's answer
            // instead; Spotlight.fenceBounded and MessagingTools.fenced do (#155).
            if (normalised.length() > perNote) {
                cutNotes++;
            }
        }
        java.util.Collections.reverse(shown);

        StringBuilder sb = new StringBuilder();
        for (String note : shown) {
            sb.append("- ").append(note).append('\n');
        }
        // Outside the fence, because the count is ours. Said at all, because a model that
        // cannot see the note it wrote fifty turns ago should be told the note existed
        // rather than left to conclude it never made the decision — and told, now, that
        // 'recall' is where the rest of them are.
        StringBuilder header = new StringBuilder("Working notes");
        if (shown.size() < notes.size()) {
            header.append(" (the most recent ").append(shown.size())
                    .append(" of ").append(notes.size()).append("; call 'recall' for all)");
        }
        if (cutNotes > 0) {
            header.insert(header.length(), cutNotes == 1
                    ? " — one note was cut to " + MAX_RENDERED_NOTE_CHARS + " characters"
                    : " — " + cutNotes + " notes were cut to "
                            + MAX_RENDERED_NOTE_CHARS + " characters");
        }
        return header + ":\n" + Spotlight.wrap(Spotlight.Kind.EVIDENCE, Source.of("working-notes"),
                sb.toString().stripTrailing());
    }
}
