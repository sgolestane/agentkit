package dev.agentkit.core.context;

import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ToolResultBlock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounds how much of a run's transcript is failure text somebody else wrote, and collapses
 * a failure the run has already read (#151).
 *
 * <h2>The defect: a per-fence bound is not a bound</h2>
 *
 * <p>{@code ToolResult.failed} cuts one detail at 4,000 characters, which stops one failure
 * deciding what the run spends. It says nothing about how many failures there are, and a
 * tool that fails on every call — a flaky network tool, a gate refusing a loop the model
 * keeps retrying — pays that price again per attempt. Measured on the pre-fix branch, one
 * tool throwing a 200,000-character message, called N times in one run — the size of the
 * message past {@code ToolResult}'s own 4,000-character cap changes none of these figures,
 * which is why the tests that pin them throw 4,100 and not 200,000:
 *
 * <pre>
 *   N     third-party chars in the transcript
 *   1                  4,136
 *   8                 33,088
 *  32                132,352
 * 256              1,058,816
 * </pre>
 *
 * <p>Exactly linear at 4,136 characters a call, and uncapped. The transcript is re-sent on
 * every turn, so the run's <em>cumulative</em> cost is quadratic in N rather than linear:
 * the same measurement summed over every request the model received was 148,896 characters
 * at N=8, 2,183,808 at N=32 and <strong>136,057,856 at N=256</strong>.
 *
 * <p>After this editor, at the same N, the transcript carries at most
 * {@link #DEFAULT_MAX_TOTAL_FAILURE_CHARS} of third-party text whatever N is — 4,136 when
 * the failures repeat and 12,408 when every one differs — and the cumulative figure falls to
 * 2,113,496 at N=256 and grows linearly rather than quadratically. What is left behind grows
 * instead by 79 or 56 characters a dropped call, and those are sentences this class wrote.
 *
 * <p>Repetition is also a channel of its own, which is the half a character budget does not
 * cover. A body replayed forty times is a stronger signal to a model than the same body
 * once, and the fence says who wrote it but not how many times it has been said.
 *
 * <h2>Where it can be fixed, and where it cannot</h2>
 *
 * <p>Not at {@code ToolResult.failed}: that is a static factory handed one detail, with no
 * way to know it is the fortieth. A total is run state, and the transcript is where a run
 * keeps it — so this is a {@link ContextEditor}, which {@link ContextStrategy} already calls
 * "the single hook through which context engineering (editing, compaction, budgeting) is
 * applied by the agent loop".
 *
 * <p>Being an editor also settles the determinism question {@code TrustFloor} had to answer
 * the other way. A floor may not be derived from the transcript because compaction would
 * silently lift it; a <em>budget</em> re-derived from the transcript every turn is the same
 * property read as a virtue — there is no counter to leak between runs, replay reaches the
 * same answer, and {@link #edit} is idempotent, so the agent loop persisting its output
 * through {@code Conversation.replaceAll} changes nothing on the next pass.
 *
 * <h2>What it does</h2>
 *
 * <p>Nothing at all, unless the transcript is already over budget. Then, newest first, over
 * {@link ToolResultBlock}s with {@code isError}:
 *
 * <ul>
 *   <li>a block whose content the walk has already kept is an exact repeat, and is replaced
 *       by {@link #REPEATED_PLACEHOLDER} — the text is still in the transcript, later;</li>
 *   <li>otherwise it is kept while the budget lasts, and replaced by
 *       {@link #OVER_BUDGET_PLACEHOLDER} once it does not.</li>
 * </ul>
 *
 * <p><strong>Collapsing repeats unconditionally was measured and rejected.</strong> #151
 * names repetition as a channel in its own right — "a body that gets replayed forty times is
 * a stronger signal to a model than the same body once" — which argues for collapsing
 * whether or not the budget is tight. Measured on twenty repetitions of
 * {@code Connection refused: api.example.com:443}, which render at 160 characters each: the
 * unconditional form takes the transcript from 3,200 characters to 1,661, saving 1,539
 * against a budget it was never within 12,000 of, and spends nineteen legible error strings
 * to do it. Every one of those was a fact about the run that an operator reading the
 * transcript can act on. So the collapse is kept as the thing that decides <em>who pays</em>
 * once the budget does bite — a repeat costs nothing to drop, because it can still be read
 * further down, and dropping one saves a distinct failure from being dropped instead — and
 * it is not a rewrite an in-budget run is subjected to. Measured after: an ordinary run of
 * 5, 10, 20 or 40 short failures, identical or all distinct, comes out byte for byte
 * unchanged.
 *
 * <p>Successful results are not touched. They are {@code ClearToolResultsEditor}'s business
 * and the two compose; this one is about the surface #151 names, where the text is written
 * by whoever failed rather than by the tool the caller chose to call.
 *
 * <h2>Three things that are not obvious</h2>
 *
 * <p><strong>The repeat is byte-exact, and that is a fact rather than a hope.</strong> A
 * fence's nonce is {@code SHA-256} of the body, so two failures carrying the same detail
 * from the same source render to the same characters. Measured: same detail — equal; a
 * different detail — not equal; the same detail from a different tool — not equal. So exact
 * content equality is the whole of the test, with no normalisation and nothing a payload can
 * steer.
 *
 * <p><strong>Nothing is deleted, because the wire format forbids it.</strong> #151 proposed
 * "one fence plus a count"; a {@code tool_result} must exist for every {@code tool_use}, so
 * the count is spent as one short framework-written block per dropped call instead. That is
 * the same constraint {@code ClearToolResultsEditor} works under, and it buys something back:
 * every dropped failure keeps its position, so the model reads the stub exactly where the
 * text used to be.
 *
 * <p>It also means the ceiling is on the third party and not on the rendering, and the
 * javadoc has to say which. What a payload contributes is bounded absolutely, at
 * {@link #DEFAULT_MAX_TOTAL_FAILURE_CHARS}, however many times it is invited to speak.
 * What is left over — 79 characters for a collapsed repeat, 56 for an over-budget drop —
 * scales with the number of tool calls, which is the caller's own {@code maxSteps} and not
 * anybody else's choice, and carries fixed text with nothing in it. Measured at N=256:
 * 1,058,816 characters of somebody else's words become 4,136 of theirs and 20,145 of the
 * framework's.
 *
 * <p><strong>That is why there is no header, where {@code Synthesizers} needed one.</strong>
 * A supervisor's dropped outcomes have nowhere to appear in the rendering, so a notice above
 * the headings is the only place the loss can be stated. Here every loss has a slot, and a
 * stub in place is strictly more informative than a count at the top. What is shared is the
 * rule: the announcement is framework-written text outside every fence, and it says which
 * end went — "an earlier failure", never a silent drop of the newest evidence.
 *
 * @see ClearToolResultsEditor
 */
public final class BoundedFailureTextEditor implements ContextEditor {

    private static final Logger log = LoggerFactory.getLogger(BoundedFailureTextEditor.class);

    /**
     * How much failure text one run's transcript may carry, in characters.
     *
     * <p>Sized against two figures this repository already measured rather than picked.
     * {@code Synthesizers.MAX_TOTAL_OUTCOME_CHARS} is 32,000 and says why it may be that
     * large: it is "the entire user message of one model call that happens once", where
     * {@code BlackboardTools.DEFAULT_MAX_RENDER_CHARS} is 8,000 because a board listing "sits
     * in a transcript and is re-sent every turn". Failure text sits in a transcript. By that
     * argument this belongs nearer 8,000 than 32,000.
     *
     * <p>8,000 was measured and is wrong for a different reason. A full-length failure costs
     * 4,142 characters rendered — the framework's frame, the fence, and a detail cut at
     * {@code ToolResult}'s 4,000 — so an 8,000-character budget carries <strong>one</strong>
     * of them whole, and a model looking at a failure cannot compare it with the failure
     * before it. That is the question a model asks of a retry loop, and a bound that answers
     * it with a stub is worse than the cost it saves.
     *
     * <p>16,000 is the smallest of the three that carries more than one worst case: three
     * full-length failures whole, or — measured over four real error strings averaging 167
     * characters rendered ({@code Connection refused: api.example.com:443},
     * {@code argument 'path' must be absolute}, {@code HTTP 429 Too Many Requests; retry
     * after 30s}, {@code java.net.SocketTimeoutException: Read timed out}) — <strong>95
     * ordinary failures whole</strong>. A run with 95 failed tool calls is already
     * pathological, so an ordinary run pays nothing for this bound, which is the property
     * that decides whether a control is kept on.
     *
     * <p>It bounds what a third party contributes and not what the transcript renders; see
     * the class javadoc for what is left behind per dropped call and why that part is the
     * caller's number rather than an attacker's.
     */
    public static final int DEFAULT_MAX_TOTAL_FAILURE_CHARS = 16_000;

    /**
     * What replaces a failure whose exact text is still in the transcript further down.
     *
     * <p>Short because it is what the run pays per dropped call, and that is the one cost
     * this class cannot bound: a {@code tool_result} must exist for every {@code tool_use},
     * so a dropped failure leaves a block. Four facts is the whole of what a reader needs —
     * something was here, it failed, it was earlier, and it can still be read. Everything
     * else was cut for the arithmetic in {@link #DEFAULT_MAX_TOTAL_FAILURE_CHARS}.
     */
    public static final String REPEATED_PLACEHOLDER =
            "[an identical earlier failure was dropped; the same text is still further down]";

    /**
     * What replaces the older failures once a run's failure text is over budget.
     *
     * <p>"cannot be read back" is the clause {@code Synthesizers.budgetNotice} spends a
     * paragraph on and it is load-bearing for the same reason: told only that something is
     * missing, a model spends a turn asking for it. Nothing is lost to the <em>caller</em>,
     * who still has the {@code AgentResult} and whatever an observer recorded; this bounds a
     * transcript, not a record.
     */
    public static final String OVER_BUDGET_PLACEHOLDER =
            "[an earlier failure was dropped and cannot be read back]";

    private final int maxTotalFailureChars;

    /** Bounds failure text at {@link #DEFAULT_MAX_TOTAL_FAILURE_CHARS}. */
    public BoundedFailureTextEditor() {
        this(DEFAULT_MAX_TOTAL_FAILURE_CHARS);
    }

    /**
     * Bounds failure text at {@code maxTotalFailureChars}.
     *
     * @param maxTotalFailureChars the ceiling on failure text across the whole transcript;
     *                             must be &gt; 0
     */
    public BoundedFailureTextEditor(int maxTotalFailureChars) {
        if (maxTotalFailureChars <= 0) {
            throw new IllegalArgumentException("maxTotalFailureChars must be > 0");
        }
        this.maxTotalFailureChars = maxTotalFailureChars;
    }

    /** The ceiling this editor applies across the whole transcript. */
    public int maxTotalFailureChars() {
        return maxTotalFailureChars;
    }

    /**
     * A copy of {@code history} whose failure text fits the budget, newest kept.
     *
     * <p><strong>Newest first, and the tie-break inside a message is arbitrary but
     * fixed.</strong> Messages are walked from the end, and the blocks within a message from
     * the end too. Between messages that is real recency. Within one message it is not —
     * the results of a parallel tool call are simultaneous — so reverse order there is a
     * tie-break chosen for determinism, which is what replay needs, and not a claim that the
     * last block of a message is the newest.
     *
     * <p><strong>Idempotent, which the agent loop requires rather than merely likes.</strong>
     * {@code Agent} persists the editor's output through {@code Conversation.replaceAll}, so
     * a pass runs over the previous pass's output every turn. A block already carrying one of
     * the two placeholders is neither charged to the budget nor replaced, so the second pass
     * over an unchanged transcript returns it unchanged and logs nothing — a control that
     * warns every turn is one an operator mutes.
     *
     * <p>The exemption is by exact string equality against text this class wrote, which a
     * tool <em>can</em> return: an error result whose whole content is one of those two
     * sentences is exempt from the budget. It costs the payload every character it had — the
     * sentences are fixed, carry nothing, and say the framework dropped something — so the
     * lever buys 79 or 56 characters a call of the framework's own words, which is what the
     * call would have cost after being dropped anyway. What it cannot buy is exemption for a
     * body, which is the thing being bounded.
     *
     * <p><strong>Only what was kept counts as seen.</strong> A repeat is announced as "the
     * same text is still further down", and that sentence has to be true:
     * if the newest copy was itself dropped over budget, the older copies take the
     * over-budget placeholder rather than the repeat one, because there is nothing further
     * down to read.
     *
     * <p><strong>{@code provenance} survives the replacement</strong>, for the reason
     * {@code ClearToolResultsEditor} states about its own: this rewrites the run's record of
     * what it has read, and the label is the one part a caller cannot reconstruct afterwards.
     * Nothing here can lift a {@code TrustFloor} either — a floor is a monotonic flag the
     * runner holds and is deliberately not derived from the transcript, so an edit cannot
     * raise it however much text it removes.
     */
    @Override
    public List<Message> edit(List<Message> history) {
        Objects.requireNonNull(history, "history");
        // Nothing at all happens to a transcript inside its budget, which is a property
        // rather than an optimisation — see the javadoc: collapsing cheap repeats was
        // measured and costs more than it saves.
        if (!overBudget(history)) {
            return history;
        }
        Set<String> kept = new HashSet<>();
        long spent = 0;
        int repeated = 0;
        // Not "overBudget": that is the method one screen down, and a local shadowing a
        // predicate's name reads as a cached call to it.
        int dropped = 0;
        // Indexed by message, then by block, so the second pass can rebuild only what moved.
        // Filled newest-first and read oldest-first, which is why it is an array rather than
        // the rebuild itself.
        String[][] replacements = new String[history.size()][];
        for (int m = history.size() - 1; m >= 0; m--) {
            List<ContentBlock> blocks = history.get(m).content();
            for (int b = blocks.size() - 1; b >= 0; b--) {
                if (!(blocks.get(b) instanceof ToolResultBlock r) || !r.isError()) {
                    continue;
                }
                String content = r.content();
                if (isPlaceholder(content)) {
                    continue;
                }
                String replacement;
                if (kept.contains(content)) {
                    replacement = REPEATED_PLACEHOLDER;
                    repeated++;
                } else if (spent + content.length() <= maxTotalFailureChars) {
                    spent += content.length();
                    kept.add(content);
                    continue;
                } else {
                    replacement = OVER_BUDGET_PLACEHOLDER;
                    dropped++;
                }
                if (replacements[m] == null) {
                    replacements[m] = new String[blocks.size()];
                }
                replacements[m][b] = replacement;
            }
        }
        if (repeated == 0 && dropped == 0) {
            return history;
        }
        // warn only when text left the run, info when it merely stopped being said twice.
        // Synthesizers splits its two log levels the same way and for the same reason: the
        // level tracks what was lost and who can act on it, not how many characters moved. A
        // dropped repeat is still readable further down and costs the model nothing; an
        // over-budget drop is evidence this run can no longer read, and the only party who
        // can do anything about a tool that fails 200 times is the operator.
        if (dropped > 0) {
            log.warn("Dropped {} over-budget and {} repeated failure result(s) from the"
                            + " transcript; {} of {} chars of failure text kept, most recent"
                            + " first, and what was dropped cannot be read back",
                    dropped, repeated, spent, maxTotalFailureChars);
        } else {
            log.info("Collapsed {} repeated failure result(s) in the transcript; each is still"
                    + " present once, further down", repeated);
        }
        List<Message> edited = new ArrayList<>(history.size());
        for (int m = 0; m < history.size(); m++) {
            Message message = history.get(m);
            if (replacements[m] == null) {
                edited.add(message);
                continue;
            }
            List<ContentBlock> blocks = message.content();
            List<ContentBlock> rebuilt = new ArrayList<>(blocks.size());
            for (int b = 0; b < blocks.size(); b++) {
                String replacement = replacements[m][b];
                if (replacement == null) {
                    rebuilt.add(blocks.get(b));
                } else {
                    ToolResultBlock r = (ToolResultBlock) blocks.get(b);
                    rebuilt.add(new ToolResultBlock(r.toolUseId(), replacement, true,
                            r.provenance()));
                }
            }
            edited.add(Message.of(message.role(), rebuilt));
        }
        return edited;
    }

    /**
     * Whether {@code history} carries more failure text than the budget allows.
     *
     * <p>Answered as a question rather than as a total, and it stops counting the moment the
     * answer is known. That is not only cheaper: a total would be an accumulator over a
     * transcript whose size nobody here chose, so the {@code int} form of it is a bound that
     * inverts exactly where it is needed and the {@code long} form is a claim about a
     * quantity nothing can afford to test. Returning early makes the two the same code.
     *
     * <p>A block this class already replaced does not count. It is framework-written text
     * with nothing in it, and charging it would let the stubs left by an earlier pass push a
     * later pass into collapsing repeats that are inside the budget — the rewrite the class
     * javadoc measured and rejected.
     */
    private boolean overBudget(List<Message> history) {
        long total = 0;
        for (Message message : history) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolResultBlock r && r.isError() && !isPlaceholder(r.content())) {
                    total += r.content().length();
                    if (total > maxTotalFailureChars) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Whether {@code content} is one of the two sentences this class writes. */
    private static boolean isPlaceholder(String content) {
        return content.equals(REPEATED_PLACEHOLDER) || content.equals(OVER_BUDGET_PLACEHOLDER);
    }

    @Override
    public String toString() {
        return "BoundedFailureTextEditor[" + maxTotalFailureChars + " chars]";
    }
}
