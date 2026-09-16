package dev.agentkit.core.goap;

import dev.agentkit.core.util.Observations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Runs actions toward an objective, planning again after every step.
 *
 * <p>The alternative to drawing the graph yourself. You declare what each action needs and
 * produces; the runner works out an order, runs the first step, looks at what actually
 * happened, and works out the order again. Nothing about the sequence is fixed in advance,
 * which is what lets it route around a step that failed:
 *
 * <pre>{@code
 * GoapResult result = GoapRunner.forObjective(Objective.of("A briefed article", "article"))
 *         .action(Action.named("search_web").produces("sources")
 *                 .agent("Find primary sources", () -> researcher))
 *         .action(Action.named("read_archive").cost(3).produces("sources")
 *                 .agent("Find sources in the archive", () -> archivist))
 *         .action(Action.named("write").needs("sources").produces("article")
 *                 .agent("Write the article", () -> writer))
 *         .run(WorldState.EMPTY);
 *
 * result.output("article");
 * }</pre>
 *
 * <p>{@code search_web} is cheaper, so it goes first. If it fails, the next plan uses
 * {@code read_archive} — with no branch drawn anywhere, and without the writer having to
 * know which one supplied the sources. An {@code AgentGraph} would need that fallback drawn
 * as an edge, and a new one for every pair.
 *
 * <h2>When to reach for this instead of a graph</h2>
 *
 * <p>A DAG is the better tool when the shape is genuinely fixed: it is explicit, it runs
 * independent branches concurrently, and you can read the topology off the page. Reach for
 * this when the shape is not fixed — when several actions can establish the same fact, when
 * an action's failure should be routed around rather than propagated, or when adding a
 * capability should not mean re-drawing edges. The cost is that you cannot see the order by
 * reading the code; you have to read the trace.
 *
 * <p><strong>Actions run one at a time</strong>, because the point is to re-plan on what
 * actually happened, and two actions in flight means planning on a state that is already
 * stale. If independent work should overlap, that is a DAG, and {@code AgentGraph} runs it
 * concurrently.
 */
public final class GoapRunner {

    /** Enough for a plan of agents to fail and re-route several times; not enough to spin. */
    public static final int DEFAULT_MAX_ACTIONS = 20;

    private final Objective objective;
    private final List<Action> actions;
    private final int maxActions;
    private final int maxPlanStates;
    private final GoapObserver observer;
    private final BiConsumer<String, Throwable> onObservationFailure;

    private GoapRunner(Builder builder) {
        this.objective = builder.objective;
        this.actions = List.copyOf(builder.actions.values());
        this.maxActions = builder.maxActions;
        this.maxPlanStates = builder.maxPlanStates;
        this.observer = builder.observer;
        this.onObservationFailure = builder.onObservationFailure;
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("A runner with no actions can only ever report that "
                    + "objective '" + objective.description() + "' is unreachable");
        }
    }

    /** Starts building a runner for {@code objective}. */
    public static Builder forObjective(Objective objective) {
        return new Builder(objective);
    }

    public Objective objective() {
        return objective;
    }

    /** The actions available, in declaration order — which is the planner's tie-break. */
    public List<Action> actions() {
        return actions;
    }

    /**
     * The plan from {@code state} as things stand, without running anything.
     *
     * <p>For inspecting or asserting on the order without executing it. It is not a
     * prerequisite for {@link #run}: an unreachable objective comes back from {@code run} as
     * {@link GoapStop#NO_PLAN_AT_ALL} with an empty trace, before anything has been spent.
     *
     * @throws UnreachableObjectiveException if no plan reaches the objective
     */
    public ActionPlan planFrom(WorldState state) {
        return GoapPlanner.plan(state, objective, actions, maxPlanStates);
    }

    /** Runs toward the objective from {@code initial}. */
    public GoapResult run(WorldState initial) {
        Objects.requireNonNull(initial, "initial");
        WorldState state = initial;
        List<ActionOutcome> trace = new ArrayList<>();
        // Failed actions are set aside for the rest of the run rather than retried, because
        // the planner would otherwise pick the same cheapest action forever. Keyed on the
        // action, which is all this can see: two actions sharing one broken backend are two
        // separate failures here, and a transient failure is indistinguishable from a
        // permanent one — retry belongs inside the handler, where the difference is visible.
        Set<String> abandoned = new LinkedHashSet<>();

        for (int taken = 0; taken < maxActions; taken++) {
            if (objective.isMetBy(state)) {
                return finish(GoapResult.of(GoapStop.OBJECTIVE_MET, state, trace, null, null));
            }
            List<Action> available = actions.stream()
                    .filter(action -> !abandoned.contains(action.name())).toList();
            ActionPlan plan;
            try {
                plan = GoapPlanner.plan(state, objective, available, maxPlanStates);
            } catch (UnreachableObjectiveException e) {
                // Re-diagnosed against every action, not just the ones still available: the
                // planner was handed the filtered list, so it reported a failed action's
                // output as "produced by no action at all" — telling the reader to go write
                // an action they already have.
                UnreachableObjectiveException explained = abandoned.isEmpty() ? e
                        : GoapPlanner.diagnose(state, objective, actions, abandoned);
                return finish(GoapResult.of(
                        trace.isEmpty() ? GoapStop.NO_PLAN_AT_ALL : GoapStop.NO_ROUTE_LEFT,
                        state, trace, explained.getMessage(), explained));
            } catch (IllegalStateException e) {
                // The search ceiling. Reported as a result rather than thrown: run() promises
                // a GoapResult, and an escaping exception loses every fact established so far.
                return finish(GoapResult.of(GoapStop.PLANNING_GAVE_UP, state, trace,
                        e.getMessage(), e));
            }
            // Snapshotted because both are reassigned around this loop and a lambda needs
            // them effectively final. WorldState and ActionPlan are immutable, so the
            // observer sees the same two values it would have been handed directly.
            ActionPlan chosen = plan;
            WorldState before = state;
            observed("onPlan", () -> observer.onPlan(chosen, before));
            Action action = chosen.first().orElseThrow(() -> new IllegalStateException(
                    "The planner returned an empty plan for an objective that is not met"));
            observed("onActionStart", () -> observer.onActionStart(action, before));

            ActionResult result;
            try {
                result = action.run(state);
            } catch (RuntimeException | Error e) {
                // Error too: an action that runs an agent can throw one, and catching only
                // RuntimeException would lose every fact established up to that point.
                ActionOutcome threw = new ActionOutcome(action.name(), false, Set.of(),
                        java.util.Optional.empty(), java.util.Optional.of(e.toString()));
                trace.add(threw);
                observed("onActionEnd", () -> observer.onActionEnd(action, threw));
                return finish(GoapResult.of(GoapStop.ACTION_THREW, state, trace,
                        "Action '" + action.name() + "' threw " + e, e));
            }

            ActionOutcome outcome = ActionOutcome.of(action, result);
            trace.add(outcome);
            observed("onActionEnd", () -> observer.onActionEnd(action, outcome));
            if (outcome.awaitsAPerson()) {
                // Before the failure branch below, and that order is the fix (#159). A
                // parked agent comes back as a failed action — Action.agent builds one, and
                // so does any handler that reports a non-COMPLETED run — so the branch below
                // would abandon it and plan again. Measured: with a cheap parking search_web
                // and a dearer read_archive both producing `sources`, the run took the
                // archive and reported OBJECTIVE_MET, obtaining the very effect somebody was
                // being asked about, by another route, while the question was outstanding.
                //
                // Stopping rather than abandoning is the narrower of the two repairs. The
                // wider one is to keep planning with the parked action still available, so
                // the run continues if some *other* route exists that does not need the
                // decision — but a route the planner reaches only because a question is
                // outstanding is the same defect wearing a smaller number, and there is no
                // way from here to tell an unrelated route from an alternative one. The
                // planner reasons about which facts exist, never about how they came to.
                return finish(GoapResult.of(GoapStop.AWAITING_APPROVAL, state, trace,
                        "Action '" + action.name() + "' stopped for a person's decision. The "
                                + "run stopped with it rather than planning a route around the "
                                + "question; see awaiting() for what is pending.", null));
            }
            if (!result.isSuccess()) {
                abandoned.add(action.name());
                continue;
            }
            state = state.with(result.facts());

            // Compared against the resulting state, not against what the action returned: an
            // action producing {a, b} in a world that already has {a} legitimately
            // establishes only b, and checking its return value alone failed the run for it.
            // The anti-spin guarantee is unaffected — the planner only picks an action when
            // some produced key is missing, so all-present afterwards means real progress.
            Set<String> promised = new LinkedHashSet<>(action.produces());
            promised.removeAll(state.keys());
            if (!promised.isEmpty()) {
                return finish(GoapResult.of(GoapStop.BROKEN_ACTION, state, trace,
                        "Action '" + action.name() + "' reported success without establishing "
                                + promised + ", which it declares it produces. The planner would "
                                + "keep choosing it, so the run stopped instead of spinning.", null));
            }
        }
        return finish(objective.isMetBy(state)
                ? GoapResult.of(GoapStop.OBJECTIVE_MET, state, trace, null, null)
                : GoapResult.of(GoapStop.OUT_OF_ACTIONS, state, trace,
                        "Ran " + maxActions + " actions without meeting objective '"
                                + objective.description() + "'; still missing "
                                + objective.missingIn(state), null));
    }

    /**
     * Dispatches one observer callback, absorbing anything it throws (#173).
     *
     * <p>This seam used to answer the opposite way, and said so: "an observer that throws
     * takes the run down with it — this is a reporting seam, not a policy one". The second
     * half is the premise for the opposite conclusion. A reporting seam that can abort the
     * thing it reports on is not a reporting seam, and {@link #onActionEnd} fires
     * <em>after</em> the action has run, which is the asymmetry that made the same defect
     * in {@code Agent} worse than an ordinary crash: the side effect has happened, and a
     * throw there loses the run with no {@link GoapResult} and no {@code onFinish}.
     * Measured before this, with an observer throwing in {@code onActionEnd}:
     *
     * <pre>
     * action actually ran : true
     * GoapResult returned : none — the exception escaped run()
     * onFinish called     : false
     * </pre>
     *
     * <p>So an observer keeping per-run state was left holding a run it would never see the
     * end of, and every fact the trace had established was lost with it — the same loss
     * {@code run}'s own {@code catch (RuntimeException | Error)} around {@code action.run}
     * exists to prevent one line earlier.
     *
     * <p>{@code GoapResult} gains no failure count, and that is #166's decision rather than
     * an omission here. A count said "some number of somethings failed", which is what the
     * log already said; {@code onFinish}'s own failure cannot appear in a result that
     * already exists; and a per-chunk callback made the number meaningless. The handler
     * carries which callback failed and what it threw, which is what a deployment can act
     * on. See {@link Observations}.
     */
    private void observed(String callback, Runnable dispatch) {
        Observations.ran(callback, dispatch, onObservationFailure);
    }

    private GoapResult finish(GoapResult result) {
        observed("onFinish", () -> observer.onFinish(result));
        return result;
    }

    /** Builder for {@link GoapRunner}. */
    public static final class Builder {
        private final Objective objective;
        private final Map<String, Action> actions = new LinkedHashMap<>();
        private int maxActions = DEFAULT_MAX_ACTIONS;
        private int maxPlanStates = GoapPlanner.DEFAULT_MAX_STATES;
        private GoapObserver observer = GoapObserver.NONE;
        private BiConsumer<String, Throwable> onObservationFailure = Observations.LOGGING;

        private Builder(Objective objective) {
            this.objective = Objects.requireNonNull(objective, "objective");
        }

        /**
         * Adds an action.
         *
         * @throws IllegalArgumentException if another action of the same name was already
         *         added; names identify actions in the trace and in the abandoned set, so two
         *         actions sharing one would make a run's history unreadable and let a
         *         failure disable the wrong step
         */
        public Builder action(Action action) {
            Objects.requireNonNull(action, "action");
            Action existing = actions.putIfAbsent(action.name(), action);
            if (existing != null) {
                throw new IllegalArgumentException("Two actions are named '" + action.name() + "'");
            }
            return this;
        }

        public Builder actions(List<Action> actions) {
            Objects.requireNonNull(actions, "actions").forEach(this::action);
            return this;
        }

        /**
         * How many actions may run before the run gives up, default
         * {@link #DEFAULT_MAX_ACTIONS}. Counts every attempt, including ones that failed and
         * were routed around.
         */
        public Builder maxActions(int maxActions) {
            if (maxActions <= 0) {
                throw new IllegalArgumentException("maxActions must be > 0, was " + maxActions);
            }
            this.maxActions = maxActions;
            return this;
        }

        /**
         * How many states each planning pass may explore, default
         * {@link GoapPlanner#DEFAULT_MAX_STATES}. Exceeding it ends the run with
         * {@link GoapStop#PLANNING_GAVE_UP} rather than throwing. Raise it only knowing the
         * search is exponential in the number of mutually independent actions.
         */
        public Builder maxPlanStates(int maxPlanStates) {
            if (maxPlanStates <= 0) {
                throw new IllegalArgumentException("maxPlanStates must be > 0, was " + maxPlanStates);
            }
            this.maxPlanStates = maxPlanStates;
            return this;
        }

        /**
         * Watches the run. Because the order is computed rather than written down, this is
         * the only way to see a run in progress — including which plan was chosen and which
         * action was routed around.
         */
        public Builder observer(GoapObserver observer) {
            this.observer = Objects.requireNonNull(observer, "observer");
            return this;
        }

        /**
         * What to do when the observer throws, given the callback's name and what it threw.
         * Defaults to {@link Observations#LOGGING}.
         *
         * <p>Set this when the record the observer keeps is a control rather than a
         * convenience. The framework cannot tell whether a dropped {@code onPlan} matters
         * and a dropped {@code onActionEnd} does, so it logs and carries on; a deployment
         * that knows the difference can write the row that was missed, naming the action.
         *
         * <p>The handler is itself guarded — one that throws is logged and absorbed —
         * because a handler that could abort a run would put #173's defect back one level
         * up.
         *
         * <p>Same name, same default and same contract as {@code Agent.Builder}'s, on
         * purpose. Two observer seams in one library answering the same question two ways
         * is what #173 was.
         */
        public Builder onObservationFailure(BiConsumer<String, Throwable> handler) {
            this.onObservationFailure = Objects.requireNonNull(handler, "handler");
            return this;
        }

        public GoapRunner build() {
            return new GoapRunner(this);
        }

        /** Builds and runs in one step. */
        public GoapResult run(WorldState initial) {
            return build().run(initial);
        }
    }
}
