package dev.agentkit.core.goap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Works out an order: the cheapest sequence of actions that would take a state to one
 * meeting an objective.
 *
 * <p>The search is over <em>which facts exist</em>, not their values. An action is
 * applicable when its needs are all present, and applying it adds everything it produces.
 * Facts are never removed, so the search space is a lattice over the fact vocabulary and a
 * state is fully identified by its key set — which is what makes it cheap to explore
 * exhaustively rather than heuristically.
 *
 * <p>Uniform-cost search (Dijkstra), not A*. The obvious heuristic — how many required
 * facts are still missing — is <em>not</em> admissible here, because one action may produce
 * several of them at once and the heuristic would then overestimate and return a plan that
 * is not the cheapest. With the action counts a plan of agents realistically has, the
 * saving would not have been worth a wrong answer.
 *
 * <p><strong>Deterministic.</strong> Ties on cost are broken by the order the actions were
 * declared, so the same inputs always give the same plan. A planner that returned a
 * different valid plan run to run would make failures unreproducible, which is the last
 * thing an unsupervised system needs.
 */
public final class GoapPlanner {

    /**
     * Explored-state ceiling.
     *
     * <p>The search space is the lattice of fact subsets, so it is exponential in the number
     * of <em>mutually independent</em> actions — those neither needing nor producing what the
     * others do. Measured, the default stops around 13 of them; raising it buys about three
     * more before wall-clock becomes the wall instead (16 independent actions take seconds,
     * 18 take a minute). Chains cost nothing: a 500-action chain plans in milliseconds.
     *
     * <p>Most real fan-out never reaches the search, because forced actions are committed
     * before it starts — see {@link #plan(WorldState, Objective, List, int)}. Twenty
     * gatherers feeding one synthesis step plan without exploring a single state.
     */
    public static final int DEFAULT_MAX_STATES = 10_000;

    private GoapPlanner() {
    }

    /**
     * The cheapest plan from {@code state} to one meeting {@code objective}.
     *
     * @param actions the actions that may be used, in declaration order — the tie-break
     * @return the plan; {@link ActionPlan#EMPTY} if the objective is already met
     * @throws UnreachableObjectiveException if no sequence of {@code actions} could meet it
     * @throws IllegalStateException         if the search exceeds {@link #DEFAULT_MAX_STATES}
     */
    public static ActionPlan plan(WorldState state, Objective objective, List<Action> actions) {
        return plan(state, objective, actions, DEFAULT_MAX_STATES);
    }

    /** As {@link #plan(WorldState, Objective, List)}, with an explicit search ceiling. */
    public static ActionPlan plan(WorldState state, Objective objective, List<Action> actions,
                                  int maxStates) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(actions, "actions");
        if (maxStates <= 0) {
            throw new IllegalArgumentException("maxStates must be > 0, was " + maxStates);
        }
        if (objective.isMetBy(state)) {
            return ActionPlan.EMPTY;
        }
        List<Action> committed = commitForced(state, objective, actions);
        Set<String> facts = new LinkedHashSet<>(state.keys());
        committed.forEach(action -> facts.addAll(action.produces()));
        if (facts.containsAll(objective.required())) {
            return new ActionPlan(committed);
        }
        // Set.copyOf is fine here and only here: these are search keys, hashed and compared
        // but never iterated into anything a caller sees.
        Set<String> start = Set.copyOf(facts);

        Map<Set<String>, Long> cheapest = new HashMap<>();
        PriorityQueue<Node> frontier = new PriorityQueue<>(
                Comparator.comparingLong((Node n) -> n.cost).thenComparingInt(n -> n.sequence));
        frontier.add(new Node(start, List.of(), 0L, 0));
        cheapest.put(start, 0L);

        int explored = 0;
        int sequence = 0;
        while (!frontier.isEmpty()) {
            if (++explored > maxStates) {
                throw new IllegalStateException("Planning gave up after exploring " + maxStates
                        + " states for objective '" + objective.description() + "' over "
                        + actions.size() + " actions. Either the action set is far larger than "
                        + "this was built for, or two actions are producing keys that fan out; "
                        + "raise the ceiling deliberately if the first.");
            }
            Node node = frontier.poll();
            if (node.cost > cheapest.getOrDefault(node.facts, Long.MAX_VALUE)) {
                continue;   // A cheaper route to the same facts was already expanded.
            }
            if (node.facts.containsAll(objective.required())) {
                List<Action> plan = new ArrayList<>(committed);
                plan.addAll(node.taken);
                return new ActionPlan(plan);
            }
            for (Action action : actions) {
                if (!node.facts.containsAll(action.needs())
                        || node.facts.containsAll(action.produces())) {
                    // An optimisation, not a correctness requirement — the cheapest-cost map
                    // below would reject a no-op anyway, since it reaches the same fact set at
                    // a strictly higher cost. It just saves expanding the node to find out.
                    continue;
                }
                Set<String> next = new LinkedHashSet<>(node.facts);
                next.addAll(action.produces());
                Set<String> nextFacts = Set.copyOf(next);
                // long, and the sentinel is Long.MAX_VALUE: a plan's cost is bounded by its
                // length times Integer.MAX_VALUE, so it can never collide with the sentinel
                // or overflow. With int both happened — a single action costing
                // Integer.MAX_VALUE was never pushed at all, and two large ones wrapped
                // negative and won against a cheaper plan.
                long nextCost = node.cost + action.cost();
                if (nextCost >= cheapest.getOrDefault(nextFacts, Long.MAX_VALUE)) {
                    continue;
                }
                cheapest.put(nextFacts, nextCost);
                List<Action> taken = new ArrayList<>(node.taken);
                taken.add(action);
                frontier.add(new Node(nextFacts, taken, nextCost, ++sequence));
            }
        }
        // The search is exhaustive over a space that only grows, so exhausting the frontier
        // means no route exists. Diagnosing it here rather than up front keeps one source of
        // truth about what is reachable, and costs nothing on the path that succeeds.
        throw diagnose(state, objective, actions, Set.of());
    }

    /**
     * Applies, up front, the actions every plan must contain anyway.
     *
     * <p>An action that is the only producer of a fact the objective needs appears in every
     * plan that meets the objective. Since facts are never consumed and cost is additive,
     * moving such an action to the front gives a plan of the same cost that is still valid —
     * so committing it is optimality-preserving, not a heuristic.
     *
     * <p>"Needs" is transitive: if the sole producer of a required fact needs something the
     * world does not have, that something must be established too, and its sole producer is
     * forced in turn. That is what makes the common fan-out shape cheap. Twenty gatherers
     * feeding one synthesis step are each the sole producer of something the synthesis needs,
     * so all twenty are committed and the search never runs — where the lattice of 2^20 fact
     * subsets would otherwise have hit the ceiling.
     */
    private static List<Action> commitForced(WorldState state, Objective objective,
                                             List<Action> actions) {
        List<Action> committed = new ArrayList<>();
        Set<String> facts = new LinkedHashSet<>(state.keys());
        Set<String> taken = new HashSet<>();
        while (!facts.containsAll(objective.required())) {
            Set<String> forced = forcedProducers(facts, objective, actions);
            Action next = actions.stream()
                    .filter(action -> forced.contains(action.name()))
                    .filter(action -> !taken.contains(action.name()))
                    .filter(action -> facts.containsAll(action.needs()))
                    .filter(action -> !facts.containsAll(action.produces()))
                    .findFirst()
                    .orElse(null);
            if (next == null) {
                break;
            }
            committed.add(next);
            taken.add(next.name());
            facts.addAll(next.produces());
        }
        return committed;
    }

    /** The names of actions that must appear in every plan, by the argument above. */
    private static Set<String> forcedProducers(Set<String> facts, Objective objective,
                                               List<Action> actions) {
        Set<String> forced = new LinkedHashSet<>();
        Set<String> needed = new LinkedHashSet<>(objective.required());
        Deque<String> pending = new ArrayDeque<>(needed);
        while (!pending.isEmpty()) {
            String fact = pending.poll();
            if (facts.contains(fact)) {
                continue;
            }
            List<Action> producers = actions.stream()
                    .filter(action -> action.produces().contains(fact)).toList();
            // Two producers means a choice, and a choice is what the search is for.
            if (producers.size() != 1 || !forced.add(producers.get(0).name())) {
                continue;
            }
            for (String need : producers.get(0).needs()) {
                if (needed.add(need)) {
                    pending.add(need);
                }
            }
        }
        return forced;
    }

    /**
     * Explains why no plan was found.
     *
     * <p>Computes the closure of everything the actions could ever produce — applying every
     * applicable action repeatedly until nothing new appears — and reports which required
     * facts fell outside it. The two reasons want opposite fixes, so they are named
     * separately: a fact nothing produces means an action is missing, and a fact produced
     * only by actions whose own needs are unreachable means the chain breaks further back.
     */
    static UnreachableObjectiveException diagnose(WorldState state, Objective objective,
                                                  List<Action> actions, Set<String> setAside) {
        Set<String> reachable = new HashSet<>(state.keys());
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Action action : actions) {
                // Reachability is over what is still usable; the producer list below is over
                // everything, so an action that failed is named rather than looking absent.
                if (!setAside.contains(action.name())
                        && reachable.containsAll(action.needs()) && reachable.addAll(action.produces())) {
                    grew = true;
                }
            }
        }
        Set<String> unreachable = new LinkedHashSet<>(objective.required());
        unreachable.removeAll(reachable);
        if (unreachable.isEmpty()) {
            // Every required fact is individually reachable, yet the search found no state
            // holding them all. Facts are never consumed, so this should not be possible;
            // reported as itself rather than dressed up as a modelling error.
            return new UnreachableObjectiveException(
                    "No plan reaches objective '" + objective.description() + "' from "
                            + state.keys() + ", although every required fact is individually "
                            + "reachable. This is a planner bug — please report it.",
                    objective.missingIn(state));
        }
        StringBuilder message = new StringBuilder("Objective '").append(objective.description())
                .append("' cannot be met from ").append(state.keys()).append(':');
        for (String fact : unreachable) {
            List<Action> producers = actions.stream()
                    .filter(action -> action.produces().contains(fact)).toList();
            message.append("\n  - '").append(fact).append("' ");
            if (producers.isEmpty()) {
                message.append("is produced by no action at all");
            } else if (producers.stream().allMatch(p -> setAside.contains(p.name()))) {
                // The distinction the two stop reasons exist for. Saying "produced by no
                // action at all" here sends the reader to write an action they already have.
                message.append("is produced only by ").append(producers.stream()
                        .map(p -> "'" + p.name() + "'")
                        .collect(java.util.stream.Collectors.joining(", ")))
                        .append(producers.size() == 1 ? ", which failed" : ", all of which failed")
                        .append(" earlier in this run and ")
                        .append(producers.size() == 1 ? "was" : "were").append(" set aside");
            } else {
                // Every producer here has an unmet need — otherwise the fixpoint would have
                // added its output and the fact would not be in this list — so there is no
                // "needs are fine" case to print.
                message.append("is produced only by ").append(producers.stream()
                        .map(producer -> {
                            if (setAside.contains(producer.name())) {
                                return "'" + producer.name() + "' (failed earlier and was set aside)";
                            }
                            Set<String> blocked = new LinkedHashSet<>(producer.needs());
                            blocked.removeAll(reachable);
                            return "'" + producer.name() + "' (needs " + blocked
                                    + ", which nothing establishes)";
                        })
                        .collect(java.util.stream.Collectors.joining(", ")));
            }
        }
        return new UnreachableObjectiveException(message.toString(), unreachable);
    }

    /**
     * One state in the search.
     *
     * @param sequence insertion order, the tie-break that makes planning deterministic —
     *                 without it a {@code PriorityQueue} orders equal-cost nodes arbitrarily
     */
    private record Node(Set<String> facts, List<Action> taken, long cost, int sequence) {
    }
}
