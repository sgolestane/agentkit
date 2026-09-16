package dev.agentkit.core.goap;

import java.util.List;
import java.util.Objects;

/**
 * A sequence of actions that would take a {@link WorldState} to one meeting an
 * {@link Objective} — assuming every action does what it declared it would.
 *
 * <p>That assumption is why a plan is a suggestion rather than a schedule. A
 * {@link GoapRunner} runs the first action and plans again from what actually happened, so
 * the only step of a plan that is ever committed to is its first one.
 *
 * @param actions the actions in order; never {@code null}, and empty only when the
 *                objective was already met
 */
public record ActionPlan(List<Action> actions) {

    /** The plan for an objective that is already met. */
    public static final ActionPlan EMPTY = new ActionPlan(List.of());

    public ActionPlan {
        Objects.requireNonNull(actions, "actions");
        actions = List.copyOf(actions);
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }

    public int size() {
        return actions.size();
    }

    /** The action to run now, if there is one. */
    public java.util.Optional<Action> first() {
        return actions.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(actions.get(0));
    }

    /**
     * The sum of the actions' costs — what the planner minimised.
     *
     * <p>{@code long}, because a handful of actions near {@link Integer#MAX_VALUE} overflow
     * an {@code int} sum and report a negative total.
     */
    public long totalCost() {
        return actions.stream().mapToLong(Action::cost).sum();
    }

    /** The action names in order, for logging and for asserting on in tests. */
    public List<String> names() {
        return actions.stream().map(Action::name).toList();
    }

    @Override
    public String toString() {
        return isEmpty() ? "ActionPlan[]" : "ActionPlan" + names() + " cost=" + totalCost();
    }
}
