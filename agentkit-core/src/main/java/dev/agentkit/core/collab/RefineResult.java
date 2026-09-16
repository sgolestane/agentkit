package dev.agentkit.core.collab;

import dev.agentkit.core.reliability.PendingApproval;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of a {@link RefineLoop}.
 *
 * <h2>Why a park needs a component of its own (#159)</h2>
 *
 * <p>Every other composing runner in this repository keeps the inner {@code AgentResult}
 * somewhere — a {@code SubagentOutcome} holds one, a {@code NodeOutcome} holds one, an
 * {@code ActionOutcome} holds one — so a parked inner run was at worst mislabelled there,
 * and always still reachable. This record held none, by design: a refinement loop's answer
 * is a draft and a verdict on it, and the runs behind them are not the caller's business.
 *
 * <p>That made this the one place where the fact was <em>destroyed</em> rather than
 * misfiled. Measured before this component existed, with a generator whose run parked:
 *
 * <pre>
 * approved : false
 * rounds   : 1
 * output   : ""
 * </pre>
 *
 * <p>Indistinguishable from a generator that produced nothing and was rejected, with the
 * {@code PendingApproval} unreachable from anywhere in the returned value.
 *
 * @param output       the final draft
 * @param approved     whether the critic approved that draft
 * @param rounds       how many generator rounds ran (at least 1)
 * @param lastFeedback the most recent critic feedback (empty if approved, and empty when a
 *                     person is being asked — the critic reached no verdict)
 * @param awaiting     the tool calls a gate stopped pending somebody's decision, when the
 *                     loop ended because of one; never {@code null}, and empty otherwise
 */
public record RefineResult(String output, boolean approved, int rounds, String lastFeedback,
                           List<PendingApproval> awaiting) {

    public RefineResult {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(lastFeedback, "lastFeedback");
        awaiting = List.copyOf(Objects.requireNonNull(awaiting, "awaiting"));
        if (approved && !awaiting.isEmpty()) {
            // An approved draft is a finished piece of work, and a run that stopped to ask
            // somebody did not finish. The two together would tell a caller to ship it and
            // to go and get a decision first.
            throw new IllegalArgumentException(
                    "an approved result cannot also be waiting for a person's decision");
        }
    }

    /**
     * The shape with nothing pending, which is what every ordinary round builds.
     *
     * <p>Not a legacy form: the two ordinary exits from {@link RefineLoop} go through it.
     */
    public RefineResult(String output, boolean approved, int rounds, String lastFeedback) {
        this(output, approved, rounds, lastFeedback, List.of());
    }

    /**
     * A loop that ended because a gate stopped a call and somebody has to decide.
     *
     * <p>{@code output} is whatever draft the loop had reached — the same reason
     * {@code AgentResult.awaitingApproval} keeps the model's last text: the work done up to
     * the question is still work, and it is what a person deciding will want to look at.
     *
     * @throws IllegalArgumentException if {@code awaiting} is empty
     */
    public static RefineResult awaitingApproval(String output, int rounds,
                                                List<PendingApproval> awaiting) {
        Objects.requireNonNull(awaiting, "awaiting");
        if (awaiting.isEmpty()) {
            throw new IllegalArgumentException(
                    "a result waiting for a person must name what is pending");
        }
        return new RefineResult(output, false, rounds, "", awaiting);
    }

    /** Whether the loop ended because a gate wants a person to decide. */
    public boolean awaitsAPerson() {
        return !awaiting.isEmpty();
    }
}
