package dev.agentkit.core.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AgentGraphTest {

    private static final Goal GOAL = Goal.of("write a briefing");

    private static GraphNode says(String output) {
        return input -> AgentResult.completed(output, 1, new TokenUsage(10, 5));
    }

    private static GraphNode fails(String message) {
        return input -> AgentResult.failed(new IllegalStateException(message), 1, new TokenUsage(3, 0));
    }

    private static GraphNode records(List<String> log, String name, String output) {
        return input -> {
            log.add(name);
            return AgentResult.completed(output, 1, TokenUsage.ZERO);
        };
    }

    private static GraphNode blocksFor(Duration duration) {
        return input -> {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentResult.completed("done", 1);
        };
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Spins until {@code condition} holds, so a test fails with a message not a hang. */
    private static void await(java.util.function.BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(10));
        }
        throw new AssertionError(message);
    }

    // --- structure -----------------------------------------------------------

    @Test
    void aLinearPipelineRunsInOrderAndCarriesOutputForward() {
        List<String> ran = new CopyOnWriteArrayList<>();
        AgentGraph graph = AgentGraph.builder()
                .node("research", records(ran, "research", "facts"))
                .node("draft", input -> {
                    ran.add("draft");
                    // The point of an edge: the downstream node sees what came before.
                    return AgentResult.completed("draft using " + input.outputOf("research").orElseThrow(), 1);
                })
                .edge("research", "draft")
                .build();

        GraphResult result = graph.run(GOAL);

        assertThat(ran).containsExactly("research", "draft");
        assertThat(result.output("draft")).contains("draft using facts");
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void aNodeSeesOnlyItsOwnDependencies() {
        // "b" is forced to finish first so this cannot pass by "a" happening to win the
        // race: if the graph fed every finished node forward, "c" would see b and a.
        CountDownLatch bDone = new CountDownLatch(1);
        AgentGraph graph = AgentGraph.builder()
                .node("b", input -> {
                    bDone.countDown();
                    return AgentResult.completed("B", 1);
                })
                .node("a", input -> {
                    try {
                        if (!bDone.await(10, TimeUnit.SECONDS)) {
                            return AgentResult.failed(new IllegalStateException("b never ran"), 0);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return AgentResult.completed("A", 1);
                })
                .node("c", input -> AgentResult.completed(
                        "saw " + input.dependencies().keySet(), 1))
                .edge("a", "c")
                .build();

        assertThat(graph.run(GOAL).output("c")).contains("saw [a]");
    }

    @Test
    void aCascadeOfSkipsResolvesWhenNodesAreDeclaredOutOfTopologicalOrder() {
        // The scheduler's fixpoint pass exists for this: declared publish-first, one pass
        // over the pending set resolves publish before draft and leaves it stuck, and the
        // graph then reports a stall on a perfectly ordinary DAG.
        AgentGraph graph = AgentGraph.builder()
                .node("publish", says("never"))
                .node("draft", says("never"))
                .node("research", fails("no sources"))
                .edge("research", "draft")
                .edge("draft", "publish")
                .build();

        GraphResult result = graph.run(GOAL);

        assertThat(result.outcome("research").orElseThrow().state()).isEqualTo(NodeState.FAILED);
        assertThat(result.outcome("draft").orElseThrow().state()).isEqualTo(NodeState.SKIPPED);
        assertThat(result.outcome("publish").orElseThrow().state()).isEqualTo(NodeState.SKIPPED);
    }

    @Test
    void anErrorFromANodeIsThatNodesFailureAndNotTheGraphs() {
        // An AssertionError from an assert or an assertion library inside a node is the
        // realistic trigger; letting it escape into the Future destroys every other
        // node's completed work on the way out.
        AgentGraph graph = AgentGraph.builder()
                .node("expensive", says("hard-won answer"))
                .node("boom", input -> {
                    throw new AssertionError("the JVM had opinions");
                })
                .build();

        GraphResult result = graph.run(GOAL);

        assertThat(result.output("expensive")).contains("hard-won answer");
        NodeOutcome boom = result.outcome("boom").orElseThrow();
        assertThat(boom.state()).isEqualTo(NodeState.FAILED);
        assertThat(boom.result().orElseThrow().error()).get().isInstanceOf(AssertionError.class);
    }

    @Test
    void leafOutputsCollectTheAnswersWithoutTheCallerNamingTheLastNode() {
        AgentGraph graph = AgentGraph.builder()
                .node("check", says("ISSUE: wrong"))
                .node("revise", says("revised"))
                .node("publish", says("published"))
                .edge("check", "revise", r -> r.output().contains("ISSUE"))
                .edge("check", "publish", r -> !r.output().contains("ISSUE"))
                .build();

        assertThat(graph.leaves()).containsExactly("revise", "publish");
        // Only the arm that ran is here, so a caller reads the answer without knowing
        // which branch the graph took.
        assertThat(graph.run(GOAL).leafOutputs()).containsExactly(entry("revise", "revised"));
    }

    @Test
    void aCleanRunThatProducedNothingIsVisibleAsSuch() {
        // isSuccess() is about whether the graph ran as written, not whether it answered:
        // a branch whose every arm was legitimately skipped did exactly what it was told.
        AgentGraph graph = AgentGraph.builder()
                .node("check", says("all clear"))
                .node("fix", says("never"))
                .edge("check", "fix", r -> r.output().contains("PROBLEM"))
                .build();

        GraphResult result = graph.run(GOAL);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.leafOutputs()).isEmpty();   // ... and this is how you tell
    }

    @Test
    void independentBranchesBothRunAndJoin() {
        AgentGraph graph = AgentGraph.builder()
                .node("split", says("topic"))
                .node("left", says("L"))
                .node("right", says("R"))
                .node("join", input -> AgentResult.completed(
                        input.outputOf("left").orElseThrow() + input.outputOf("right").orElseThrow(), 1))
                .edge("split", "left")
                .edge("split", "right")
                .edge("left", "join")
                .edge("right", "join")
                .build();

        GraphResult result = graph.run(GOAL);

        assertThat(result.output("join")).contains("LR");
        assertThat(result.outcomes()).hasSize(4)
                .allSatisfy((name, outcome) -> assertThat(outcome.state()).isEqualTo(NodeState.COMPLETED));
    }

    @Test
    void outcomesAreReportedInDeclarationOrderForEveryDeclaredNode() {
        // Including nodes that never ran: a missing key would make callers guess whether
        // a node was skipped or the graph forgot about it.
        AgentGraph graph = AgentGraph.builder()
                .node("first", fails("nope"))
                .node("second", says("x"))
                .node("third", says("y"))
                .edge("first", "third")
                .build();

        GraphResult result = graph.run(GOAL);

        assertThat(result.outcomes().keySet()).containsExactly("first", "second", "third");
        assertThat(result.outcome("third").orElseThrow().state()).isEqualTo(NodeState.SKIPPED);
    }

    // --- conditions and skipping ---------------------------------------------

    @Test
    void aFailedNodeSkipsEverythingDownstreamOfIt() {
        AgentGraph graph = AgentGraph.builder()
                .node("research", fails("source unavailable"))
                .node("draft", says("never"))
                .node("publish", says("never"))
                .edge("research", "draft")
                .edge("draft", "publish")
                .build();

        GraphResult result = graph.run(GOAL);

        assertThat(result.outcome("research").orElseThrow().state()).isEqualTo(NodeState.FAILED);
        // The skip cascades: publish's upstream never ran either.
        assertThat(result.outcome("draft").orElseThrow().state()).isEqualTo(NodeState.SKIPPED);
        assertThat(result.outcome("publish").orElseThrow().state()).isEqualTo(NodeState.SKIPPED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failures()).extracting(NodeOutcome::name).containsExactly("research");
    }

    @Test
    void anAlwaysEdgeCarriesOnPastAFailure() {
        // The escape hatch for a cleanup or reporting node.
        AgentGraph graph = AgentGraph.builder()
                .node("work", fails("boom"))
                .node("report", input -> AgentResult.completed(
                        "reported " + input.resultOf("work").orElseThrow().stopReason(), 1))
                .edge("work", "report", AgentGraph.ALWAYS)
                .build();

        GraphResult result = graph.run(GOAL);

        assertThat(result.output("report")).contains("reported ERROR");
    }

    @Test
    void aConditionalBranchTakesExactlyOneArm() {
        AgentGraph graph = AgentGraph.builder()
                .node("check", says("ISSUE: date is wrong"))
                .node("revise", says("revised"))
                .node("publish", says("published"))
                .edge("check", "revise", r -> r.output().contains("ISSUE"))
                .edge("check", "publish", r -> !r.output().contains("ISSUE"))
                .build();

        GraphResult result = graph.run(GOAL);

        assertThat(result.outcome("revise").orElseThrow().state()).isEqualTo(NodeState.COMPLETED);
        assertThat(result.outcome("publish").orElseThrow().state()).isEqualTo(NodeState.SKIPPED);
    }

    @Test
    void anAnyJoinRunsOnOneLiveArmAndSeesOnlyThatArm() {
        // The aggregator's policy, and deliberately opt-in: a node running on partial
        // input produces a whole-looking answer with nothing in it to say so.
        AgentGraph graph = AgentGraph.builder()
                .node("left", fails("dead"))
                .node("right", says("R"))
                .node("join", input -> AgentResult.completed(
                        "joined " + input.dependencies().keySet(), 1), JoinPolicy.ANY)
                .edge("left", "join")
                .edge("right", "join")
                .build();

        GraphResult result = graph.run(GOAL);

        assertThat(result.outcome("join").orElseThrow().state()).isEqualTo(NodeState.COMPLETED);
        assertThat(result.output("join")).contains("joined [right]");
    }

    @Test
    void anAnyJoinWithEveryArmDeadIsItselfSkipped() {
        AgentGraph graph = AgentGraph.builder()
                .node("left", fails("dead"))
                .node("right", fails("also dead"))
                .node("join", says("never"), JoinPolicy.ANY)
                .edge("left", "join")
                .edge("right", "join")
                .build();

        assertThat(graph.run(GOAL).outcome("join").orElseThrow().state()).isEqualTo(NodeState.SKIPPED);
    }

    @Test
    void theDefaultJoinIsABarrierSoAMissingInputStopsTheNode() {
        // The default, and the safe direction for a mistake: ALL leaves an absence you
        // notice, where ANY would hand the node half its data and let it answer anyway.
        AgentGraph graph = AgentGraph.builder()
                .node("left", fails("dead"))
                .node("right", says("R"))
                .node("join", says("never"))
                .edge("left", "join")
                .edge("right", "join")
                .build();

        assertThat(graph.run(GOAL).outcome("join").orElseThrow().state()).isEqualTo(NodeState.SKIPPED);
    }

    @Test
    void dataAndAGateCanReachTheSameNode() {
        // The composition ALL exists for: revise needs the draft's text, and must only
        // run when the check found problems. Under ANY either edge alone fires it, so the
        // editor gets a list of complaints with no draft to apply them to.
        GraphNode revise = input -> AgentResult.completed(
                "revised[" + input.outputOf("draft").orElse("NO DRAFT") + "]", 1);

        AgentGraph withIssue = AgentGraph.builder()
                .node("draft", says("DRAFT"))
                .node("check", says("ISSUE: para 2"))
                .node("revise", revise)
                .edge("draft", "check")
                .edge("draft", "revise")
                .edge("check", "revise", r -> r.output().contains("ISSUE"))
                .build();

        assertThat(withIssue.run(GOAL).output("revise")).contains("revised[DRAFT]");

        AgentGraph clean = AgentGraph.builder()
                .node("draft", says("DRAFT"))
                .node("check", says("all facts fine"))
                .node("revise", revise)
                .edge("draft", "check")
                .edge("draft", "revise")
                .edge("check", "revise", r -> r.output().contains("ISSUE"))
                .build();

        // The gate still gates: the draft edge alone is not enough to fire it.
        assertThat(clean.run(GOAL).outcome("revise").orElseThrow().state()).isEqualTo(NodeState.SKIPPED);
    }

    // --- failure containment --------------------------------------------------

    @Test
    void aNodeThatThrowsIsRecordedAsFailedRatherThanTakingTheGraphDown() {
        AgentGraph graph = AgentGraph.builder()
                .node("boom", input -> {
                    throw new IllegalStateException("kaboom");
                })
                .node("unrelated", says("fine"))
                .build();

        GraphResult result = graph.run(GOAL);

        NodeOutcome boom = result.outcome("boom").orElseThrow();
        assertThat(boom.state()).isEqualTo(NodeState.FAILED);
        assertThat(boom.result().orElseThrow().error()).get()
                .isInstanceOf(IllegalStateException.class);
        // An independent branch is unaffected.
        assertThat(result.output("unrelated")).contains("fine");
    }

    @Test
    void aFailedNodeKeepsItsResultSoPartialWorkIsNotLost() {
        AgentGraph graph = AgentGraph.builder().node("work", fails("half done")).build();

        NodeOutcome outcome = graph.run(GOAL).outcome("work").orElseThrow();

        assertThat(outcome.ran()).isTrue();
        assertThat(outcome.result()).isPresent();
        // But output() is empty: a failed run's text is not an answer.
        assertThat(outcome.output()).isEmpty();
    }

    // --- totals ---------------------------------------------------------------

    @Test
    void stepsAndUsageAreSummedOverEveryNodeThatRan() {
        AgentGraph graph = AgentGraph.builder()
                .node("a", says("A"))          // 1 step, 10/5
                .node("b", says("B"))          // 1 step, 10/5
                .node("c", fails("x"))         // 1 step, 3/0 — a failure still costs tokens
                .node("skipped", says("never"))
                .edge("c", "skipped")
                .build();

        GraphResult result = graph.run(GOAL);

        assertThat(result.steps()).isEqualTo(3);
        assertThat(result.usage()).isEqualTo(new TokenUsage(23, 10));
    }

    // --- concurrency ----------------------------------------------------------

    @Test
    void independentNodesRunConcurrently() throws Exception {
        // Both nodes block until the other has started; if the graph serialised them this
        // would time out rather than fail an assertion.
        CountDownLatch both = new CountDownLatch(2);
        GraphNode rendezvous = input -> {
            both.countDown();
            try {
                if (!both.await(5, TimeUnit.SECONDS)) {
                    return AgentResult.failed(new IllegalStateException("ran serially"), 0);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AgentResult.failed(e, 0);
            }
            return AgentResult.completed("ok", 1);
        };

        GraphResult result = AgentGraph.builder()
                .node("left", rendezvous).node("right", rendezvous).build().run(GOAL);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void maxConcurrencyBoundsHowManyNodesRunAtOnce() {
        AtomicInteger live = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        GraphNode busy = input -> {
            peak.accumulateAndGet(live.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            live.decrementAndGet();
            return AgentResult.completed("ok", 1);
        };

        AgentGraph.Builder builder = AgentGraph.builder().maxConcurrency(2);
        for (int i = 0; i < 6; i++) {
            builder.node("n" + i, busy);
        }
        GraphResult result = builder.build().run(GOAL);

        assertThat(result.isSuccess()).isTrue();
        assertThat(peak.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void anInjectedExecutorIsUsedAndNotShutDown() throws Exception {
        // Asserting the pool ran the node, not merely that it survived: without the first
        // check, a graph that ignored the injection entirely would still pass.
        try (ExecutorService pool = Executors.newFixedThreadPool(2, runnable ->
                new Thread(runnable, "injected-pool"))) {
            AtomicReference<String> ranOn = new AtomicReference<>();
            AgentGraph graph = AgentGraph.builder()
                    .node("a", input -> {
                        ranOn.set(Thread.currentThread().getName());
                        return AgentResult.completed("A", 1);
                    })
                    .executor(pool)
                    .build();

            assertThat(graph.run(GOAL).isSuccess()).isTrue();
            assertThat(ranOn.get()).isEqualTo("injected-pool");
            // The graph does not own it: a second run must still work.
            assertThat(graph.run(GOAL).isSuccess()).isTrue();
            assertThat(pool.isShutdown()).isFalse();
        }
    }

    @Test
    void maxConcurrencyIsSharedAcrossConcurrentRunsOfTheSameGraph() {
        // A per-run cap would multiply by the number of goals in flight, which defeats the
        // point: the backend being protected does not care how many runs you started.
        AtomicInteger live = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AgentGraph graph = AgentGraph.builder()
                .node("a", input -> {
                    peak.accumulateAndGet(live.incrementAndGet(), Math::max);
                    sleep(Duration.ofMillis(30));
                    live.decrementAndGet();
                    return AgentResult.completed("A", 1);
                })
                .node("b", input -> {
                    peak.accumulateAndGet(live.incrementAndGet(), Math::max);
                    sleep(Duration.ofMillis(30));
                    live.decrementAndGet();
                    return AgentResult.completed("B", 1);
                })
                .maxConcurrency(2)
                .build();

        List<Thread> runners = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> Thread.ofVirtual().start(() -> graph.run(Goal.of("goal " + i))))
                .toList();
        runners.forEach(AgentGraphTest::join);

        assertThat(peak.get()).isLessThanOrEqualTo(2);
    }

    /**
     * Blocks, bounded, until {@code wanted} node bodies are live at once — the window in
     * which bodies that are allowed to overlap will be caught doing so. A lone body waits
     * the whole window and leaves, so the honest reading of a peak of one is "nobody else
     * was ever admitted", not "the timing happened not to line up".
     */
    private static void awaitCompany(AtomicInteger live, int wanted) {
        long deadline = System.nanoTime() + Duration.ofMillis(400).toNanos();
        while (live.get() < wanted && System.nanoTime() < deadline) {
            sleep(Duration.ofMillis(2));
        }
    }

    /** A node that records the highest number of node bodies ever live at once. */
    private static GraphNode peakRecording(AtomicInteger live, AtomicInteger peak) {
        return input -> {
            peak.accumulateAndGet(live.incrementAndGet(), Math::max);
            awaitCompany(live, 2);
            peak.accumulateAndGet(live.get(), Math::max);
            live.decrementAndGet();
            return AgentResult.completed("w", 1);
        };
    }

    @Test
    @Timeout(30)
    void aNodeCanRunItsOwnGraphAgainUnderThePermitItAlreadyHolds() {
        // The recursive decomposition this class's javadoc invites — "instances are
        // immutable and reusable", a node is "just a function". With one permit for the
        // whole graph and no lending, the outer node holds it while the nested node queues
        // for it forever: no result, no error, no log line, and no deadline to end it,
        // which is the worst shape a failure in this class can take.
        AtomicReference<AgentGraph> self = new AtomicReference<>();
        AgentGraph graph = AgentGraph.builder()
                .node("recurse", input -> input.goal().description().equals("sub")
                        ? AgentResult.completed("leaf", 1)
                        : AgentResult.completed("outer("
                                + self.get().run(Goal.of("sub")).output("recurse").orElseThrow()
                                + ")", 1))
                .maxConcurrency(1)
                .build();
        self.set(graph);

        GraphResult result = graph.run(GOAL);

        assertThat(result.stop()).isEqualTo(GraphStop.COMPLETED);
        assertThat(result.output("recurse")).contains("outer(leaf)");
    }

    @Test
    @Timeout(60)
    void aNestedRunTakesTurnsInTheOuterNodesPermitRatherThanRunningFree() {
        // Lending must not be a free pass. If a nested run simply skipped the gate,
        // maxConcurrency(1) would put two node bodies on a rate-limited backend at once and
        // the bound would mean something other than what it says — trading one hazard for
        // the other rather than removing either.
        AtomicInteger live = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicReference<AgentGraph> self = new AtomicReference<>();
        AgentGraph graph = AgentGraph.builder()
                .node("spawn", input -> input.goal().description().equals("sub")
                        ? AgentResult.completed("bottom", 1)
                        : AgentResult.completed(self.get().run(Goal.of("sub")).stop().name(), 1))
                .node("w1", peakRecording(live, peak))
                .node("w2", peakRecording(live, peak))
                .maxConcurrency(1)
                .build();
        self.set(graph);

        GraphResult result = graph.run(GOAL);

        assertThat(result.stop()).isEqualTo(GraphStop.COMPLETED);
        assertThat(result.output("spawn")).contains(GraphStop.COMPLETED.name());
        assertThat(peak.get())
                .as("maxConcurrency(1) still means one node body at a time, nested or not")
                .isEqualTo(1);
    }

    @Test
    void aGraphIsReusableAcrossGoals() {
        AtomicInteger runs = new AtomicInteger();
        AgentGraph graph = AgentGraph.builder()
                .node("a", input -> AgentResult.completed(
                        input.goal().description() + "#" + runs.incrementAndGet(), 1))
                .build();

        assertThat(graph.run(Goal.of("first")).output("a")).contains("first#1");
        assertThat(graph.run(Goal.of("second")).output("a")).contains("second#2");
    }

    @Test
    void theTimeoutStopsTheGraphAndKeepsWhatFinished() {
        AgentGraph graph = AgentGraph.builder()
                .node("quick", says("done"))
                .node("slow", input -> {
                    try {
                        Thread.sleep(Duration.ofSeconds(30));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return AgentResult.completed("too late", 1);
                })
                .node("after", says("never"))
                .edge("slow", "after")
                .timeout(Duration.ofMillis(250))
                .build();

        GraphResult result = graph.run(GOAL);

        assertThat(result.stop()).isEqualTo(GraphStop.TIMED_OUT);
        assertThat(result.isSuccess()).isFalse();
        // Work that finished before the deadline is kept, not discarded.
        assertThat(result.output("quick")).contains("done");
        // "slow" started and was cut short: it spent real time, so it is a failure rather
        // than an absence, and its cause says what happened.
        NodeOutcome slow = result.outcome("slow").orElseThrow();
        assertThat(slow.state()).isEqualTo(NodeState.FAILED);
        assertThat(slow.result().orElseThrow().error()).get().isInstanceOf(TimeoutException.class);
        // "after" never started, so it genuinely did not run.
        assertThat(result.outcome("after").orElseThrow().state()).isEqualTo(NodeState.NOT_RUN);
    }

    @Test
    void aNodeStillRunningAtTheDeadlineIsActuallyCancelled() {
        // Without the cancellation the suite still passes but the abandoned node runs to
        // completion in the background, holding a thread and burning tokens.
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        AgentGraph graph = AgentGraph.builder()
                .node("slow", input -> {
                    started.countDown();
                    try {
                        Thread.sleep(Duration.ofSeconds(30));
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                    }
                    return AgentResult.completed("late", 1);
                })
                .timeout(Duration.ofMillis(200))
                .build();

        graph.run(GOAL);

        await(() -> interrupted.get(), "the cancelled node was never interrupted");
    }

    @Test
    void aCompletionThatLandsAsTheDeadlineExpiresIsNotThrownAway() {
        // The driver evaluates edge conditions itself, so a slow predicate is a
        // deterministic way to arrive at the deadline with a finished node already queued;
        // a GC pause or a large graph does the same thing non-deterministically.
        // root resolves immediately, which sends the driver into the slow predicate; "fast"
        // finishes during that sleep, so by the time the driver looks again the deadline
        // has passed and its completion is sitting in the queue, already earned.
        AgentGraph graph = AgentGraph.builder()
                .node("root", says("R"))
                .node("fast", input -> {
                    sleep(Duration.ofMillis(50));
                    return AgentResult.completed("F", 1);
                })
                .node("gated", says("never"))
                .edge("root", "gated", result -> {
                    sleep(Duration.ofMillis(400));
                    return false;
                })
                .timeout(Duration.ofMillis(250))
                .build();

        GraphResult result = graph.run(GOAL);

        // "fast" finished in microseconds, long inside the deadline. Losing it would make
        // "results from nodes that already finished are kept" false precisely when the
        // graph is under the pressure that makes the promise worth having.
        assertThat(result.output("fast")).contains("F");
    }

    @Test
    void anExecutorShutDownUnderTheRunEndsItRatherThanHanging() {
        // shutdownNow discards queued tasks without ever completing their futures, so a
        // driver that blocked waiting for one would hang forever on an ordinary lifecycle
        // event — a Spring context close, a JVM shutdown hook.
        ExecutorService pool = Executors.newFixedThreadPool(1);
        AgentGraph graph = AgentGraph.builder()
                .node("a", blocksFor(Duration.ofSeconds(10)))
                .node("b", says("B"))
                .node("c", says("C"))
                .executor(pool)
                .build();

        Thread killer = Thread.ofVirtual().start(() -> {
            sleep(Duration.ofMillis(200));
            pool.shutdownNow();
        });

        GraphResult result = graph.run(GOAL);   // must return rather than hang

        assertThat(result.stop()).isIn(GraphStop.ABANDONED, GraphStop.COMPLETED);
        assertThat(result.isSuccess()).isFalse();
        join(killer);
    }

    @Test
    void anExecutorThatRejectsFailsThatNodeRatherThanTheWholeGraph() {
        // A saturated pool is one node's problem. Losing every other node's completed work
        // to a raw RejectedExecutionException is not a proportionate response.
        ExecutorService pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        try {
            AgentGraph graph = AgentGraph.builder()
                    .node("a", blocksFor(Duration.ofMillis(300)))
                    .node("b", says("B"))
                    .node("c", says("C"))
                    .executor(pool)
                    .build();

            GraphResult result = graph.run(GOAL);

            assertThat(result.outcomes()).hasSize(3);
            assertThat(result.failures()).isNotEmpty();
            assertThat(result.failures()).anySatisfy(outcome ->
                    assertThat(outcome.result().orElseThrow().error()).get()
                            .isInstanceOf(RejectedExecutionException.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anInterruptedRunIsReportedRatherThanMistakenForATimeout() {
        // This graph has no timeout at all, so claiming one elapsed would be a fabrication.
        AgentGraph graph = AgentGraph.builder().node("blocker", blocksFor(Duration.ofSeconds(30))).build();
        AtomicReference<GraphResult> result = new AtomicReference<>();
        AtomicBoolean flagStillSet = new AtomicBoolean();

        Thread runner = Thread.ofVirtual().start(() -> {
            result.set(graph.run(GOAL));
            flagStillSet.set(Thread.currentThread().isInterrupted());
        });
        sleep(Duration.ofMillis(200));
        runner.interrupt();
        join(runner);

        assertThat(result.get().stop()).isEqualTo(GraphStop.INTERRUPTED);
        // The interrupt is re-asserted, so the caller's own cancellation still works.
        assertThat(flagStillSet).isTrue();
    }

    // --- validation -----------------------------------------------------------

    @Test
    void aCycleIsRejectedAtBuildTimeRatherThanDeadlockingAtRunTime() {
        AgentGraph.Builder builder = AgentGraph.builder()
                .node("a", says("A")).node("b", says("B")).node("c", says("C"))
                .node("root", says("R"))
                .edge("root", "a")
                .edge("a", "b").edge("b", "c").edge("c", "a");

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle")
                // Naming the nodes involved, since a cycle in a big graph is otherwise a
                // hunt; "root" is reachable and must not be listed.
                .hasMessageContaining("[a, b, c]")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("root"));
    }

    @Test
    void aSelfEdgeIsRejectedImmediately() {
        assertThatThrownBy(() -> AgentGraph.builder().node("a", says("A")).edge("a", "a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot depend on itself");
    }

    @Test
    void anEdgeToAnUnknownNodeIsRejected() {
        assertThatThrownBy(() -> AgentGraph.builder().node("a", says("A")).edge("a", "ghost").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ghost");
        assertThatThrownBy(() -> AgentGraph.builder().node("a", says("A")).edge("ghost", "a").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void duplicateAndEmptyDeclarationsAreRejected() {
        assertThatThrownBy(() -> AgentGraph.builder().node("a", says("A")).node("a", says("B")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> AgentGraph.builder().node(" ", says("A")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blank");
        assertThatThrownBy(() -> AgentGraph.builder().build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least one node");
    }

    @Test
    void invalidBoundsAreRejected() {
        assertThatThrownBy(() -> AgentGraph.builder().maxConcurrency(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentGraph.builder().timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentGraph.builder().timeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- NodeInput / NodeOutcome ---------------------------------------------

    @Test
    void nodeInputDistinguishesSkippedFromFailedUpstream() {
        NodeInput input = new NodeInput("n", GOAL, Map.of(
                "ok", AgentResult.completed("yes", 1),
                "bad", AgentResult.failed(new IllegalStateException("x"), 1)));

        assertThat(input.outputOf("ok")).contains("yes");
        assertThat(input.outputOf("bad")).isEmpty();     // ran, but its text is not an answer
        assertThat(input.outputOf("absent")).isEmpty();  // never ran
        assertThat(input.resultOf("bad")).isPresent();   // ... and this is how you tell
        assertThat(input.resultOf("absent")).isEmpty();
    }

    @Test
    void renderedDependenciesIncludeOnlySuccessfulOnes() {
        NodeInput input = new NodeInput("n", GOAL, new java.util.LinkedHashMap<>(Map.of()) {{
                put("first", AgentResult.completed("alpha", 1));
                put("broken", AgentResult.failed(new IllegalStateException("x"), 1));
                put("second", AgentResult.completed("beta", 1));
            }});

        // Each upstream output is fenced under its own node, so nothing a node writes is
        // read as the operator's words and no node can forge a heading claiming to be
        // another. outsideFences() is what the downstream model would read as ours.
        String rendered = input.renderDependencies();
        assertThat(rendered)
                .isEqualTo(Spotlight.wrap(Source.of("node", "first"), "alpha")
                        + "\n\n" + Spotlight.wrap(Source.of("node", "second"), "beta"));
        // Nothing but the two labels survives outside the fences.
        assertThat(Spotlight.outsideFences(rendered)).isEqualTo("node:first\n\nnode:second");
        assertThat(new NodeInput("n", GOAL, Map.of()).renderDependencies()).isEmpty();
    }

    @Test
    void anOutcomeCannotClaimToHaveRunWithoutAResult() {
        assertThatThrownBy(() -> new NodeOutcome("a", NodeState.COMPLETED, java.util.Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NodeOutcome("a", NodeState.SKIPPED,
                java.util.Optional.of(AgentResult.completed("x", 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
