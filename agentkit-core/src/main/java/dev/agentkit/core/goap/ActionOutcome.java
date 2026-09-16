package dev.agentkit.core.goap;

import dev.agentkit.core.agent.AgentResult;
import java.util.Optional;
import java.util.Set;

/**
 * What happened when one action ran, in the order the {@link GoapRunner} ran them.
 *
 * <p>Each action appears at most once. Every way a step can end forecloses a repeat: success
 * puts its facts in the world, and the planner skips an action whose output already exists;
 * failure sets the action aside; and succeeding without establishing what was declared ends
 * the run outright. So the trace is a set of distinct attempts, and a route that was tried
 * and abandoned shows up as a failed entry rather than as a repeat.
 *
 * @param action    the action's name; never {@code null}
 * @param succeeded whether it did what it said it would
 * @param produced  the fact keys it actually established; never {@code null}, and empty on
 *                  failure
 * @param result    the agent run behind it, if it was one; never {@code null}
 * @param failure   why it failed, if it did; never {@code null}
 */
public record ActionOutcome(String action, boolean succeeded, Set<String> produced,
                            Optional<AgentResult> result, Optional<String> failure) {

    public ActionOutcome {
        java.util.Objects.requireNonNull(action, "action");
        java.util.Objects.requireNonNull(result, "result");
        java.util.Objects.requireNonNull(failure, "failure");
        produced = java.util.Collections.unmodifiableSet(
                new java.util.LinkedHashSet<>(java.util.Objects.requireNonNull(produced, "produced")));
        if (succeeded == failure.isPresent()) {
            throw new IllegalArgumentException("A failure reason must be present exactly when the "
                    + "action failed (succeeded=" + succeeded + ", failure present="
                    + failure.isPresent() + ")");
        }
    }

    /**
     * Whether this action's agent stopped for a person's decision.
     *
     * <p>Read off the carried {@link AgentResult} rather than off {@link #succeeded()},
     * because a park arrives here as a failure: {@code Action.agent} maps every
     * non-{@code COMPLETED} run to {@code ActionResult.failed}, so the only thing that can
     * tell a question from a dead end is the run itself. {@link GoapRunner} asks this
     * <em>before</em> it abandons a failed action, which is what stops it planning a route
     * around the question — see {@link GoapStop#AWAITING_APPROVAL} for what it did before.
     *
     * <p>It answers for a <em>successful</em> action too, and the runner stops on that as
     * well. A handler that files a fact from a run that parked has established a fact
     * nothing did the work for; failing closed there costs a re-run and the alternative
     * costs the control.
     *
     * <p>An action that runs an agent and does not pass the {@code AgentResult} back —
     * {@code ActionResult.failed(reason)} rather than {@code failed(reason, result)} — is
     * invisible here, and the framework cannot see past it. {@code Action.agent} always
     * passes it; a handler doing this by hand must too, which {@link Action} says.
     */
    public boolean awaitsAPerson() {
        return result.filter(AgentResult::isAwaitingApproval).isPresent();
    }

    static ActionOutcome of(Action action, ActionResult result) {
        return new ActionOutcome(action.name(), result.isSuccess(), result.facts().keySet(),
                result.result(), result.failure());
    }

    @Override
    public String toString() {
        return action + (succeeded ? " -> " + produced : " failed: " + failure.orElseThrow());
    }
}
