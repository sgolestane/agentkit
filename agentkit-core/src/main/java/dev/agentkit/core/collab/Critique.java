package dev.agentkit.core.collab;

import dev.agentkit.core.reliability.PendingApproval;
import java.util.List;
import java.util.Objects;

/**
 * A peer's assessment of a draft: whether it is good enough, and if not, what to
 * fix. Consumed by {@link RefineLoop} to decide whether to accept the draft or
 * send it back for another revision.
 *
 * <h2>Three answers, not two (#159)</h2>
 *
 * <p>A critic backed by a full agent ({@link Critics#agent}) can stop for a reason that is
 * neither approval nor revision: a gate inside the reviewer's own run parked a tool call
 * and a person has to decide. That used to arrive as
 * {@code Critique.revise("Reviewer did not complete (AWAITING_APPROVAL).")}, which is not a
 * mislabelling so much as an instruction to do the wrong thing — {@code revise} means "the
 * draft is not good enough", so the loop sent the generator back to work on a draft nobody
 * had found fault with.
 *
 * <p>Measured before {@link #awaiting} existed, with a reviewing peer whose run parked:
 *
 * <pre>
 * generator rounds run : 3   (of a 3-round cap)
 * approved             : false
 * last feedback        : "Reviewer did not complete (AWAITING_APPROVAL)."
 * </pre>
 *
 * <p>Three full generator runs, every one of them started by a question about the reviewer
 * that nobody had answered, and the cap was the only thing that stopped it. So the third
 * answer is representable: {@code approved} false, {@code feedback} empty, and the pending
 * calls carried. {@link RefineLoop} checks for it before it reads the feedback.
 *
 * @param approved {@code true} if the draft is acceptable as-is
 * @param feedback actionable guidance for the next revision; empty when approved, and empty
 *                 when a person is being asked — there is nothing to act on yet
 * @param awaiting the tool calls a gate stopped pending somebody's decision, when the critic
 *                 could not reach a verdict because of one; never {@code null}, and empty
 *                 for an ordinary approve or revise
 */
public record Critique(boolean approved, String feedback, List<PendingApproval> awaiting) {

    public Critique {
        Objects.requireNonNull(feedback, "feedback");
        awaiting = List.copyOf(Objects.requireNonNull(awaiting, "awaiting"));
        if (approved && !awaiting.isEmpty()) {
            // The two contradict each other at the one place RefineLoop reads to decide
            // whether the loop ends happily. A critique meaning "this is fine, and also
            // somebody must decide something" has approved a draft produced by a run that
            // did not finish.
            throw new IllegalArgumentException(
                    "an approving critique cannot also be waiting for a person's decision");
        }
    }

    /**
     * The verdict shape, for a critic that reached one.
     *
     * <p>Not a legacy form: {@link #approve()} and {@link #revise} both build through it,
     * and it is what every critic in this repository except {@link Critics#agent} returns.
     */
    public Critique(boolean approved, String feedback) {
        this(approved, feedback, List.of());
    }

    /** An approving critique with no further feedback. */
    public static Critique approve() {
        return new Critique(true, "");
    }

    /** A revise-requested critique carrying actionable {@code feedback}. */
    public static Critique revise(String feedback) {
        Objects.requireNonNull(feedback, "feedback");
        return new Critique(false, feedback);
    }

    /**
     * The critic could not judge the draft, because its own run stopped for a person.
     *
     * <p>Deliberately carries no feedback. There is nothing the generator could act on: the
     * reviewer never read the draft, and inventing a sentence for it to revise against is
     * how "a person must decide" turns into another round of work.
     *
     * @throws IllegalArgumentException if {@code awaiting} is empty — a critique cannot wait
     *     on a question it cannot name, the same rule {@code AgentResult} holds a parked run
     *     to
     */
    public static Critique needsAPerson(List<PendingApproval> awaiting) {
        Objects.requireNonNull(awaiting, "awaiting");
        if (awaiting.isEmpty()) {
            throw new IllegalArgumentException(
                    "a critique waiting for a person must name what is pending");
        }
        return new Critique(false, "", awaiting);
    }

    /** Whether the critic stopped because a gate wants a person to decide. */
    public boolean awaitsAPerson() {
        return !awaiting.isEmpty();
    }
}
