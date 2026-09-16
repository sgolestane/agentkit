package dev.agentkit.core.util;

import java.util.regex.Pattern;

/**
 * Collapses text that is about to become one line of a list.
 *
 * <p>A listing built as {@code "- " + item + "\n"} — a skill catalog, working notes,
 * recalled lessons, tool descriptions, a blackboard header — is structure the model reads
 * as the framework's. An item carrying a line terminator writes entries of its own into
 * it: a skill that does not exist, a note nobody recorded, a post attributed to another
 * agent. Fencing the listing does not answer this, because the forged entry lands inside
 * the same fence as the real ones and is indistinguishable from them; what answers it is
 * the item being unable to end its line.
 *
 * <p>Only for items that are <em>a line</em>. A body that may legitimately run to
 * paragraphs is a different problem and wants its own fence — see {@code BlackboardTools},
 * which flattens the topic on the header line and fences each post's content separately.
 *
 * <p>Applied to catalog <em>descriptions</em> anyway ({@code SkillLibrary},
 * {@code LlmPlanner}, {@code DisclosingToolRegistry}), and that is a compromise rather
 * than the rule: an MCP description can be several paragraphs, and collapsing it costs the
 * model some legibility. It is where those catalogs are today because a forged entry is
 * worse than an ugly one; fencing per entry, as the blackboard does, is the better answer
 * when someone wants it.
 *
 * <h2>Why two passes</h2>
 *
 * <p>The obvious single expression, {@code \s*\R\s*}, puts two unbounded quantifiers over
 * overlapping classes either side of the anchor — {@code \s} contains most of {@code \R} —
 * so a whitespace run with no line terminator in it rescans from every position. 64 KB of
 * plain spaces took ten seconds, on text a model chose. {@code Spotlight}'s marker pattern
 * carries the same warning; this is the third place the repo has met it.
 */
public final class OneLine {

    /** Every Unicode line terminator: wider than {@code \n}, and wider than {@code \s}. */
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    /** Possessive: nothing after it needs the backtracking, and dropping it keeps this linear. */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s++");

    private OneLine() {
    }

    /**
     * {@code text} with every line terminator and run of whitespace collapsed to a single
     * space, and the ends trimmed.
     */
    public static String of(String text) {
        java.util.Objects.requireNonNull(text, "text");
        return WHITESPACE_RUN.matcher(LINE_BREAK.matcher(text).replaceAll(" "))
                .replaceAll(" ").strip();
    }
}
