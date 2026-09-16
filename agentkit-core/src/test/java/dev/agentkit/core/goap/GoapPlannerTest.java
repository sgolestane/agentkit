package dev.agentkit.core.goap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoapPlannerTest {

    /** An action that establishes its declared keys with placeholder values. */
    private static Action step(String name, List<String> needs, List<String> produces, int cost) {
        Action.Builder builder = Action.named(name).cost(cost)
                .needs(needs.toArray(String[]::new))
                .produces(produces.toArray(String[]::new));
        return builder.handler(state -> ActionResult.ok(
                produces.stream().collect(java.util.stream.Collectors.toMap(k -> k, k -> k + "!"))))
                .build();
    }

    private static Action step(String name, List<String> needs, List<String> produces) {
        return step(name, needs, produces, 1);
    }

    @Test
    void itWorksOutTheOrderFromTheDeclarationsAlone() {
        // Declared out of order on purpose: nothing here says write comes after research
        // except that write needs what research produces.
        List<Action> actions = List.of(
                step("write", List.of("sources"), List.of("article")),
                step("research", List.of(), List.of("sources")));

        ActionPlan plan = GoapPlanner.plan(WorldState.EMPTY,
                Objective.of("An article", "article"), actions);

        assertThat(plan.names()).containsExactly("research", "write");
    }

    @Test
    void anObjectiveAlreadyMetPlansNothing() {
        ActionPlan plan = GoapPlanner.plan(WorldState.of("article", "already written"),
                Objective.of("An article", "article"),
                List.of(step("write", List.of(), List.of("article"))));

        assertThat(plan.isEmpty()).isTrue();
        assertThat(plan.totalCost()).isZero();
    }

    @Test
    void itTakesTheCheaperOfTwoRoutesToTheSameFact() {
        List<Action> actions = List.of(
                step("expensive_search", List.of(), List.of("sources"), 5),
                step("cheap_search", List.of(), List.of("sources"), 1),
                step("write", List.of("sources"), List.of("article")));

        ActionPlan plan = GoapPlanner.plan(WorldState.EMPTY,
                Objective.of("An article", "article"), actions);

        assertThat(plan.names()).containsExactly("cheap_search", "write");
        assertThat(plan.totalCost()).isEqualTo(2);
    }

    @Test
    void aLongCheapRouteBeatsAShortExpensiveOne() {
        // The reason this is uniform-cost search rather than fewest-steps: three cheap
        // agents can be worth less than one big-model call, and cost is how you say so.
        List<Action> actions = List.of(
                step("one_big_model_call", List.of(), List.of("article"), 10),
                step("outline", List.of(), List.of("outline"), 1),
                step("draft", List.of("outline"), List.of("draft"), 1),
                step("polish", List.of("draft"), List.of("article"), 1));

        ActionPlan plan = GoapPlanner.plan(WorldState.EMPTY,
                Objective.of("An article", "article"), actions);

        assertThat(plan.names()).containsExactly("outline", "draft", "polish");
    }

    @Test
    void itUsesWhatTheStartingStateAlreadyKnows() {
        List<Action> actions = List.of(
                step("research", List.of(), List.of("sources"), 1),
                step("write", List.of("sources"), List.of("article")));

        ActionPlan plan = GoapPlanner.plan(WorldState.of("sources", "handed in by the caller"),
                Objective.of("An article", "article"), actions);

        assertThat(plan.names()).containsExactly("write");
    }

    @Test
    void anActionProducingSeveralRequiredFactsAtOnceIsUsedOnce() {
        // Also the case that rules out the obvious "how many required facts are missing"
        // heuristic: it scores the start at 2 when the true cost is 1, so it overestimates,
        // and an A* using it could return the two-step plan as if it were cheapest.
        List<Action> actions = List.of(
                step("both_at_once", List.of(), List.of("summary", "sources"), 1),
                step("just_summary", List.of(), List.of("summary"), 1),
                step("just_sources", List.of(), List.of("sources"), 1));

        ActionPlan plan = GoapPlanner.plan(WorldState.EMPTY,
                Objective.of("Both", "summary", "sources"), actions);

        assertThat(plan.names()).containsExactly("both_at_once");
        assertThat(plan.totalCost()).isEqualTo(1);
    }

    @Test
    void aCostTieIsBrokenByDeclarationOrder() {
        // Looping in one JVM over a deterministic computation proved nothing: a
        // PriorityQueue fed the same insertions sifts the same way every time, so the old
        // version of this test passed with the tie-break deleted. What pins it is that the
        // answer tracks declaration order — reverse the declarations, get the other action.
        List<String> names = List.of("alpha", "beta", "gamma", "delta", "epsilon");
        List<Action> forward = names.stream()
                .map(name -> step(name, List.of(), List.of("sources"))).toList();
        List<Action> reversed = new java.util.ArrayList<>(forward);
        java.util.Collections.reverse(reversed);
        Objective sources = Objective.of("Some sources", "sources");

        assertThat(GoapPlanner.plan(WorldState.EMPTY, sources, forward).names())
                .containsExactly("alpha");
        assertThat(GoapPlanner.plan(WorldState.EMPTY, sources, reversed).names())
                .containsExactly("epsilon");
    }

    @Test
    void aTieDeeperInTheSearchIsBrokenByDeclarationOrderToo() {
        // A single tie at depth one does not exercise the tie-break: a binary heap fed
        // equal keys hands back the first inserted anyway. This is the shape that needs it —
        // several equal-cost routes of two steps each, so equal-cost nodes are polled and
        // re-sifted against one another before the goal is reached.
        List<Action> actions = new java.util.ArrayList<>();
        for (String name : List.of("a", "b", "c", "d")) {
            actions.add(step("mid_" + name, List.of(), List.of("mid_" + name)));
            actions.add(step("end_" + name, List.of("mid_" + name), List.of("done")));
        }

        ActionPlan plan = GoapPlanner.plan(WorldState.EMPTY, Objective.of("Done", "done"), actions);

        assertThat(plan.names()).containsExactly("mid_a", "end_a");
    }

    @Test
    void anActionCostingTheMaximumIsStillPlanned() {
        // Integer.MAX_VALUE was both a legal cost and the "not reached yet" sentinel, so a
        // successor landing exactly on it was never pushed. The frontier drained and the
        // planner accused itself of a bug on a plainly reachable objective.
        ActionPlan plan = GoapPlanner.plan(WorldState.EMPTY, Objective.of("An article", "article"),
                List.of(step("expensive", List.of(), List.of("article"), Integer.MAX_VALUE)));

        assertThat(plan.names()).containsExactly("expensive");
        assertThat(plan.totalCost()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void aCostThatWouldOverflowAnIntStillPicksTheCheaperPlan() {
        // Summed as an int, the two-step route wrapped negative and was polled first — so
        // the planner returned the dearer plan and reported a negative total cost.
        List<Action> actions = List.of(
                step("direct", List.of(), List.of("article"), 2_000_000_000),
                step("step_a", List.of(), List.of("half"), 1_200_000_000),
                step("step_b", List.of("half"), List.of("article"), 1_200_000_000));

        ActionPlan plan = GoapPlanner.plan(WorldState.EMPTY,
                Objective.of("An article", "article"), actions);

        assertThat(plan.names()).containsExactly("direct");
        assertThat(plan.totalCost()).isEqualTo(2_000_000_000L);
    }

    // --- what it refuses to plan, and what it says ----------------------------

    @Test
    void aFactNoActionProducesIsNamedAsSuch() {
        assertThatThrownBy(() -> GoapPlanner.plan(WorldState.EMPTY,
                Objective.of("An article with a bibliography", "article", "bibliography"),
                List.of(step("write", List.of(), List.of("article")))))
                .isInstanceOf(UnreachableObjectiveException.class)
                .hasMessageContaining("'bibliography' is produced by no action at all");
    }

    @Test
    void aBrokenChainNamesTheActionAndTheNeedNothingEstablishes() {
        // The other diagnosis, and it wants the opposite fix: the producing action exists,
        // but its own input is what is missing.
        assertThatThrownBy(() -> GoapPlanner.plan(WorldState.EMPTY,
                Objective.of("An article", "article"),
                List.of(step("write", List.of("sources"), List.of("article")))))
                .isInstanceOf(UnreachableObjectiveException.class)
                .hasMessageContaining("'article' is produced only by 'write'")
                .hasMessageContaining("[sources]")
                .hasMessageContaining("which nothing establishes");
    }

    @Test
    void theUnreachableFactsAreAvailableWithoutParsingTheMessage() {
        assertThatThrownBy(() -> GoapPlanner.plan(WorldState.EMPTY,
                Objective.of("Two things", "article", "podcast"),
                List.of(step("write", List.of(), List.of("article")))))
                .isInstanceOf(UnreachableObjectiveException.class)
                .satisfies(e -> assertThat(((UnreachableObjectiveException) e).unreachable())
                        .containsExactly("podcast"));
    }

    @Test
    void theSearchCeilingStopsRatherThanHangs() {
        // Every fact has two producers, so nothing is forced and the search has to do the
        // work. That is the shape the ceiling exists for.
        List<Action> actions = new java.util.ArrayList<>();
        List<String> required = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            actions.add(step("cheap_" + i, List.of(), List.of("fact_" + i), 1));
            actions.add(step("dear_" + i, List.of(), List.of("fact_" + i), 2));
            required.add("fact_" + i);
        }

        assertThatThrownBy(() -> GoapPlanner.plan(WorldState.EMPTY,
                new Objective("Everything", new java.util.LinkedHashSet<>(required)), actions, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gave up after exploring 10 states");
    }

    @Test
    void fanOutIntoOneSynthesisPlansWithoutSearchingAtAll() {
        // The shape the README advertises, and the one that used to hit the ceiling at 14:
        // the lattice of 2^20 fact subsets is never explored, because each gatherer is the
        // only producer of something the synthesis needs and is therefore in every plan.
        List<Action> actions = new java.util.ArrayList<>();
        List<String> gathered = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            actions.add(step("gather_" + i, List.of(), List.of("fact_" + i)));
            gathered.add("fact_" + i);
        }
        actions.add(step("synthesise", gathered, List.of("report")));

        // A ceiling of 1 proves the search never ran, rather than merely ran quickly.
        ActionPlan plan = GoapPlanner.plan(WorldState.EMPTY,
                Objective.of("A report", "report"), actions, 1);

        assertThat(plan.size()).isEqualTo(21);
        assertThat(plan.names()).endsWith("synthesise");
    }

    @Test
    void aForcedActionIsNotCommittedWhenAnotherRouteExists() {
        // Pre-commitment is only sound for actions every plan must contain. Two producers
        // for the same fact means a choice, and a choice is what the search is for.
        List<Action> actions = List.of(
                step("dear_sources", List.of(), List.of("sources"), 9),
                step("cheap_sources", List.of(), List.of("sources"), 1),
                step("write", List.of("sources"), List.of("article")));

        ActionPlan plan = GoapPlanner.plan(WorldState.EMPTY,
                Objective.of("An article", "article"), actions);

        assertThat(plan.names()).containsExactly("cheap_sources", "write");
    }

    @Test
    void committingForcedActionsStillReturnsTheCheapestPlan() {
        // Fuzzed against brute force: for every domain, the plan's cost must equal the
        // minimum over all valid action sequences. Pre-commitment is optimality-preserving
        // in theory — facts are never consumed, so a forced action can always be moved to
        // the front — and this is the check that the implementation agrees.
        java.util.Random random = new java.util.Random(20260813L);
        int checked = 0;
        for (int trial = 0; trial < 400; trial++) {
            List<Action> actions = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                List<String> needs = new java.util.ArrayList<>();
                for (int f = 0; f < 4; f++) {
                    if (random.nextInt(4) == 0) {
                        needs.add("f" + f);
                    }
                }
                String produced = "f" + random.nextInt(4);
                needs.remove(produced);
                actions.add(step("a" + i, needs, List.of(produced), 1 + random.nextInt(5)));
            }
            Objective objective = Objective.of("goal", "f" + random.nextInt(4));
            Long brute = cheapestByBruteForce(actions, objective);
            if (brute == null) {
                continue;
            }
            checked++;
            assertThat(GoapPlanner.plan(WorldState.EMPTY, objective, actions).totalCost())
                    .describedAs("%s for %s", actions, objective.required())
                    .isEqualTo(brute);
        }
        assertThat(checked).describedAs("solvable domains exercised").isGreaterThan(50);
    }

    /** The cheapest valid sequence, by enumeration — deliberately dumb, so it is obviously right. */
    private static Long cheapestByBruteForce(List<Action> actions, Objective objective) {
        return cheapest(actions, objective, new java.util.LinkedHashSet<>(), 0L, new java.util.HashSet<>());
    }

    private static Long cheapest(List<Action> actions, Objective objective, java.util.Set<String> facts,
                                 long spent, java.util.Set<String> used) {
        if (facts.containsAll(objective.required())) {
            return spent;
        }
        Long best = null;
        for (Action action : actions) {
            if (used.contains(action.name()) || !facts.containsAll(action.needs())) {
                continue;
            }
            java.util.Set<String> next = new java.util.LinkedHashSet<>(facts);
            next.addAll(action.produces());
            java.util.Set<String> taken = new java.util.HashSet<>(used);
            taken.add(action.name());
            Long candidate = cheapest(actions, objective, next, spent + action.cost(), taken);
            if (candidate != null && (best == null || candidate < best)) {
                best = candidate;
            }
        }
        return best;
    }

    @Test
    void aPlanOfManyIndependentFactsStillFinishesWithinTheDefaultCeiling() {
        // The flip side: the ceiling must not trip on a realistic plan. Ten independent
        // facts is a large agent pipeline, and the pruning is what keeps it tractable.
        List<Action> actions = new java.util.ArrayList<>();
        List<String> required = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            actions.add(step("produce_" + i, List.of(), List.of("fact_" + i)));
            required.add("fact_" + i);
        }

        ActionPlan plan = GoapPlanner.plan(WorldState.EMPTY,
                new Objective("Ten facts", new java.util.LinkedHashSet<>(required)), actions);

        assertThat(plan.size()).isEqualTo(10);
    }

    @Test
    void anActionThatWouldAddNothingIsNotPlanned() {
        List<Action> actions = List.of(
                step("redundant", List.of(), List.of("sources")),
                step("write", List.of("sources"), List.of("article")));

        ActionPlan plan = GoapPlanner.plan(WorldState.of(Map.of("sources", "already here")),
                Objective.of("An article", "article"), actions);

        assertThat(plan.names()).containsExactly("write");
    }
}
