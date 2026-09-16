package dev.agentkit.core.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.concurrent.TaskContext;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.PendingApproval;
import dev.agentkit.core.tool.ToolInvocation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Work a run starts and collects later, bounded by the run (#328).
 *
 * <p>The three decisions this pins are the ones the issue left open, so each has a test that
 * fails if the decision is reversed rather than a comment asserting it.
 */
@Timeout(30)
class AgentScopeTest {

    private static final AgentRun PARENT = AgentRun.of("supervisor");

    private static Subagent answering(String name, String answer) {
        return Subagent.handling(name, name,
                goal -> AgentResult.completed(answer, 1));
    }

    /**
     * A subagent that will not finish until released, so "outstanding" is observable.
     *
     * <p>{@code await()} with <strong>no timeout</strong>, deliberately. An earlier draft
     * waited ten seconds and then carried on regardless, which made a synchronous
     * {@code start} merely slow instead of wrong — every test still passed. The class-level
     * {@code @Timeout} turns a genuine hang into a failure, which is what a broken
     * {@code start} should be.
     */
    private static Subagent blockedOn(String name, CountDownLatch release) {
        return Subagent.handling(name, name, goal -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentResult.completed("done", 1);
        });
    }

    /**
     * The whole reason the class exists: {@code start} returns while the child is still
     * running.
     *
     * <p><strong>This has to be provable, not merely consistent with the truth.</strong> The
     * first draft asserted {@code outstanding()} contained the handle — which is equally true
     * of a task that has already finished, since "outstanding" means started-and-not-collected.
     * Replacing {@code executor.submit} with an inline {@code FutureTask.run()}, making
     * {@code start} fully synchronous and the executor dead code, left all seven tests
     * passing.
     *
     * <p>So the child now blocks on a latch this test never releases until <em>after</em> the
     * assertions. A synchronous {@code start} cannot reach them at all: it deadlocks, and the
     * class-level timeout fails it.
     */
    @Test
    @DisplayName("start returns while the child is provably still running")
    void startsWithoutBlockingAndCollectsLater() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        Subagent researcher = Subagent.handling("researcher", "researcher", goal -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentResult.completed("done", 1);
        });

        try (AgentScope scope = AgentScope.forRun(PARENT, 0)) {
            String handle = scope.start(researcher, Goal.of("look it up"));

            // Reached only because start did not wait: the child is inside its run on
            // another thread and is not coming out until this test says so. Waiting for
            // `entered` here proves the child is genuinely running concurrently -- and
            // `release` has not been counted down, so a synchronous start could never have
            // returned to reach this line at all.
            entered.await();
            assertThat(scope.outstanding()).containsExactly(handle);
            assertThat(scope.outstandingWork()).containsEntry(handle, "researcher");

            release.countDown();
            assertThat(scope.collect(handle).orElseThrow().result().output()).isEqualTo("done");
            assertThat(scope.outstanding()).isEmpty();
        }
    }

    /**
     * The parent link survives deferred execution, which is the design's load-bearing claim.
     *
     * <p>It looks as though it should not: {@code Tool.boundTo} works because the parent's
     * dispatch is on the stack when a child runs, and here it is not. The link holds because
     * it is minted at {@code start}, on the calling thread.
     */
    @Test
    @DisplayName("a deferred child still names the run that started it")
    void theParentLinkIsMintedAtStartNotAtRun() {
        AtomicInteger sawParent = new AtomicInteger();
        Subagent child = Subagent.handlingUnder("worker", "worker", (goal, run) -> {
            if (run.parent().map(p -> p.id().equals(PARENT.id())).orElse(false)) {
                sawParent.incrementAndGet();
            }
            return AgentResult.completed("ok", 1);
        });

        try (AgentScope scope = AgentScope.forRun(PARENT, 0)) {
            scope.collect(scope.start(child, Goal.of("go")));
        }

        assertThat(sawParent).hasValue(1);
    }

    /**
     * Decision 2: close waits.
     *
     * <p>A run reporting COMPLETED while a child is partway through a write is the audit lie
     * #131, #181 and #323 each removed. Reversing this — closing without joining — fails
     * here.
     */
    @Test
    @DisplayName("close waits for work nobody collected rather than abandoning it")
    void closeWaitsForUncollectedWork() throws Exception {
        AtomicBoolean finished = new AtomicBoolean();
        Subagent slow = Subagent.handling("slow", "slow", goal -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.set(true);
            return AgentResult.completed("done", 1);
        });

        // An INJECTED executor, deliberately. Over one this scope owns, close() would wait
        // anyway -- ExecutorService.close() blocks until termination -- so a test on an
        // owned executor passes whether or not joinAll is there, which is what the first
        // draft of this test did. It released the latch before close() as well, so the
        // child had already finished either way: a control that could not fail, of the
        // shape this repository keeps finding. Over a caller's executor, joinAll is the
        // only thing that waits.
        try (ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentScope scope = AgentScope.forRun(PARENT, caller, TaskContext.NONE, 0);
            scope.start(slow, Goal.of("go"));
            scope.close();

            assertThat(finished)
                    .as("close returned while the child was still running")
                    .isTrue();
        }
    }

    /**
     * Decision 3: a park does not hang the scope.
     *
     * <p>The issue assumed a parked child was a thread to avoid blocking on. It is not — a
     * park <em>ends</em> the child's run — so waiting returns promptly and the approvals
     * arrive as a result to propagate.
     */
    @Test
    @DisplayName("a parked child returns promptly and its approvals are collectable")
    void aParkedChildDoesNotHangTheScope() {
        PendingApproval waiting = new PendingApproval(
                new ToolInvocation("c1", "publish", Map.of("text", "x")),
                ApprovalNeeded.because("a person decides what goes out"), "t-1");
        Subagent parks = Subagent.handling("publisher", "publisher", goal ->
                AgentResult.awaitingApproval("held", 1, TokenUsage.ZERO, List.of(waiting)));

        List<SubagentOutcome> outcomes;
        try (AgentScope scope = AgentScope.forRun(PARENT, 0)) {
            scope.start(parks, Goal.of("publish it"));
            outcomes = scope.joinAll();
        }

        assertThat(outcomes).singleElement()
                .satisfies(o -> assertThat(o.result().stopReason())
                        .isEqualTo(StopReason.AWAITING_APPROVAL));
        assertThat(AgentScope.parked(outcomes))
                .as("the parent puts these on its own result so one person sees one list")
                .containsExactly(waiting);
    }

    /** A subagent that throws becomes an outcome, never an escaping exception. */
    @Test
    @DisplayName("a subagent that throws comes back as a failed outcome")
    void aThrowingSubagentBecomesAnOutcome() {
        Subagent boom = Subagent.handling("boom", "boom", goal -> {
            throw new IllegalStateException("fell over");
        });

        try (AgentScope scope = AgentScope.forRun(PARENT, 0)) {
            SubagentOutcome outcome = scope.collect(scope.start(boom, Goal.of("go")))
                    .orElseThrow();
            assertThat(outcome.result().stopReason()).isEqualTo(StopReason.ERROR);
        }
    }

    /** Collecting an unknown or already-collected handle is empty, not an exception. */
    @Test
    @DisplayName("an unknown handle is empty rather than a throw")
    void anUnknownHandleIsEmpty() {
        try (AgentScope scope = AgentScope.forRun(PARENT, 0)) {
            String handle = scope.start(answering("a", "one"), Goal.of("go"));
            assertThat(scope.collect(handle)).isPresent();
            assertThat(scope.collect(handle)).as("collected twice").isEmpty();
            assertThat(scope.collect("task-999")).as("never started").isEmpty();
        }
    }

    /**
     * Several run <em>at once</em>, and all are joined.
     *
     * <p>A {@link CyclicBarrier} rather than a count: {@code hasSize(8)} passes just as well
     * when the eight ran one after another, so it did not test the word "together" in its own
     * name. Eight tasks that must all arrive before any may leave cannot complete unless
     * eight are genuinely in flight.
     */
    @Test
    @DisplayName("many tasks run together and joinAll returns all of them")
    void manyTasksRunTogether() {
        CyclicBarrier allEight = new CyclicBarrier(8);
        try (AgentScope scope = AgentScope.forRun(PARENT, 0)) {
            for (int i = 0; i < 8; i++) {
                scope.start(Subagent.handling("w" + i, "w" + i, goal -> {
                    try {
                        allEight.await();
                    } catch (Exception e) {
                        throw new IllegalStateException("never all in flight at once", e);
                    }
                    return AgentResult.completed("done", 1);
                }), Goal.of("go " + i));
            }
            assertThat(scope.joinAll()).hasSize(8)
                    .allSatisfy(o -> assertThat(o.result().stopReason())
                            .isEqualTo(StopReason.COMPLETED));
            assertThat(scope.outstanding()).isEmpty();
        }
    }

    /**
     * A bounded scope does not deadlock when a running task starts and collects another.
     *
     * <p>{@link AgentScope#joinAll}'s own comment describes this case — "a collected task may
     * start another before this loop ends" — and with {@code maxConcurrent > 0} it hung: the
     * permit was held across {@code Subagent.handle}, so the child waited for a permit its
     * own parent was sitting on, and {@code acquireUninterruptibly} meant nothing could break
     * it out. Nothing exercised the semaphore at all; every other test here passes 0.
     */
    @Test
    @DisplayName("a bounded scope survives a task that starts and collects another")
    void aTaskMayStartAnotherUnderABound() {
        Subagent leaf = answering("leaf", "inner");
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentScope scope = AgentScope.forRun(PARENT, pool, TaskContext.NONE, 1);
            Subagent outer = Subagent.handling("outer", "outer", goal -> {
                String inner = scope.start(leaf, Goal.of("sub"));
                return AgentResult.completed(
                        scope.collect(inner).orElseThrow().result().output(), 1);
            });

            String handle = scope.start(outer, Goal.of("go"));
            assertThat(scope.collect(handle).orElseThrow().result().output())
                    .isEqualTo("inner");
            scope.close();
        }
    }

    /** The bound is real: with one permit, two tasks never overlap. */
    @Test
    @DisplayName("maxConcurrent bounds how many run at once")
    void theBoundIsEnforced() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        try (AgentScope scope = AgentScope.forRun(PARENT, 1)) {
            for (int i = 0; i < 6; i++) {
                scope.start(Subagent.handling("w" + i, "w" + i, goal -> {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    inFlight.decrementAndGet();
                    return AgentResult.completed("done", 1);
                }), Goal.of("go " + i));
            }
            assertThat(scope.joinAll()).hasSize(6);
        }
        assertThat(peak).as("more than one ran at once under a bound of one").hasValue(1);
    }

    /**
     * An interrupt does not fabricate outcomes for children that are still running.
     *
     * <p>The first draft caught {@code InterruptedException}, restored the flag, and returned
     * a synthesized {@code AgentResult.failed}. Because the flag was restored, every
     * subsequent {@code get()} in {@code joinAll} threw at once: one interrupt reported ERROR
     * for every child, all of which then finished normally. That is the audit lie decision 2
     * exists to prevent, and it is worse than a throw — {@code awaiting()} would report no
     * pending approval for a child that did in fact park.
     */
    @Test
    @DisplayName("an interrupt does not report outcomes children never reached")
    void anInterruptDoesNotFabricateOutcomes() throws Exception {
        int children = 4;
        AtomicInteger finished = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentScope scope = AgentScope.forRun(PARENT, pool, TaskContext.NONE, 0);
            for (int i = 0; i < children; i++) {
                scope.start(Subagent.handling("w" + i, "w" + i, goal -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    finished.incrementAndGet();
                    return AgentResult.completed("done", 1);
                }), Goal.of("go " + i));
            }

            AtomicBoolean flagSeen = new AtomicBoolean();
            List<SubagentOutcome> outcomes = new java.util.ArrayList<>();
            Thread joiner = new Thread(() -> {
                outcomes.addAll(scope.joinAll());
                flagSeen.set(Thread.currentThread().isInterrupted());
            });
            joiner.start();
            Thread.sleep(50);
            joiner.interrupt();
            joiner.join();

            assertThat(outcomes).hasSize(children)
                    .allSatisfy(o -> assertThat(o.result().stopReason())
                            .as("an outcome the child never reached")
                            .isEqualTo(StopReason.COMPLETED));
            assertThat(finished).as("joinAll returned before the children ran")
                    .hasValue(children);
            assertThat(flagSeen).as("the interrupt was swallowed rather than re-asserted")
                    .isTrue();
        }
    }

    /**
     * A closed scope refuses new work rather than running it with nobody left to join it.
     *
     * <p>Over an injected executor the unguarded version ran the child <em>after</em> close
     * returned, leaving it outstanding forever — "work outlives the call, never the run",
     * broken outright. Over an owned one it threw {@code RejectedExecutionException}, which
     * the agent loop reads as {@code THREW}: "entered and may have landed half a side
     * effect", for a call that started nothing.
     */
    @Test
    @DisplayName("a closed scope refuses to start more work")
    void startAfterCloseIsRefused() {
        AtomicBoolean ran = new AtomicBoolean();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentScope scope = AgentScope.forRun(PARENT, pool, TaskContext.NONE, 0);
            scope.close();

            assertThatThrownBy(() -> scope.start(
                    Subagent.handling("late", "late", goal -> {
                        ran.set(true);
                        return AgentResult.completed("done", 1);
                    }), Goal.of("go")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
            assertThat(ran).as("the child ran after the scope closed").isFalse();
            assertThat(scope.outstanding()).isEmpty();
        }
    }

    /** {@code running_tasks} renders straight from this, so the order has to be the start order. */
    @Test
    @DisplayName("outstandingWork keeps start order")
    void outstandingWorkKeepsStartOrder() {
        CountDownLatch release = new CountDownLatch(1);
        try (AgentScope scope = AgentScope.forRun(PARENT, 0)) {
            List<String> handles = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                handles.add(scope.start(blockedOn("w" + i, release), Goal.of("go " + i)));
            }
            // Map.copyOf would pass this only by luck: its iteration order is unspecified and
            // salted per JVM, and it disagreed with outstanding() on the same scope at the
            // same moment.
            assertThat(scope.outstandingWork().keySet())
                    .containsExactlyElementsOf(handles)
                    .containsExactlyElementsOf(scope.outstanding());
            release.countDown();
        }
    }

    /**
     * Exactly one caller wins a handle, even when two await it at once.
     *
     * <p>Reordering {@code collect} to leave the handle visible during the wait — so a
     * concurrent {@code close} could still see a running task — made the claim itself
     * non-atomic: two callers both read the entry, both blocked on the same future, and both
     * returned the outcome. The second consequence is the worse one: {@code recording} ran
     * twice, so one park became two and one person would be asked twice for one decision.
     */
    @Test
    @DisplayName("two threads collecting one handle: one wins, and the park is recorded once")
    void oneHandleIsWonByExactlyOneCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        PendingApproval waiting = new PendingApproval(
                new ToolInvocation("c1", "publish", Map.of("text", "x")),
                ApprovalNeeded.because("a person decides what goes out"), "t-1");
        Subagent parks = Subagent.handling("publisher", "publisher", goal -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentResult.awaitingApproval("held", 1, TokenUsage.ZERO, List.of(waiting));
        });

        AgentScope scope = AgentScope.forRun(PARENT, 0);
        String handle = scope.start(parks, Goal.of("publish it"));

        // Both threads are parked on the same handle before the child can finish, so the
        // claim is genuinely contended rather than resolved by timing.
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch bothIn = new CountDownLatch(2);
        Runnable collector = () -> {
            bothIn.countDown();
            if (scope.collect(handle).isPresent()) {
                winners.incrementAndGet();
            }
        };
        Thread one = new Thread(collector);
        Thread two = new Thread(collector);
        one.start();
        two.start();
        bothIn.await();
        release.countDown();
        one.join();
        two.join();
        scope.close();

        assertThat(winners).as("both callers were handed the same outcome").hasValue(1);
        assertThat(scope.awaiting())
                .as("one decision, recorded once — twice would page a person twice")
                .containsExactly(waiting);
    }

    /**
     * A task claimed by another thread still holds {@code close} open.
     *
     * <p>The other half of the claim: taking the handle out of the map atomically is what
     * makes one winner, but a claimed task is still running, and a {@code close} that could
     * not see it would return having joined only what was left behind.
     */
    @Test
    @DisplayName("close waits for a task another thread is already collecting")
    void closeWaitsForATaskSomebodyElseClaimed() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch claimed = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        Subagent slow = Subagent.handling("slow", "slow", goal -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.set(true);
            return AgentResult.completed("done", 1);
        });

        try (ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentScope scope = AgentScope.forRun(PARENT, caller, TaskContext.NONE, 0);
            String handle = scope.start(slow, Goal.of("go"));

            Thread collector = new Thread(() -> {
                claimed.countDown();
                scope.collect(handle);
            });
            collector.start();
            claimed.await();
            // Give the collector time to take the handle out of the map before close looks.
            while (!scope.outstanding().isEmpty()) {
                Thread.onSpinWait();
            }

            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                release.countDown();
            });
            releaser.start();
            scope.close();

            assertThat(finished)
                    .as("close returned while a claimed task was still running")
                    .isTrue();
            collector.join();
            releaser.join();
        }
    }

    /**
     * The refusal is not a window.
     *
     * <p>Checking {@code closed} and submitting as two steps let {@code close()} set the
     * flag, join an empty map and return in between — after which the child ran anyway, left
     * outstanding forever on an injected executor. The two are one step under the same
     * monitor now.
     *
     * <p><strong>Held open deliberately rather than raced.</strong> A loop of two hundred
     * unsynchronised attempts was the first version of this test and it caught nothing: the
     * window is a few instructions wide, so it never lost the race. A {@link TaskContext}
     * whose {@code wrap} blocks pins {@code start} inside the window instead, which makes
     * both outcomes deterministic — the unguarded version fails every run, the guarded one
     * refuses every run.
     */
    @Test
    @DisplayName("a start held inside the close window is refused, not silently run")
    void startRacingCloseIsNeverLost() throws Exception {
        CountDownLatch insideWrap = new CountDownLatch(1);
        CountDownLatch letWrapFinish = new CountDownLatch(1);
        TaskContext blocking = new TaskContext() {
            @Override
            public <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> task) {
                insideWrap.countDown();
                try {
                    letWrapFinish.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return task;
            }
        };

        AtomicBoolean ran = new AtomicBoolean();
        AtomicBoolean refused = new AtomicBoolean();
        Subagent child = Subagent.handling("late", "late", goal -> {
            ran.set(true);
            return AgentResult.completed("done", 1);
        });

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentScope scope = AgentScope.forRun(PARENT, pool, blocking, 0);
            Thread starter = new Thread(() -> {
                try {
                    scope.start(child, Goal.of("go"));
                } catch (IllegalStateException expected) {
                    refused.set(true);
                }
            });
            starter.start();

            // start() is now suspended inside wrap, past the point an unguarded check would
            // have cleared it and before anything is in the map.
            insideWrap.await();
            Thread closer = new Thread(scope::close);
            closer.start();
            // close() sets the flag and joins; with nothing yet in the map it returns at
            // once. Let start() carry on into that returned-from state.
            closer.join();
            letWrapFinish.countDown();
            starter.join();

            assertThat(refused)
                    .as("start got past a closed scope and submitted anyway")
                    .isTrue();
            assertThat(ran).as("the child ran after close() had already returned").isFalse();
            assertThat(scope.outstanding())
                    .as("left outstanding forever on a scope nobody will join again")
                    .isEmpty();
        }
    }

    /**
     * A park survives whoever collected it.
     *
     * <p>{@code close()} discards the outcomes it joins and {@code collect_task} renders one
     * and keeps none, so a park held only in a returned outcome is a park that is routinely
     * dropped. The scope accumulates them instead.
     */
    @Test
    @DisplayName("a park is reachable even though nobody kept the outcome")
    void parksSurviveTheOutcomeBeingDiscarded() {
        PendingApproval waiting = new PendingApproval(
                new ToolInvocation("c1", "publish", Map.of("text", "x")),
                ApprovalNeeded.because("a person decides what goes out"), "t-1");
        Subagent parks = Subagent.handling("publisher", "publisher", goal ->
                AgentResult.awaitingApproval("held", 1, TokenUsage.ZERO, List.of(waiting)));

        AgentScope scope = AgentScope.forRun(PARENT, 0);
        scope.start(parks, Goal.of("publish it"));
        scope.close();

        assertThat(scope.awaiting())
                .as("close joined the park and dropped it on the floor")
                .containsExactly(waiting);
    }
}
