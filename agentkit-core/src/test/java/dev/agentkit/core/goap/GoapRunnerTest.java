package dev.agentkit.core.goap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GoapRunnerTest {

    private static final Objective ARTICLE = Objective.of("An article", "article");

    private static Action producing(String name, List<String> needs, String produces, int cost) {
        return Action.named(name).cost(cost)
                .needs(needs.toArray(String[]::new))
                .produces(produces)
                .handler(state -> ActionResult.ok(produces, name + "'s output"))
                .build();
    }

    private static Action producing(String name, List<String> needs, String produces) {
        return producing(name, needs, produces, 1);
    }

    private static Action failing(String name, List<String> needs, String produces, int cost) {
        return Action.named(name).cost(cost)
                .needs(needs.toArray(String[]::new))
                .produces(produces)
                .handler(state -> ActionResult.failed(name + " could not do it"))
                .build();
    }

    @Test
    void itRunsThePlanAndMeetsTheObjective() {
        GoapResult result = GoapRunner.forObjective(ARTICLE)
                .action(producing("write", List.of("sources"), "article"))
                .action(producing("research", List.of(), "sources"))
                .run(WorldState.EMPTY);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.stop()).isEqualTo(GoapStop.OBJECTIVE_MET);
        assertThat(result.path()).containsExactly("research", "write");
        assertThat(result.output("article")).contains("write's output");
    }

    @Test
    void aFailedActionIsRoutedAroundRatherThanFailingTheRun() {
        // The reason this exists rather than a DAG. Nothing declares a fallback edge; the
        // second route is simply the cheapest one left once the first is out.
        GoapResult result = GoapRunner.forObjective(ARTICLE)
                .action(failing("search_web", List.of(), "sources", 1))
                .action(producing("read_archive", List.of(), "sources", 3))
                .action(producing("write", List.of("sources"), "article"))
                .run(WorldState.EMPTY);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.path()).containsExactly("search_web", "read_archive", "write");
        assertThat(result.trace().get(0).succeeded()).isFalse();
        assertThat(result.trace().get(0).failure()).contains("search_web could not do it");
    }

    @Test
    void aFailedActionIsNotTriedAgain() {
        // Without setting it aside the planner picks the same cheapest action every round,
        // and the run spins until the budget is gone with a trace full of one name.
        AtomicInteger attempts = new AtomicInteger();
        Action flaky = Action.named("search_web").produces("sources")
                .handler(state -> {
                    attempts.incrementAndGet();
                    return ActionResult.failed("still down");
                }).build();

        GoapResult result = GoapRunner.forObjective(ARTICLE)
                .action(flaky)
                .action(producing("read_archive", List.of(), "sources", 3))
                .action(producing("write", List.of("sources"), "article"))
                .run(WorldState.EMPTY);

        assertThat(attempts).hasValue(1);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void whenEveryRouteIsGoneItSaysSoAndKeepsWhatItLearned() {
        GoapResult result = GoapRunner.forObjective(Objective.of("An article", "article", "notes"))
                .action(producing("take_notes", List.of(), "notes"))
                .action(failing("write", List.of("notes"), "article", 1))
                .run(WorldState.EMPTY);

        assertThat(result.stop()).isEqualTo(GoapStop.NO_ROUTE_LEFT);
        assertThat(result.path()).containsExactly("take_notes", "write");
        // The partial result is usually the useful part, so it survives the failure.
        assertThat(result.output("notes")).contains("take_notes's output");
        assertThat(result.message()).isPresent();
    }

    @Test
    void anObjectiveThatWasNeverReachableIsDistinguishedFromOneThatRanOut() {
        // Two different mistakes: this one is a wiring error you want at startup, the other
        // is a run that hit reality. Reporting both as "failed" hides which.
        GoapResult result = GoapRunner.forObjective(Objective.of("A podcast", "podcast"))
                .action(producing("write", List.of(), "article"))
                .run(WorldState.EMPTY);

        assertThat(result.stop()).isEqualTo(GoapStop.NO_PLAN_AT_ALL);
        assertThat(result.trace()).isEmpty();
        assertThat(result.message().orElseThrow()).contains("produced by no action at all");
    }

    @Test
    void anActionThatDoesNotEstablishWhatItPromisedStopsTheRun() {
        // The failure mode that would otherwise be invisible: the planner keeps choosing the
        // cheapest route to a fact the action claims to produce and never does, and the run
        // burns its whole budget on one lying step.
        Action liar = Action.named("write").produces("article")
                .handler(state -> ActionResult.ok("something_else", "not what was promised")).build();

        GoapResult result = GoapRunner.forObjective(ARTICLE)
                .action(liar)
                .run(WorldState.EMPTY);

        assertThat(result.stop()).isEqualTo(GoapStop.BROKEN_ACTION);
        assertThat(result.message().orElseThrow())
                .contains("'write' reported success without establishing [article]");
        // What it did establish is kept, so the mistake is diagnosable from the result.
        assertThat(result.fact("something_else")).isPresent();
    }

    @Test
    void anActionEstablishingOnlyWhatWasStillMissingIsNotCalledBroken() {
        // A action producing {a, b} in a world that already has {a} legitimately returns
        // only b. Checking its return value rather than the resulting state failed the run
        // for doing exactly the right thing — and this is the overlapping-producer shape
        // the whole design is sold on.
        Action combo = Action.named("combo").produces("notes", "article")
                .handler(state -> ActionResult.ok("article", "a")).build();

        GoapResult result = GoapRunner.forObjective(Objective.of("Both", "notes", "article"))
                .action(combo)
                .run(WorldState.of("notes", "already taken"));

        assertThat(result.stop()).isEqualTo(GoapStop.OBJECTIVE_MET);
        assertThat(result.fact("notes")).isPresent();
        assertThat(result.fact("article")).isPresent();
    }

    @Test
    void aPlannerThatGivesUpComesBackAsAResultRatherThanAnException() {
        // run() promises a GoapResult. The search ceiling threw IllegalStateException
        // straight out of it, losing every fact established up to that point.
        List<Action> actions = new ArrayList<>();
        List<String> required = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            actions.add(producing("cheap_" + i, List.of(), "fact_" + i, 1));
            actions.add(producing("dear_" + i, List.of(), "fact_" + i, 2));
            required.add("fact_" + i);
        }

        GoapResult result = GoapRunner
                .forObjective(new Objective("Everything", new java.util.LinkedHashSet<>(required)))
                .actions(actions)
                .maxPlanStates(10)
                .run(WorldState.EMPTY);

        assertThat(result.stop()).isEqualTo(GoapStop.PLANNING_GAVE_UP);
        assertThat(result.message().orElseThrow()).contains("gave up after exploring");
    }

    @Test
    void anActionThatThrowsIsReportedWithEverythingLearnedBeforeIt() {
        Action explodes = Action.named("write").needs("sources").produces("article")
                .handler(state -> {
                    throw new IllegalStateException("the writer fell over");
                }).build();

        GoapResult result = GoapRunner.forObjective(ARTICLE)
                .action(producing("research", List.of(), "sources"))
                .action(explodes)
                .run(WorldState.EMPTY);

        assertThat(result.stop()).isEqualTo(GoapStop.ACTION_THREW);
        assertThat(result.error()).get().isInstanceOf(IllegalStateException.class);
        assertThat(result.output("sources")).contains("research's output");
        assertThat(result.path()).containsExactly("research", "write");
    }

    @Test
    void anErrorOutOfAnActionDoesNotDestroyTheRunsResults() {
        // Error rather than RuntimeException: an AssertionError or a StackOverflowError out
        // of an agent is still a failed action, and catching only the former lost every fact
        // established up to that point.
        Action explodes = Action.named("write").needs("sources").produces("article")
                .handler(state -> {
                    throw new AssertionError("assertion inside the writer");
                }).build();

        GoapResult result = GoapRunner.forObjective(ARTICLE)
                .action(producing("research", List.of(), "sources"))
                .action(explodes)
                .run(WorldState.EMPTY);

        assertThat(result.stop()).isEqualTo(GoapStop.ACTION_THREW);
        assertThat(result.error()).get().isInstanceOf(AssertionError.class);
        assertThat(result.output("sources")).contains("research's output");
    }

    @Test
    void theActionBudgetIsAHardStop() {
        List<Action> manyFailures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            manyFailures.add(failing("attempt_" + i, List.of(), "article", i + 1));
        }

        GoapResult result = GoapRunner.forObjective(ARTICLE)
                .actions(manyFailures)
                .maxActions(3)
                .run(WorldState.EMPTY);

        assertThat(result.stop()).isEqualTo(GoapStop.OUT_OF_ACTIONS);
        assertThat(result.trace()).hasSize(3);
        assertThat(result.message().orElseThrow()).contains("[article]");
    }

    @Test
    void anObjectiveAlreadyMetRunsNothing() {
        AtomicInteger ran = new AtomicInteger();
        GoapResult result = GoapRunner.forObjective(ARTICLE)
                .action(Action.named("write").produces("article").handler(state -> {
                    ran.incrementAndGet();
                    return ActionResult.ok("article", "x");
                }).build())
                .run(WorldState.of("article", "handed in already"));

        assertThat(ran).hasValue(0);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.trace()).isEmpty();
    }

    @Test
    void stepsAndUsageRollUpFromTheAgentActionsThatRan() {
        // Same bargain GraphNode makes by returning an AgentResult: an action that was an
        // agent run reports its tokens into the run's total rather than vanishing.
        Action metered = Action.named("research").produces("sources")
                .handler(state -> ActionResult.ok(Map.of("sources", "s"),
                        AgentResult.completed("s", 3, new TokenUsage(100, 20)))).build();
        Action alsoMetered = Action.named("write").needs("sources").produces("article")
                .handler(state -> ActionResult.ok(Map.of("article", "a"),
                        AgentResult.completed("a", 2, new TokenUsage(50, 10)))).build();

        GoapResult result = GoapRunner.forObjective(ARTICLE)
                .action(metered).action(alsoMetered).run(WorldState.EMPTY);

        assertThat(result.steps()).isEqualTo(5);
        assertThat(result.usage()).isEqualTo(new TokenUsage(150, 30));
    }

    @Test
    void aFailedAgentActionCountsItsTokensToo() {
        // The expensive case: a run that failed still cost money, and a total that quietly
        // omitted it would under-report exactly the runs worth investigating.
        Action failed = Action.named("write").produces("article")
                .handler(state -> ActionResult.failed("gave up",
                        AgentResult.stopped(StopReason.MAX_STEPS, "", 4, new TokenUsage(900, 80)))).build();

        GoapResult result = GoapRunner.forObjective(ARTICLE).action(failed).run(WorldState.EMPTY);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.usage()).isEqualTo(new TokenUsage(900, 80));
        assertThat(result.steps()).isEqualTo(4);
    }

    @Test
    void planFromShowsTheOrderWithoutRunningAnything() {
        AtomicInteger ran = new AtomicInteger();
        GoapRunner runner = GoapRunner.forObjective(ARTICLE)
                .action(Action.named("research").produces("sources").handler(state -> {
                    ran.incrementAndGet();
                    return ActionResult.ok("sources", "s");
                }).build())
                .action(producing("write", List.of("sources"), "article"))
                .build();

        assertThat(runner.planFrom(WorldState.EMPTY).names()).containsExactly("research", "write");
        assertThat(ran).hasValue(0);
    }

    @Test
    void planFromFailsAtWiringTimeRatherThanAfterTwoAgentsHaveBeenPaidFor() {
        GoapRunner runner = GoapRunner.forObjective(Objective.of("A podcast", "podcast"))
                .action(producing("write", List.of(), "article"))
                .build();

        assertThatThrownBy(() -> runner.planFrom(WorldState.EMPTY))
                .isInstanceOf(UnreachableObjectiveException.class);
    }

    @Test
    void theRawHandlerSeesEveryFactNotJustItsDeclaredNeeds() {
        // Named for what actually happens. It used to be named for the opposite, and
        // asserted only that the declared fact was present — true under both behaviours —
        // so it would have passed had run() narrowed the state. Only agent(...) narrows.
        List<String> seen = new ArrayList<>();
        Action write = Action.named("write").needs("sources").produces("article")
                .handler(state -> {
                    seen.addAll(state.keys());
                    return ActionResult.ok("article", "a");
                }).build();

        GoapRunner.forObjective(Objective.of("An article and notes", "article", "notes"))
                .action(producing("research", List.of(), "sources"))
                .action(producing("take_notes", List.of(), "notes"))
                .action(write)
                .maxActions(5)
                .run(WorldState.EMPTY);

        // "notes" was never declared by this action, and it is handed over anyway. That is
        // the trap worth pinning: an action meant to judge something independently must
        // read only what it declared, because nothing enforces it here.
        assertThat(seen).contains("sources", "notes");
    }

    @Test
    void anObserverSeesThePlanChangeWhenAnActionFails() {
        // The reason this seam exists: the order is computed, so a run is otherwise opaque
        // until it is over. What an observer can see and a wrapped handler cannot is that
        // the second plan is a different plan.
        List<List<String>> plans = new ArrayList<>();
        List<String> ended = new ArrayList<>();
        GoapObserver observer = new GoapObserver() {
            @Override
            public void onPlan(ActionPlan plan, WorldState state) {
                plans.add(plan.names());
            }

            @Override
            public void onActionEnd(Action action, ActionOutcome outcome) {
                ended.add(action.name() + (outcome.succeeded() ? ":ok" : ":failed"));
            }

            @Override
            public void onFinish(GoapResult result) {
                ended.add("finished:" + result.stop());
            }
        };

        GoapRunner.forObjective(ARTICLE)
                .action(failing("search_web", List.of(), "sources", 1))
                .action(producing("read_archive", List.of(), "sources", 3))
                .action(producing("write", List.of("sources"), "article"))
                .observer(observer)
                .run(WorldState.EMPTY);

        assertThat(plans).containsExactly(
                List.of("search_web", "write"),
                List.of("read_archive", "write"),
                List.of("write"));
        assertThat(ended).containsExactly(
                "search_web:failed", "read_archive:ok", "write:ok", "finished:OBJECTIVE_MET");
    }

    @Test
    void theObserverIsToldWhyARunEndedBadlyToo() {
        List<String> finished = new ArrayList<>();

        GoapRunner.forObjective(Objective.of("A podcast", "podcast"))
                .action(producing("write", List.of(), "article"))
                .observer(new GoapObserver() {
                    @Override
                    public void onFinish(GoapResult result) {
                        finished.add(result.stop().name());
                    }
                })
                .run(WorldState.EMPTY);

        assertThat(finished).containsExactly("NO_PLAN_AT_ALL");
    }

    @Test
    void twoActionsCannotShareAName() {
        assertThatThrownBy(() -> GoapRunner.forObjective(ARTICLE)
                .action(producing("write", List.of(), "article"))
                .action(producing("write", List.of(), "draft")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Two actions are named 'write'");
    }

    @Test
    void aRunnerWithNoActionsIsRejectedAtBuildTime() {
        assertThatThrownBy(() -> GoapRunner.forObjective(ARTICLE).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no actions");
    }
}
