package dev.agentkit.core.goap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * An observer cannot decide whether a GOAP run completes (#173).
 *
 * <h2>The defect</h2>
 *
 * <p>{@code GoapRunner} called its observer in five places and guarded none of them, and
 * said so in {@code GoapObserver}'s javadoc: "an observer that throws takes the run down
 * with it — this is a reporting seam, not a policy one". The second half is the premise for
 * the opposite conclusion. A reporting seam that can abort the thing it reports on is not
 * one, and {@code onActionEnd} fires <em>after</em> the action has run. Measured before the
 * fix, with an observer throwing there:
 *
 * <pre>
 * action actually ran : true
 * GoapResult returned : none — the exception escaped run()
 * onFinish called     : false
 * </pre>
 *
 * <p>The side effect happened, every fact the trace had established went with the
 * exception, and an observer keeping per-run state was left holding a run it would never
 * see the end of — one line away from the {@code catch (RuntimeException | Error)} the
 * runner puts around the action itself for exactly that reason.
 *
 * <h2>Why it is the same answer {@code Agent} gives</h2>
 *
 * <p>#166 made {@code AgentObserver} absorb, log and report through a handler. GOAP
 * answered the other way, on purpose, and nothing told a reader which contract they were
 * holding except reading both classes. The failure mode is identical, so the answer is now
 * identical and both javadocs say it in the same words.
 */
class GoapObserverThrowsTest {

    private static final Objective ARTICLE = Objective.of("An article", "article");

    private static Action producing(String name, List<String> needs, String produces) {
        return Action.named(name)
                .needs(needs.toArray(String[]::new))
                .produces(produces)
                .handler(state -> ActionResult.ok(produces, name + "'s output"))
                .build();
    }

    /** Throws in one named callback and records every callback it was asked for. */
    private static final class Throwing implements GoapObserver {
        private final String failIn;
        private final List<String> called = new ArrayList<>();

        Throwing(String failIn) {
            this.failIn = failIn;
        }

        private void maybeThrow(String callback) {
            called.add(callback);
            if (callback.equals(failIn)) {
                throw new IllegalStateException("the audit sink is down");
            }
        }

        @Override
        public void onPlan(ActionPlan plan, WorldState state) {
            maybeThrow("onPlan");
        }

        @Override
        public void onActionStart(Action action, WorldState state) {
            maybeThrow("onActionStart");
        }

        @Override
        public void onActionEnd(Action action, ActionOutcome outcome) {
            maybeThrow("onActionEnd");
        }

        @Override
        public void onFinish(GoapResult result) {
            maybeThrow("onFinish");
        }
    }

    private static GoapResult runWith(GoapObserver observer,
                                      java.util.function.BiConsumer<String, Throwable> handler) {
        return GoapRunner.forObjective(ARTICLE)
                .action(producing("write", List.of("sources"), "article"))
                .action(producing("research", List.of(), "sources"))
                .observer(observer)
                .onObservationFailure(handler)
                .run(WorldState.EMPTY);
    }

    @Test
    void aThrowInEveryCallbackLeavesTheRunIntact() {
        // All four, because guarding four of five sites is the shape this defect had in
        // Agent: the one left unguarded was the one that fires after the work.
        for (String callback : List.of("onPlan", "onActionStart", "onActionEnd", "onFinish")) {
            List<String> failures = new ArrayList<>();
            Throwing observer = new Throwing(callback);

            GoapResult result = runWith(observer, (name, failure) -> failures.add(name));

            assertThat(result.stop())
                    .as("an observer throwing in %s decided the run's outcome", callback)
                    .isEqualTo(GoapStop.OBJECTIVE_MET);
            assertThat(result.output("article"))
                    .as("the run reported success in %s's presence but produced nothing",
                            callback)
                    .isPresent();
            assertThat(failures)
                    .as("the deployment was not told which callback failed")
                    .contains(callback);
        }
    }

    @Test
    void onFinishStillFiresWhenAnEarlierCallbackThrew() {
        // The half that made this worse than an ordinary crash. An observer keeping per-run
        // state is the one most likely to exist in a deployment that cares, and losing
        // onFinish leaves it holding a run that never ends.
        Throwing observer = new Throwing("onActionEnd");

        runWith(observer, (name, failure) -> { });

        assertThat(observer.called)
                .as("an earlier throw stole the closing callback")
                .contains("onFinish");
    }

    @Test
    void theActionRanAndItsFactsSurviveTheThrowThatFollowedIt() {
        // onActionEnd fires after the action, so a throw there loses a run whose side
        // effect has already happened. This is the measurement in the class javadoc, as an
        // assertion: the trace and the world state both survive.
        AtomicBoolean ran = new AtomicBoolean();
        Action recorded = Action.named("research").produces("sources")
                .handler(state -> {
                    ran.set(true);
                    return ActionResult.ok("sources", "what research found");
                })
                .build();

        GoapResult result = GoapRunner.forObjective(ARTICLE)
                .action(producing("write", List.of("sources"), "article"))
                .action(recorded)
                .observer(new Throwing("onActionEnd"))
                .run(WorldState.EMPTY);

        assertThat(ran).as("the action did not run, so this test measured nothing").isTrue();
        assertThat(result.state().keys()).contains("sources", "article");
        assertThat(result.trace()).as("the facts the trace had established went with the"
                + " exception").isNotEmpty();
    }

    @Test
    void anErrorIsAbsorbedToo() {
        // Throwable and not RuntimeException, for Observations' stated reason: an observer
        // allocating per call is where a StackOverflowError turns up, and a broken observer
        // class is exactly a NoClassDefFoundError. Catching only RuntimeException would
        // leave those doing what they did before.
        List<String> failures = new ArrayList<>();

        GoapResult result = runWith(new GoapObserver() {
            @Override
            public void onActionEnd(Action action, ActionOutcome outcome) {
                throw new AssertionError("a broken observer class");
            }
        }, (name, failure) -> failures.add(name + ":" + failure.getClass().getSimpleName()));

        assertThat(result.stop()).isEqualTo(GoapStop.OBJECTIVE_MET);
        assertThat(failures).contains("onActionEnd:AssertionError");
    }

    @Test
    void aHandlerThatThrowsIsAbsorbedRatherThanEndingTheRun() {
        // Otherwise the defect moves one level up, which is the whole point of the guard.
        GoapResult result = runWith(new Throwing("onActionEnd"), (name, failure) -> {
            throw new IllegalStateException("the handler is broken too");
        });

        assertThat(result.stop()).isEqualTo(GoapStop.OBJECTIVE_MET);
    }

    @Test
    void anActionThatThrewStillReportsThroughAnObserverThatThrowsToo() {
        // The fifth dispatch site, and the only one the other tests cannot reach: it fires
        // inside run's own catch around action.run, so it needs BOTH a throwing action and
        // a throwing observer. Found by a mutation pass -- unwrapping this one site alone
        // survived every test above.
        //
        // It is the worst of the five to leave unguarded. The runner has just caught an
        // action's exception specifically so the run can report ACTION_THREW instead of
        // losing every fact established so far; an unguarded observer on that path throws
        // the report away in the middle of writing it, and what reaches the caller is the
        // observer's exception rather than the action's.
        Action explodes = Action.named("research").produces("sources")
                .handler(state -> {
                    throw new IllegalStateException("the research backend is down");
                })
                .build();
        List<String> failures = new ArrayList<>();

        GoapResult result = GoapRunner.forObjective(ARTICLE)
                .action(producing("write", List.of("sources"), "article"))
                .action(explodes)
                .observer(new Throwing("onActionEnd"))
                .onObservationFailure((name, failure) -> failures.add(name))
                .run(WorldState.EMPTY);

        assertThat(result.stop())
                .as("the observer's exception replaced the action's, so the caller was told"
                        + " the wrong thing went wrong")
                .isEqualTo(GoapStop.ACTION_THREW);
        assertThat(result.error()).get().isInstanceOf(IllegalStateException.class);
        assertThat(result.trace())
                .as("the outcome the runner caught the exception in order to record")
                .isNotEmpty();
        assertThat(failures).contains("onActionEnd");
    }

    @Test
    void theDefaultHandlerSaysSomethingRatherThanNothing() {
        // Also found by a mutation pass: replacing the default with a silent no-op survived
        // every test, because every other test here supplies its own handler. A deployment
        // that sets none is the common case, and for it the log line is the entire report.
        // Absorbing quietly would turn "the audit sink is down" into no evidence at all,
        // which is the failure #166 named -- "the observer threw" and "there is no record
        // of what just happened" are the same event.
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true,
                java.nio.charset.StandardCharsets.UTF_8));
        GoapResult result;
        try {
            result = GoapRunner.forObjective(ARTICLE)
                    .action(producing("write", List.of("sources"), "article"))
                    .action(producing("research", List.of(), "sources"))
                    .observer(new Throwing("onActionEnd"))
                    .run(WorldState.EMPTY);
        } finally {
            System.setErr(original);
        }

        assertThat(result.stop()).isEqualTo(GoapStop.OBJECTIVE_MET);
        assertThat(captured.toString(java.nio.charset.StandardCharsets.UTF_8))
                .as("no handler was set and the failure went unmentioned")
                .contains("onActionEnd")
                // And that it was absorbed, which is the half the callback name does not
                // carry (#225). The run ends OBJECTIVE_MET either way, so nothing else an
                // operator can reach distinguishes "the observer threw and the run went on"
                // from "the observer was never called" — and those are opposite diagnoses
                // for the same missing audit row. A mutant that trims this line back to the
                // callback name passes the assertion above and says neither.
                .contains("threw; the run continues");
    }

    @Test
    void anObserverThatLosesAnInterruptHasItPutBack() {
        // Absorbing is otherwise a cancellation regression. A GoapObserver cannot declare
        // InterruptedException, so an observer doing blocking work has to wrap it, and
        // Thread.sleep has already cleared the flag by the time it throws. Absorbing the
        // wrapper without restoring the flag would leave the run taking further actions
        // after its caller had cancelled it.
        Thread.interrupted();
        GoapResult result = runWith(new GoapObserver() {
            @Override
            public void onActionEnd(Action action, ActionOutcome outcome) {
                throw new IllegalStateException("wrapped", new InterruptedException("cancelled"));
            }
        }, (name, failure) -> { });

        boolean stillInterrupted = Thread.interrupted();
        assertThat(result.stop()).isEqualTo(GoapStop.OBJECTIVE_MET);
        assertThat(stillInterrupted)
                .as("the absorbed wrapper carried a cancellation and the flag was dropped")
                .isTrue();
    }
}
