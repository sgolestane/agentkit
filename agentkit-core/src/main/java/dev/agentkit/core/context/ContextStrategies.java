package dev.agentkit.core.context;

import dev.agentkit.core.message.Message;
import java.util.List;
import java.util.Objects;

/**
 * Factories for common {@link ContextStrategy} compositions.
 */
public final class ContextStrategies {

    private ContextStrategies() {
    }

    /**
     * What a runner applies when its caller has not chosen a strategy (#151).
     *
     * <p><strong>The measured defect this closes.</strong> {@link BoundedFailureTextEditor} landed in #220 with its mechanism measured and
     * tested, and nothing wired it. It was reachable only through
     * {@code Agent.Builder.contextStrategy}, so a deployment that did not already know the
     * defect existed ran with the pre-#220 behaviour. Measured through a real {@code Agent}
     * with one tool that throws on every call, counting third-party characters in the
     * transcript and summing over every request the model received:
     *
     * <pre>
     *          IDENTITY (the old default)      this constant (the new one)
     * calls    in transcript   cumulative      in transcript   cumulative
     *     1            4,136        4,136              4,136        4,136
     *     8           33,088      148,896              8,272       62,040
     *    32          132,352    2,183,808              8,272      260,568
     *   256        1,058,816  136,057,856              4,136    2,113,496
     * </pre>
     *
     * <p>The transcript is re-sent every turn, so the unbounded cost is quadratic in the
     * number of failures rather than linear; under the bound it is linear. There was no
     * ceiling in the default configuration and no signal to a caller that they were paying
     * for one being absent.
     *
     * <p>The columns count characters a third party wrote, which is what the bound is on and
     * what the money is spent on. What is left behind — one short framework-written sentence
     * per dropped call, 56 or 79 characters of fixed text with nothing in it — is not
     * counted there and is bounded by the caller's own {@code maxSteps} rather than by
     * anybody else's choice. Counting it too, the cumulative figure at 256 calls is
     * 4,671,911 rather than 136,057,856.
     *
     * <p><strong>Why a control this quiet is worth defaulting on.</strong> The objection is real and is #151's own: a default that silently edits a transcript
     * surprises anybody who reads back what they sent. Four things answer it, and they are
     * properties of this particular editor rather than a general argument for defaults.
     *
     * <ul>
     *   <li><strong>It does nothing below its budget, and "nothing" is exact.</strong>
     *       {@code BoundedFailureTextEditor.edit} returns the <em>same list instance</em>
     *       unless the transcript already carries more than 16,000 characters of failure
     *       text, and its budget check stops counting the moment the answer is known. An
     *       ordinary run of 5, 10, 20 or 40 short failures comes out byte for byte
     *       unchanged. A default that costs nothing on ordinary runs is one that can be left
     *       on, which is the property that decides whether a control is a control.</li>
     *   <li><strong>Nothing is dropped silently.</strong> Every dropped call keeps its
     *       position and carries a framework-written sentence, outside every fence, saying
     *       that something was here, that it failed, that it was earlier, and whether it can
     *       still be read further down. Over-budget drops are logged at {@code WARN}.</li>
     *   <li><strong>It bounds a transcript, not a record.</strong> {@code AgentResult}, the
     *       observer callbacks and anything an audit trail wrote are untouched — this edits
     *       only the message list a turn sends to the model.</li>
     *   <li><strong>The escape hatch is one call and it is honest.</strong> A caller who
     *       wants the transcript through unchanged passes {@link #identity()}, which still
     *       means exactly identity.</li>
     * </ul>
     *
     * <p><strong>Why {@code ContextStrategy.IDENTITY} did not move.</strong> #151 proposed changing {@code ContextStrategy.IDENTITY} itself and noted that its
     * name and javadoc would then have to move, because it would no longer be identity. That
     * is a true consequence of that shape and the reason this takes the other one. The
     * constant is public API and a caller naming it is asking for the history through
     * unchanged; a constant that quietly stopped honouring its own name would be a second
     * surprise added to fix the first, and it would leave no way to spell the thing it used
     * to mean. What was actually wrong was the <em>default</em> — the field initialiser and
     * the two convenience constructors in {@code Agent} — so that is what moved.
     *
     * <p><strong>One rule, several runners.</strong> Named here rather than inside {@code Agent} because a transcript bound is a
     * property of an agent run and not of the in-memory loop, and #151's third point asks
     * for it to live somewhere both runners reach. {@code agentkit-core} is that place, and
     * since #243 both runners do reach it: {@code AgentWorkflowImpl} applies this constant
     * to the message list each durable turn sends, behind a {@code Workflow.getVersion}
     * marker so a run already in flight keeps the transcript shape its history was written
     * against. It edits the activity's input rather than writing the result back into the
     * workflow's own conversation, so the numbers the two runners settle at differ slightly
     * while the rule does not; {@code AgentWorkflowImpl.TRANSCRIPT_BOUNDED} has both tables
     * and says why.
     *
     * <p>Safe to share: {@code BoundedFailureTextEditor} holds one {@code int} and
     * {@link Compactor#NONE} holds nothing, so this constant is immutable and its
     * {@code prepare} is a pure function of its argument — which is also what replay on the
     * durable path will require of it.
     */
    public static final ContextStrategy DEFAULT = editing(new BoundedFailureTextEditor());

    /** The pass-through strategy: the history is sent exactly as it stands. */
    public static ContextStrategy identity() {
        return ContextStrategy.IDENTITY;
    }

    /**
     * Applies a {@link ContextEditor} (prune) and then a {@link Compactor}
     * (summarise): editing first reclaims cheap bulk, so compaction runs less
     * often and over smaller input.
     */
    public static ContextStrategy of(ContextEditor editor, Compactor compactor) {
        Objects.requireNonNull(editor, "editor");
        Objects.requireNonNull(compactor, "compactor");
        return history -> {
            List<Message> edited = editor.edit(history);
            return compactor.compact(edited);
        };
    }

    /** A strategy that only edits (no compaction). */
    public static ContextStrategy editing(ContextEditor editor) {
        return of(editor, Compactor.NONE);
    }

    /** A strategy that only compacts (no editing). */
    public static ContextStrategy compacting(Compactor compactor) {
        return of(ContextEditor.NONE, compactor);
    }
}
