package dev.agentkit.core.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.util.Cut;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SupervisorTest {

    /** A subagent that always produces {@code output} with the given usage. */
    private static Subagent textSubagent(String name, String output, TokenUsage usage) {
        return Subagent.of(name, name, () -> new Agent(
                new FakeLlmClient(FakeLlmClient.textWithUsage(output, usage)),
                new SimpleToolRegistry(), AgentConfig.builder("m").build()));
    }

    private static Subagent textSubagent(String name, String output) {
        return textSubagent(name, output, TokenUsage.ZERO);
    }

    @Test
    void fanOutCollectsOutcomesInTaskOrderAndSynthesizes() {
        SubagentRoster roster = SubagentRoster.of(
                textSubagent("weather", "It is sunny."),
                textSubagent("news", "Markets are up."));
        Supervisor supervisor = Supervisor.of(roster);

        SupervisionResult result = supervisor.fanOut(Goal.of("brief me"), List.of(
                DelegatedTask.of("weather", "weather?"),
                DelegatedTask.of("news", "news?")));

        assertThat(result.outcomes()).extracting(SubagentOutcome::subagentName)
                .containsExactly("weather", "news");
        assertThat(result.allSucceeded()).isTrue();
        // Fenced and attributed, not bare (#118). The headings still make it readable; the
        // fence is what stops one subagent writing another's heading, which is exactly what
        // buildPrompt's javadoc has always said about the leg that did fence.
        assertThat(result.output()).contains("## weather").contains("It is sunny.")
                .contains("## news").contains("Markets are up.")
                .contains("source=\"subagent:weather\"").contains("source=\"subagent:news\"");
        // Pinned exactly, not "does not contain the outputs". Absence is also satisfied by
        // an empty string, so the loose form passed for a synthesizer that returned nothing
        // at all; what the caller reads as ours has to be the whole assertion.
        assertThat(dev.agentkit.core.prompt.Spotlight.outsideFences(result.output()))
                .as("a subagent's words reached the caller outside the fence")
                .isEqualTo("## weather\nsubagent:weather\n\n## news\nsubagent:news");
    }

    @Test
    void fanOutAggregatesStepsAndUsage() {
        SubagentRoster roster = SubagentRoster.of(
                textSubagent("a", "one", new TokenUsage(10, 5)),
                textSubagent("b", "two", new TokenUsage(3, 2)));
        Supervisor supervisor = Supervisor.of(roster);

        SupervisionResult result = supervisor.fanOut(Goal.of("g"), List.of(
                DelegatedTask.of("a", "x"), DelegatedTask.of("b", "y")));

        assertThat(result.totalSteps()).isEqualTo(2); // one model turn each
        assertThat(result.totalUsage()).isEqualTo(new TokenUsage(13, 7));
    }

    @Test
    void aFailingSubagentDoesNotAbortTheOthers() {
        Subagent refuser = Subagent.of("refuser", "refuses", () -> new Agent(
                new FakeLlmClient(FakeLlmClient.refusal("no")),
                new SimpleToolRegistry(), AgentConfig.builder("m").build()));
        SubagentRoster roster = SubagentRoster.of(textSubagent("ok", "done"), refuser);
        Supervisor supervisor = Supervisor.of(roster);

        SupervisionResult result = supervisor.fanOut(Goal.of("g"), List.of(
                DelegatedTask.of("ok", "x"), DelegatedTask.of("refuser", "y")));

        assertThat(result.allSucceeded()).isFalse();
        assertThat(result.failures()).extracting(SubagentOutcome::subagentName).containsExactly("refuser");
        // The successful outcome is still present and synthesized, and the failure is
        // annotated with its stop reason in the concatenated output. Pinned exactly: the
        // subagent text lives inside a fence, so "contains(\"done\")" was satisfied by the
        // fenced body and said nothing about where the heading sat.
        assertThat(result.output()).contains("done");
        assertThat(dev.agentkit.core.prompt.Spotlight.outsideFences(result.output()))
                .isEqualTo("## ok\nsubagent:ok\n\n## refuser (REFUSED)\nsubagent:refuser");
    }

    @Test
    void aFailureWithNothingToShowRendersTheReasonRatherThanAnEmptyFence() {
        // AgentResult.failed hardcodes output to "", so this outcome has nothing to fence.
        // Fencing it anyway spent three lines of marker on an empty body while error() —
        // the one thing that says why — was dropped. The exception carries no message
        // either, the common shape, so the type is all that is left to say.
        Subagent broken = Subagent.of("broken", "throws", () -> {
            throw new IllegalStateException();
        });
        SupervisionResult result = Supervisor.of(SubagentRoster.of(broken))
                .fanOut(Goal.of("g"), List.of(DelegatedTask.of("broken", "x")));

        assertThat(result.allSucceeded()).isFalse();
        assertThat(result.output())
                .contains("## broken (ERROR)")
                .contains("java.lang.IllegalStateException");
        // The reason is a throwable's text, so it is fenced like any other subagent word:
        // only the heading is ours.
        assertThat(dev.agentkit.core.prompt.Spotlight.outsideFences(result.output()))
                .isEqualTo("## broken (ERROR)\nsubagent:broken");
    }

    @Test
    void unknownSubagentIsRejectedBeforeRunning() {
        Supervisor supervisor = Supervisor.of(SubagentRoster.of(textSubagent("known", "x")));
        assertThatThrownBy(() -> supervisor.fanOut(Goal.of("g"),
                List.of(DelegatedTask.of("ghost", "y"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void emptyTaskListSynthesizesEmpty() {
        Supervisor supervisor = Supervisor.of(SubagentRoster.of(textSubagent("a", "x")));
        SupervisionResult result = supervisor.fanOut(Goal.of("g"), List.of());
        assertThat(result.outcomes()).isEmpty();
        assertThat(result.output()).isEmpty();
        assertThat(result.totalSteps()).isZero();
    }

    @Test
    void llmSynthesizerReconcilesOutputs() {
        FakeLlmClient synthLlm = new FakeLlmClient(FakeLlmClient.text("Combined brief."));
        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(
                        textSubagent("a", "part a"), textSubagent("b", "part b")))
                .synthesizer(Synthesizers.llm(synthLlm, "m"))
                .build();

        SupervisionResult result = supervisor.fanOut(Goal.of("brief"), List.of(
                DelegatedTask.of("a", "x"), DelegatedTask.of("b", "y")));

        assertThat(result.output()).isEqualTo("Combined brief.");
        // The synthesizer saw both parts and the original goal.
        String prompt = synthLlm.received().get(0).messages().get(0).text();
        assertThat(prompt).contains("part a").contains("part b").contains("brief");
    }

    @Test
    void llmSynthesizerFallsBackToConcatenationOnModelFailure() {
        // An empty FakeLlmClient throws LlmException on generate → fallback.
        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(
                        textSubagent("a", "part a"), textSubagent("b", "part b")))
                .synthesizer(Synthesizers.llm(new FakeLlmClient(), "m"))
                .build();

        SupervisionResult result = supervisor.fanOut(Goal.of("brief"), List.of(
                DelegatedTask.of("a", "x"), DelegatedTask.of("b", "y")));

        // The fallback is the path that mattered most: it is reached on exactly the error
        // an oversized prompt can itself provoke, and it used to hand back the same content
        // bare — so the fenced leg degraded to a bare one under pressure.
        //
        // Pinned exactly rather than asserted absent. "The outputs are not outside a fence"
        // is also true of an empty string, so a llm() that swallowed the exception and
        // returned "" — the fallback deleted outright — passed a test named for the
        // fallback. Six mutants survived the loose form; none survives this one.
        assertThat(dev.agentkit.core.prompt.Spotlight.outsideFences(result.output()))
                .as("the fallback renders both outcomes, fenced and attributed")
                .isEqualTo("## a\nsubagent:a\n\n## b\nsubagent:b");
    }

    @Test
    void aggregatedUsageExcludesTheSynthesisCall() {
        // Synthesis model call has non-zero usage; it must NOT be counted.
        FakeLlmClient synthLlm = new FakeLlmClient(
                FakeLlmClient.textWithUsage("done", new TokenUsage(100, 100)));
        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(
                        textSubagent("a", "x", new TokenUsage(4, 1))))
                .synthesizer(Synthesizers.llm(synthLlm, "m"))
                .build();

        SupervisionResult result = supervisor.fanOut(Goal.of("g"),
                List.of(DelegatedTask.of("a", "x")));

        assertThat(result.totalUsage()).isEqualTo(new TokenUsage(4, 1)); // subagent only
    }

    @Test
    @Timeout(10)
    void maxConcurrencyLimitsSimultaneousSubagents() {
        int limit = 2;
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);

        // Each subagent records the peak concurrency it observes, then blocks until
        // released so overlap is forced if the limit is not honoured. The accounting
        // lives in the factory, which runs on the subagent's own thread.
        Subagent counting = Subagent.of("probe", "probe", () -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return new Agent(new FakeLlmClient(FakeLlmClient.text("x")),
                    new SimpleToolRegistry(), AgentConfig.builder("m").build());
        });

        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(counting))
                .maxConcurrency(limit).build();

        // Release the probes shortly after fan-out begins on another thread.
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        releaser.start();

        supervisor.fanOut(Goal.of("g"), List.of(
                DelegatedTask.of("probe", "1"), DelegatedTask.of("probe", "2"),
                DelegatedTask.of("probe", "3"), DelegatedTask.of("probe", "4")));

        assertThat(peak.get()).isLessThanOrEqualTo(limit);
    }

    /**
     * The fan-out deadline this test uses, and why it is not the 200ms it used to be (#255).
     *
     * <p>The test failed once on a machine carrying several concurrent Maven reactors, with
     * the output not captured. Reproduced deliberately: sixteen busy-loop threads on four
     * cores, one cold JVM per attempt, running this method's body as it then stood —
     * <strong>34 failures in 40 attempts</strong>, always the same assertion, the one saying
     * the <em>fast</em> subagent had not been stranded. The slow one was correctly marked
     * failed in all 40, so the timeout never fired late; what lost was the sibling.
     *
     * <p>The cause was not the deadline value on its own. Instrumented, the fast subagent's
     * own work — building an {@code Agent} over a {@code FakeLlmClient} and running its loop
     * for the first time in a cold JVM — finished at 49-55ms idle and at 172-541ms under that
     * load, against a 200ms deadline shared by the whole fan-out. The control was measuring
     * class loading and JIT on a contended box, which is a property of the machine, and
     * calling the answer {@code Supervisor}'s.
     *
     * <p>So two things changed, in #203's order: the bound is expressed in work done rather
     * than in wall-clock, and what remains of the clock is paid for in duration rather than
     * in coverage. {@code await} now reads a subagent whose work is <em>finished</em>
     * whatever the clock says — see {@link #workAlreadyFinishedIsReadAfterTheDeadline}, which
     * pins that with no clock in it at all — and the sibling here does work that is a record
     * construction rather than an agent run. Measured on the same rig:
     *
     * <pre>
     * sibling's work finished at   idle        under 16 burners on 4 cores   deadline
     * agent run (before)           49-55ms     172-541ms                     200ms
     * record construction (now)    24-26ms     75-292ms                      2,000ms
     * </pre>
     *
     * <p>The margin over the worst reading under load goes from 0.4x — that is, a deficit,
     * which is why it failed — to about 7x, and the same 40 loaded attempts that failed 34
     * times pass 40 of 40. The slow subagent still has to outlive the deadline for there to
     * be a timeout to observe, so the fan-out still costs {@code TIMEOUT}; that cost is
     * duration, and {@link Timeout} is what bounds it. That is the trade #203 names:
     * contention buys seconds, not a verdict.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    @Timeout(60)
    void timeoutMarksSlowSubagentAsFailedWithoutStrandingOthers() {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch fastFinished = new CountDownLatch(1);
        // Not textSubagent: an agent run is the incidental part of "a sibling that finished",
        // and it was the part the clock was actually measuring. This is the property.
        Subagent fast = Subagent.handling("fast", "fast", goal -> {
            AgentResult done = AgentResult.completed("quick", 1);
            fastFinished.countDown();
            return done;
        });
        Subagent slow = Subagent.of("slow", "slow", () -> {
            try {
                blocked.await(); // never released within the deadline
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Agent(new FakeLlmClient(FakeLlmClient.text("late")),
                    new SimpleToolRegistry(), AgentConfig.builder("m").build());
        });
        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(fast, slow))
                .timeout(TIMEOUT).build();

        // Captured around the fan-out, because the WARN is the only thing that tells an
        // operator which subagent the deadline caught -- the outcome says a subagent failed
        // and the log says which one and why -- and it is deletable without any assertion
        // below noticing. slf4j-simple resolves System.err per write, which is what makes
        // this work at all.
        java.io.PrintStream stderr = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        SupervisionResult result;
        String log;
        try {
            System.setErr(new java.io.PrintStream(captured, true,
                    java.nio.charset.StandardCharsets.UTF_8));
            result = supervisor.fanOut(Goal.of("g"), List.of(
                    DelegatedTask.of("fast", "x"), DelegatedTask.of("slow", "y")));
        } finally {
            System.setErr(stderr);
            log = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        blocked.countDown(); // let the cancelled thread unwind
        assertThat(result.outcomes()).extracting(SubagentOutcome::subagentName)
                .containsExactly("fast", "slow");
        // Described, because the first occurrence of this failing was diagnosed from nothing
        // and cost a reproduction run to place. Whether the sibling's handler ran at all
        // separates "the deadline swept up finished work" from "the machine never scheduled
        // it", which are different problems with different fixes.
        assertThat(result.outcomes().get(0).succeeded())
                .as("the fast sibling was stranded; its handler finished=%s, outcome=%s",
                        fastFinished.getCount() == 0, result.outcomes().get(0).result())
                .isTrue();
        assertThat(result.outcomes().get(1).succeeded())
                .as("the slow subagent was not marked failed; outcome=%s",
                        result.outcomes().get(1).result())
                .isFalse();
        assertThat(log).as("nothing told an operator which subagent the deadline caught")
                .contains("WARN").contains("Subagent 'slow' timed out after PT2S");
    }

    /**
     * An {@link ExecutorService} that runs every submission on the calling thread, so a task
     * is finished before {@code submit} returns.
     *
     * <p>That is the whole point of it here: it turns "the subagent's work is done" from
     * something the machine decides into something the construction guarantees, which is
     * what lets the test below contain no timing assumption at all.
     */
    private static final class InlineExecutor
            extends java.util.concurrent.AbstractExecutorService {
        /** Submissions at or after this index come back already cancelled, having never run. */
        private final int cancelFrom;
        private int submitted;
        private volatile boolean shutdown;

        InlineExecutor() {
            this(Integer.MAX_VALUE);
        }

        InlineExecutor(int cancelFrom) {
            this.cancelFrom = cancelFrom;
        }

        @Override public <T> java.util.concurrent.Future<T> submit(
                java.util.concurrent.Callable<T> task) {
            java.util.concurrent.FutureTask<T> future = new java.util.concurrent.FutureTask<>(task);
            if (submitted++ >= cancelFrom) {
                future.cancel(false);
            } else {
                future.run();
            }
            return future;
        }

        @Override public void execute(Runnable command) {
            command.run();
        }

        @Override public void shutdown() {
            shutdown = true;
        }

        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override public boolean isShutdown() {
            return shutdown;
        }

        @Override public boolean isTerminated() {
            return shutdown;
        }

        @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return shutdown;
        }
    }

    @Test
    @Timeout(30)
    void workAlreadyFinishedIsReadAfterTheDeadline() {
        // #255's fix, with the clock taken out of the question. The deadline is what
        // Supervisor cancels a subagent *still running* by — its own javadoc says so — and
        // it used to be what Supervisor threw finished work away by too: `await` tested the
        // clock before it tested the future, so a collector thread descheduled past the
        // deadline reported completed subagents as timed out. Under six concurrent reactors
        // that is not exotic; it is where the 34-in-40 reproduction above spent most of its
        // failures.
        //
        // Run inline, so both subagents have provably finished before a single future is
        // read, and the first one deliberately overruns the deadline so that every await
        // afterwards is past it. Nothing here depends on how fast the machine is: more
        // contention makes the deadline more elapsed, which is the direction that used to
        // break it.
        Duration deadline = Duration.ofMillis(50);
        Subagent slowButFinishing = Subagent.handling("first", "first", goal -> {
            try {
                Thread.sleep(deadline.toMillis() * 4);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentResult.completed("done anyway", 1);
        });
        Subagent second = Subagent.handling("second", "second",
                goal -> AgentResult.completed("also done", 1));

        InlineExecutor inline = new InlineExecutor();
        SupervisionResult result = Supervisor
                .builder(SubagentRoster.of(slowButFinishing, second))
                .executor(inline).timeout(deadline).build()
                .fanOut(Goal.of("g"), List.of(
                        DelegatedTask.of("first", "x"), DelegatedTask.of("second", "y")));

        assertThat(result.outcomes().get(0).succeeded())
                .as("finished work was discarded because the deadline had passed: %s",
                        result.outcomes().get(0).result())
                .isTrue();
        assertThat(result.outcomes().get(0).result().output()).isEqualTo("done anyway");
        assertThat(result.outcomes().get(1).succeeded())
                .as("finished work was discarded because the deadline had passed: %s",
                        result.outcomes().get(1).result())
                .isTrue();
        assertThat(result.outcomes().get(1).result().output()).isEqualTo("also done");
    }

    @Test
    @Timeout(30)
    void aSubagentWhoseFutureWasCancelledIsStillAFailedOutcome() {
        // The other half of the same line, and the reason it reads
        // `isDone() && !isCancelled()` rather than `isDone()`. A cancelled future is done,
        // and reading one throws CancellationException — which would leave `fanOut` by a
        // door it does not have, taking every sibling's outcome with it. A caller-supplied
        // executor shut down mid-fan-out is how a future gets cancelled by somebody other
        // than this loop, and the outcome for it is the one it always was: a failed,
        // timed-out subagent.
        Duration deadline = Duration.ofMillis(50);
        Subagent first = Subagent.handling("first", "first", goal -> {
            try {
                Thread.sleep(deadline.toMillis() * 4);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentResult.completed("done anyway", 1);
        });
        Subagent second = Subagent.handling("second", "second",
                goal -> AgentResult.completed("never ran", 1));

        SupervisionResult result = Supervisor.builder(SubagentRoster.of(first, second))
                .executor(new InlineExecutor(1)).timeout(deadline).build()
                .fanOut(Goal.of("g"), List.of(
                        DelegatedTask.of("first", "x"), DelegatedTask.of("second", "y")));

        assertThat(result.outcomes().get(0).succeeded()).isTrue();
        assertThat(result.outcomes().get(1).succeeded()).isFalse();
        assertThat(result.outcomes().get(1).result().error())
                .get().isInstanceOf(java.util.concurrent.TimeoutException.class);
    }

    // ---- #135: an Error is not a RuntimeException ------------------------------------

    /** An {@code Error} a test can throw without breaking the harness the way a real one would. */
    private static final class TestError extends Error {
        TestError(String message) {
            super(message);
        }
    }

    @Test
    @Timeout(30)
    void anErrorFromOneSubagentDoesNotDiscardTheOthersAndIsNotLaundered() {
        // #135's second site. An Error from subagent.handle was not caught by runOne, so it
        // surfaced in await as an ExecutionException and left as
        // IllegalStateException("Subagent execution failed unexpectedly"). Two harms in one
        // line: the sibling's completed answer went out with it, because await abandons the
        // remaining futures on the way out; and the Error arrived at the caller as a
        // RuntimeException, so Agent.runTool's Error branch (#241) — the one that ends a run
        // because the worker's own invariant broke — never saw one, and a JVM invariant
        // violation was fed to the model as an ordinary handled tool failure.
        //
        // AgentGraph.runNode has caught Throwable for exactly this since it was written.
        TestError thrown = new TestError("the JVM had opinions");
        SubagentRoster roster = SubagentRoster.of(
                textSubagent("healthy", "I finished."),
                Subagent.handling("broken", "broken", goal -> {
                    throw thrown;
                }));

        SupervisionResult result = Supervisor.of(roster).fanOut(Goal.of("g"), List.of(
                DelegatedTask.of("healthy", "x"), DelegatedTask.of("broken", "y")));

        // The consequence, not the catch: the sibling's answer survived and reached the
        // synthesizer. Asserting only that something was caught would pass for a catch that
        // swallowed the whole fan-out.
        assertThat(result.outcomes()).extracting(SubagentOutcome::subagentName)
                .containsExactly("healthy", "broken");
        assertThat(result.outcomes().get(0).succeeded()).isTrue();
        assertThat(result.outcomes().get(0).result().output()).isEqualTo("I finished.");
        assertThat(result.output()).contains("I finished.");

        // And the Error itself is on the outcome, not a RuntimeException standing in for it.
        assertThat(result.outcomes().get(1).succeeded()).isFalse();
        assertThat(result.outcomes().get(1).result().error())
                .as("the Error was laundered rather than carried")
                .get().isSameAs(thrown);
    }

    @Test
    @Timeout(30)
    void aSubagentThatThrowsAnErrorIsReportedToTheOperator() {
        // The log line is the only thing that tells an operator a subagent broke rather than
        // simply failed, and it is deletable without any assertion above noticing. Captured
        // off System.err, which is what slf4j-simple resolves per write.
        java.io.PrintStream stderr = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        String log;
        try {
            System.setErr(new java.io.PrintStream(captured, true,
                    java.nio.charset.StandardCharsets.UTF_8));
            Supervisor.of(SubagentRoster.of(Subagent.handling("broken", "broken", goal -> {
                throw new TestError("the JVM had opinions");
            }))).fanOut(Goal.of("g"), List.of(DelegatedTask.of("broken", "y")));
        } finally {
            System.setErr(stderr);
            log = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        assertThat(log).contains("WARN")
                .contains("Subagent 'broken' threw during delegation")
                .contains("the JVM had opinions");
    }

    @Test
    @Timeout(30)
    void anErrorAroundTheSubagentReachesTheCallerAsAnError() {
        // The same laundering one layer out. runOne now catches Throwable, so the only way
        // to reach await's ExecutionException branch is for something wrapping the callable
        // to throw when it is called — which is what a TaskContext does. It is a seam a
        // deployment writes, and TaskContext's own javadoc warns that an implementation that
        // throws "turns a wrapper into a source of errors".
        //
        // The fan-out ends either way; the assertion is about the type it ends with, because
        // that is what a caller with an Error branch reads.
        TestError thrown = new TestError("the wrapper had opinions");
        Supervisor supervisor = Supervisor
                .builder(SubagentRoster.of(textSubagent("a", "part a")))
                .taskContext(new dev.agentkit.core.concurrent.TaskContext() {
                    @Override
                    public <T> java.util.concurrent.Callable<T> wrap(
                            java.util.concurrent.Callable<T> task) {
                        return () -> {
                            throw thrown;
                        };
                    }
                })
                .build();

        assertThatThrownBy(() -> supervisor.fanOut(Goal.of("g"),
                List.of(DelegatedTask.of("a", "x"))))
                .as("an Error around a subagent was rewrapped as a RuntimeException")
                .isSameAs(thrown);
    }

    // --- what the synthesis prompt does with the goal it was handed (#142) ------

    /**
     * The largest goal {@code SubagentTools} can itself build: its routing sentence, the
     * whole {@code Spotlight.INSTRUCTION} clause, and a request already cut to the
     * delegation ceiling. 6,054 characters, measured — of which 2,039 are the framework's.
     */
    private static String largestDelegatedGoal(String request) {
        java.util.List<String> handedIn = new java.util.ArrayList<>();
        SubagentRoster roster = SubagentRoster.of(Subagent.handling("echo", "records",
                goal -> {
                    handedIn.add(goal.description());
                    return dev.agentkit.core.agent.AgentResult.completed("ok", 1);
                }));
        SubagentTools.delegateTool(roster).execute(new ToolInvocation("i", "delegate",
                java.util.Map.of("subagent", "echo", "goal", request)));
        return handedIn.get(0);
    }

    @Test
    void oneHopOfNestingDoesNotCutTheRequestAgain() {
        // #142's third defect. The goal slot used to share MAX_OUTCOME_CHARS (4,000) with a
        // subagent's answer, and a goal arriving at a *nested* supervisor is not a sentence
        // — it is a request the framework has already wrapped in 2,039 characters of its
        // own framing. Measured on the pre-fix branch: a 4,205-character request delegated
        // once reached this slot with 1,946 characters of itself left, the rest replaced by
        // an in-band "... [truncated]" and nothing said to anyone. The majority of a request
        // the framework had only just finished bounding, cut a second time.
        //
        // The worst case moved when #207 landed and got smaller. A subgoal the delegation
        // slot cannot carry is now refused rather than delivered cut, so the largest goal
        // the framework can build is 2,039 characters of framing on a request of exactly
        // the slot: 6,039, not 6,054, and it carries no truncation marker of its own. That
        // makes the assertion below stricter — there is no first cut to tell a second one
        // apart from — and leaves MAX_GOAL_CHARS with more headroom than it was sized for.
        String request = "R".repeat(SubagentTools.DEFAULT_MAX_OUTPUT_CHARS);
        String delegated = largestDelegatedGoal(request);
        assertThat(delegated).as("the framework's own worst case, measured").hasSize(6_039);

        FakeLlmClient synth = new FakeLlmClient(FakeLlmClient.text("done"));
        Supervisor.builder(SubagentRoster.of(textSubagent("a", "part a")))
                .synthesizer(Synthesizers.llm(synth, "m")).build()
                .fanOut(Goal.of(delegated), List.of(DelegatedTask.of("a", "x")));
        String prompt = synth.received().get(0).messages().get(0).text();

        // Whole: every character of the request that survived the delegation ceiling is
        // still here, in one unbroken run. Pinned from both sides — "contains 4,000" is also
        // true of 4,001, and a bare character count picks up the R in "ORIGINAL" and the one
        // in "RESULTS", which is how this assertion first read 4,002.
        assertThat(prompt).as("the nested slot cut the request a second time")
                .contains("R".repeat(SubagentTools.DEFAULT_MAX_OUTPUT_CHARS));
        assertThat(prompt).as("the nested slot invented request text")
                .doesNotContain("R".repeat(SubagentTools.DEFAULT_MAX_OUTPUT_CHARS + 1));
        // No truncation marker at all now: the delegation refuses rather than cutting, so
        // any marker here would be this slot's own. Cut.MARKER is a hint and not evidence,
        // so it is counted rather than trusted.
        assertThat(prompt.split(java.util.regex.Pattern.quote(Cut.MARKER), -1).length - 1)
                .as("the nested slot added a truncation of its own").isZero();
    }

    @Test
    void aGoalTooBigForTheSlotIsStillCutAndTheModelIsToldSo() {
        // Raising the ceiling is not removing it: a hand-built Goal is not bounded by
        // anything the framework wrote, and half a megabyte of it would decide what the rest
        // of the run costs — the argument MAX_OUTCOME_CHARS exists for, applied to the other
        // span in the prompt.
        FakeLlmClient synth = new FakeLlmClient(FakeLlmClient.text("done"));
        Supervisor.builder(SubagentRoster.of(textSubagent("a", "part a")))
                .synthesizer(Synthesizers.llm(synth, "m")).build()
                .fanOut(Goal.of("G".repeat(500_000)), List.of(DelegatedTask.of("a", "x")));
        String prompt = synth.received().get(0).messages().get(0).text();

        assertThat(prompt).as("the goal slot is unbounded").hasSizeLessThan(9_000);
        // Said in-band, where the model reading the shortened goal can see it, and said
        // again in the operator's log — which nothing here can assert, for the same reason
        // Synthesizers.fenceOf's info line has no test: there is no log capture in this
        // module. The in-band half is the half a test can hold, and Cut's own javadoc is why
        // it is not the whole answer: it "is a hint to a reader, not evidence about the
        // writer", so an operator who needs to know a run answered a shortened goal cannot
        // learn it from the prompt.
        assertThat(prompt).contains(Cut.MARKER);
        // Still fenced after the cut, and still attributed: a bound that drops the markers
        // would trade #142's third defect for its first.
        assertThat(Spotlight.outsideFences(prompt)).doesNotContain("GGGG");
        assertThat(prompt).contains("source=\"caller\" kind=\"procedure\">");
    }

    @Test
    void anExpandingGoalCannotOutgrowTheSlotOrTheWorkItCosts() {
        // The bound has to be on the emitted size, not on the field, because neutralising
        // expands and whoever writes the goal picks by how much: U+FDFA is one UTF-16 unit
        // that NFKC turns into eighteen. Spotlight.fenceBounded cuts twice for exactly this
        // — once to bound the work, once to bound the output — and the pre-fix slot did
        // neither: Cut.to(sizedAsFenced(description), 4_000) ran an UNBOUNDED normalising
        // pass over an attacker-chosen field and only then cut.
        //
        // Measured on 5,000,000 U+FDFA, five runs each: pre-fix built a 90,000,000-character
        // intermediate in 7.9-10.8 s; through fenceBounded it is 8,112 characters out in
        // 0.16-0.26 s, and both figures are flat in the input size (1,800,000 chars at
        // 100,000 in, 90,000,000 at 5,000,000 in, against a constant 8,112 either way).
        //
        // Asserted absolutely, per PromptBoundTest's rule: a bound stated as
        // "MAX_GOAL_CHARS + slack" rises with the constant it claims to pin, and this repo
        // has shipped an 8,000-character budget that emitted 135,475.
        FakeLlmClient synth = new FakeLlmClient(FakeLlmClient.text("done"));
        Supervisor.builder(SubagentRoster.of(textSubagent("a", "part a")))
                .synthesizer(Synthesizers.llm(synth, "m")).build()
                .fanOut(Goal.of("\uFDFA".repeat(200_000)), List.of(DelegatedTask.of("a", "x")));

        String prompt = synth.received().get(0).messages().get(0).text();
        assertThat(prompt.length()).as("an expanding goal outgrew the slot")
                .isLessThan(10_000);
    }

    @Test
    void aCutGoalIsReportedToTheOperatorAndNotOnlyToTheModel() {
        // The half Cut.MARKER cannot cover. Cut's own javadoc calls the marker "a hint to a
        // reader, not evidence about the writer" — any body can contain it and any body can
        // omit it — so an operator asking "did this run answer the goal it was given, or a
        // prefix of it?" cannot learn the answer from the prompt. Synthesizers.fenceOf has
        // said as much since it was written ("a cap nobody is told about reads as
        // 'everything was carried'"), and #142's third defect is that the goal slot, which
        // is the more serious of the two, had neither half.
        //
        // Captured off System.err rather than through a log appender, because slf4j-simple
        // is this repo's only binding, it is already on every module's test classpath, and
        // it resolves System.err per write. Measured, and the reason this test exists:
        // against eleven mutants of the goal slot, ten died to the prompt assertions and
        // "the cut is no longer logged" survived the entire core suite. It is the mutant
        // that matters most — it is #142's third defect exactly — and this is the only test
        // that kills it.
        java.io.PrintStream stderr = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        String log;
        try {
            System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
            FakeLlmClient synth = new FakeLlmClient(FakeLlmClient.text("done"));
            Supervisor.builder(SubagentRoster.of(textSubagent("a", "part a")))
                    .synthesizer(Synthesizers.llm(synth, "m")).build()
                    .fanOut(Goal.of("G".repeat(50_000)), List.of(DelegatedTask.of("a", "x")));
        } finally {
            System.setErr(stderr);
            log = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        assertThat(log).as("the goal was cut and nobody outside the prompt was told")
                .contains("Truncated the original goal");
        // WARN, not INFO. An outcome that was cut is a subagent that said too much; a goal
        // that was cut is this run answering a question it only partly received, and every
        // answer it produces from here is against the shortened one.
        assertThat(log).contains("WARN");
    }

    @Test
    void aGoalThatFitsSaysNothingToTheOperator() {
        // The other side of the same line: a log that fires either way reports nothing. The
        // guard is a branch and an unguarded warn passes the test above.
        java.io.PrintStream stderr = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        String log;
        try {
            System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
            FakeLlmClient synth = new FakeLlmClient(FakeLlmClient.text("done"));
            Supervisor.builder(SubagentRoster.of(textSubagent("a", "part a")))
                    .synthesizer(Synthesizers.llm(synth, "m")).build()
                    .fanOut(Goal.of("brief me"), List.of(DelegatedTask.of("a", "x")));
        } finally {
            System.setErr(stderr);
            log = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        assertThat(log).doesNotContain("Truncated the original goal");
    }

    // ---- #127: bounded per outcome, unbounded in total -------------------------------

    /** {@code n} subagents each returning {@code body}, in task order. */
    private static SubagentRoster rosterOf(int n, java.util.function.IntFunction<String> body) {
        Subagent[] subs = new Subagent[n];
        for (int i = 0; i < n; i++) {
            subs[i] = textSubagent("s" + i, body.apply(i));
        }
        return SubagentRoster.of(subs);
    }

    private static List<DelegatedTask> tasksOf(int n) {
        List<DelegatedTask> tasks = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            tasks.add(DelegatedTask.of("s" + i, "q"));
        }
        return tasks;
    }

    /** The synthesis prompt {@link Synthesizers#llm} builds for this fan-out. */
    private static String promptFor(SubagentRoster roster, List<DelegatedTask> tasks, String goal) {
        FakeLlmClient synth = new FakeLlmClient(FakeLlmClient.text("done"));
        Supervisor.builder(roster).synthesizer(Synthesizers.llm(synth, "m")).build()
                .fanOut(Goal.of(goal), tasks);
        return synth.received().get(0).messages().get(0).text();
    }

    /**
     * How many of {@code c} survived into the render — which is how much of a body was
     * carried, asked the way {@code Cut} says to ask.
     *
     * <p>{@code 'z'} and {@code 'y'} rather than {@code 'A'} and {@code 'B'}: the prompt's
     * own scaffolding is not empty of letters, and counting {@code 'A'} over the whole
     * string silently adds the ones in "ORIGINAL", "GOAL" and "SUBAGENT". Neither {@code z}
     * nor {@code y} occurs in any framework-written span here — the headings, the closing
     * line, the budget notice, {@code Cut.MARKER}, or a fence marker, whose id is lowercase
     * hex and whose label is a subagent name this test chose.
     */
    private static long countOf(String text, char c) {
        return text.chars().filter(ch -> ch == c).count();
    }

    @Test
    void aWideFanOutIsBoundedInTotalAndNotOnlyPerOutcome() {
        // #127. MAX_OUTCOME_CHARS stops one subagent deciding what the run costs; it says
        // nothing about N of them, and "bounded per outcome" reads as "bounded". Measured on
        // the pre-fix branch, and the pair is the whole issue: 500,000 characters from ONE
        // subagent produced a 4,305-character prompt, and the same 500,000 characters spread
        // over 125 subagents produced 513,202 — a factor of 119 between two runs carrying
        // identical text. Growth was exactly linear and uncapped: 4,290 at N=1, 65,841 at
        // N=16, 1,050,957 at N=256, 16,423,077 at N=4,000.
        int n = 200;
        List<DelegatedTask> tasks = tasksOf(n);
        SubagentRoster roster = rosterOf(n, i -> "A".repeat(4_000));

        String prompt = promptFor(roster, tasks, "brief me");
        String concat = Supervisor.builder(roster).synthesizer(Synthesizers.concatenating())
                .build().fanOut(Goal.of("brief me"), tasks).output();

        // 839,394 and 841,394 respectively before the fix.
        assertThat(prompt.length()).as("synthesis prompt at N=%d", n).isLessThan(100_000);
        assertThat(concat.length()).as("concatenated result at N=%d", n).isLessThan(100_000);

        // Bounded, not merely smaller: twenty times the subagents must not cost twenty times
        // the prompt. Before the fix this ratio was 20.
        String wider = promptFor(rosterOf(4_000, i -> "A".repeat(4_000)), tasksOf(4_000), "brief me");
        assertThat(wider.length()).isLessThan(prompt.length() + 1_000);
    }

    @Test
    void anOrdinaryFanOutPaysNothingForTheTotalBound() {
        // The bound has to be free where it is not needed, or it is a tax on the common case.
        // Six subagents with 1,200-character answers spend 7,200 of the 32,000 budget, so
        // every outcome arrives whole and nothing is said about a budget that did not bite.
        String prompt = promptFor(rosterOf(6, i -> "z".repeat(1_200)), tasksOf(6), "brief me");

        assertThat(countOf(prompt, 'z')).as("every body whole").isEqualTo(6 * 1_200);
        assertThat(prompt).doesNotContain(Cut.MARKER);
        assertThat(Spotlight.outsideFences(prompt)).doesNotContain("subagent result(s)");
    }

    @Test
    void theLargestOutcomesPayAndTheSmallOnesAreUntouched() {
        // The design question #127 forces: when the total bound bites, what gets cut? Three
        // policies were measured against 40 outcomes — 8 long (4,000) and 32 terse (250) —
        // sharing a 32,000-character budget.
        //
        //   first come, first served  carried 7 of 40, DROPPED 33 outright
        //   proportional              dropped none, shortened all 40 including the 32 terse
        //   max-min fair share        dropped none, shortened 8, at a level of 3,000
        //
        // This asserts the third, and the three are distinguishable by nothing but these
        // counts: first-come leaves 28,000 long characters and no terse ones, proportional
        // 25,600 and 6,400, max-min 24,000 and 8,000.
        List<DelegatedTask> tasks = tasksOf(40);
        String prompt = promptFor(
                rosterOf(40, i -> i < 8 ? "z".repeat(4_000) : "y".repeat(250)), tasks, "brief me");

        assertThat(countOf(prompt, 'y'))
                .as("a terse outcome nowhere near any ceiling does not pay for a talkative one")
                .isEqualTo(32 * 250);
        assertThat(countOf(prompt, 'z'))
                .as("the 8 long outcomes share what the 32 terse ones left, at a level of 3,000")
                .isEqualTo(8 * 3_000);
    }

    @Test
    void theTotalBoundDoesNotUndoThePerOutcomeBound() {
        // The trap in extending a per-outcome cap with a total one: the share replaces
        // MAX_OUTCOME_CHARS at the fenceBounded call, so a share computed from raw body
        // lengths can come back ABOVE the per-outcome cap and the fix for #127 would silently
        // repeal #126. Measured with the sizes taken uncapped, the level reaches 32,000 — the
        // whole budget, eight times the per-outcome cap — and one talkative subagent is back
        // to deciding what the rest of the run costs. Capping each size at MAX_OUTCOME_CHARS
        // before the water-filling walk is what holds the level strictly below it.
        SubagentRoster roster = SubagentRoster.of(
                textSubagent("s0", "z".repeat(500_000)),
                textSubagent("s1", "y".repeat(100)));
        List<DelegatedTask> tasks = List.of(DelegatedTask.of("s0", "q"), DelegatedTask.of("s1", "q"));

        String prompt = promptFor(roster, tasks, "brief me");

        assertThat(countOf(prompt, 'z'))
                .as("one huge outcome beside a small one is still held to the per-outcome cap")
                .isEqualTo(4_000);
        assertThat(countOf(prompt, 'y')).as("and the small one is untouched").isEqualTo(100);
    }

    @Test
    void whatAnOutcomeGetsDoesNotDependOnTheOrderTheTasksWereListedIn() {
        // Why max-min rather than first-come, and the disqualifying result rather than merely
        // the worst one. Measured, the same multiset rendered two ways: first-come carried 7
        // of 40 with the long outcomes listed first and 27 of 40 with them interleaved. What
        // a subagent contributes would depend on something it cannot see. A board can afford
        // that because read_board answers a cut page with a `since` cursor; a supervisor's
        // outcomes have no cursor, and this session has already shipped a bound that cut the
        // tail of the evidence the newest facts were in.
        String longFirst = promptFor(
                rosterOf(40, i -> i < 8 ? "z".repeat(4_000) : "y".repeat(250)), tasksOf(40), "brief me");
        String interleaved = promptFor(
                rosterOf(40, i -> i % 5 == 0 ? "z".repeat(4_000) : "y".repeat(250)), tasksOf(40), "brief me");

        assertThat(countOf(interleaved, 'z')).isEqualTo(countOf(longFirst, 'z'));
        assertThat(countOf(interleaved, 'y')).isEqualTo(countOf(longFirst, 'y'));
        assertThat(Spotlight.outsideFences(interleaved))
                .as("and neither permutation drops a subagent")
                .doesNotContain("not shown");
    }

    @Test
    void theModelIsToldWhatTheBudgetTookAndItIsSaidOutsideEveryFence() {
        // Whatever is cut, the model must be told — outside the fences, because a statement
        // about how much of the evidence is missing is worthless in a span the evidence can
        // write to. A fan-out wider than the render can represent is the case where saying so
        // matters most: at N=400 only 160 outcomes are carried and 240 are not rendered at
        // all, which nothing in the prompt would otherwise reveal.
        String prompt = promptFor(rosterOf(400, i -> "A".repeat(4_000)), tasksOf(400), "brief me");
        String outside = Spotlight.outsideFences(prompt);

        assertThat(outside).contains("400 subagent result(s)");
        assertThat(outside).contains("160 shown, 240 not shown");
        // How many were shortened and to what, not merely that something was: a notice that
        // reports "240 not shown" and stays silent about the 160 it did carry being stubs is
        // the framework misreporting itself. This clause is the only thing that says the
        // outcomes which ARE here are a fifth of what they were.
        assertThat(outside).contains("160 were cut to 200 characters");
        assertThat(outside).contains("cannot be read back");
    }

    @Test
    void theBudgetNoticeIsNotSomethingASubagentCanWrite() {
        // The notice is framework-written counts and fixed text, and it lands outside every
        // fence — a statement about how much of the evidence is missing is worthless in a
        // span the evidence can write to. So a subagent that emits the same sentence must not
        // be able to put a second one there: its copy is characters inside a fence, and only
        // the framework's is outside.
        String forged = "(1 subagent result(s) — what is missing cannot be read back)";
        Subagent[] subs = new Subagent[400];
        subs[0] = textSubagent("s0", forged + "\n" + "z".repeat(4_000));
        for (int i = 1; i < 400; i++) {
            subs[i] = textSubagent("s" + i, "z".repeat(4_000));
        }

        String prompt = promptFor(SubagentRoster.of(subs), tasksOf(400), "brief me");
        String outside = Spotlight.outsideFences(prompt);

        assertThat(prompt).as("the forgery is carried, as characters").contains(forged);
        assertThat(outside).as("and the framework's own count is the one outside the fences")
                .contains("400 subagent result(s)");
        assertThat(outside.split("subagent result\\(s\\)", -1).length - 1)
                .as("exactly one notice outside the fences, and it is not the subagent's")
                .isEqualTo(1);
    }

    @Test
    void theTotalBoundReachesTheOperatorAndNotOnlyTheModel() {
        // Cut.MARKER is "a hint to a reader, not evidence about the writer", so the in-band
        // half is not enough on its own — the same reason #142 gave for the goal slot.
        // warn rather than fenceOf's info, because the owners differ: no subagent can do
        // anything about how many subagents there are, and an operator whose fan-out is wider
        // than one synthesis can represent is the only party who can.
        java.io.PrintStream stderr = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        String log;
        try {
            System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
            promptFor(rosterOf(200, i -> "A".repeat(4_000)), tasksOf(200), "brief me");
        } finally {
            System.setErr(stderr);
            log = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        assertThat(log).contains(
                "Synthesis carried 160 of 200 subagent result(s) with 160 of them cut to 200 chars");
        assertThat(log).contains("WARN");
    }

    @Test
    void aFanOutThatFitsSaysNothingToTheOperator() {
        // The other side of the branch: a log that fires either way reports nothing, and an
        // unguarded warn passes the test above.
        java.io.PrintStream stderr = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        String log;
        try {
            System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
            promptFor(rosterOf(6, i -> "A".repeat(1_200)), tasksOf(6), "brief me");
        } finally {
            System.setErr(stderr);
            log = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        assertThat(log).doesNotContain("Synthesis carried");
    }

    /**
     * The one place this class's two truncation lines are compared, which is the only way
     * the level they were given means anything (#225).
     *
     * <p>{@code Synthesizers}' javadoc argues the split three times — "{@link
     * Synthesizers#goalOf} warns where {@code fenceOf} informs", "the level tracks who has
     * to act, not how many characters went" — and no test held either line to it. A
     * {@code warn} nobody can afford to read is not a {@code warn}, so an {@code info}
     * quietly promoted to {@code warn} is a regression the prompt assertions cannot see:
     * every one of them passes with both lines at either level.
     *
     * <p>Driven in a single fan-out with both cuts firing, so the comparison is between two
     * lines from one run rather than between two runs that could differ for other reasons.
     * The subagent's own line is the {@code info}: an outcome that was cut is a subagent
     * that said too much, and the subagent is who can say less. The goal's is the
     * {@code warn}: every answer this run produces from here is against a question it only
     * partly received, and only the operator can do anything about that.
     *
     * <p>Asserted per line rather than over the whole capture, because "the log contains
     * WARN somewhere" is satisfied by the other line, and asserting the capture contains no
     * {@code WARN} at all would break the moment anything else in a fan-out warns.
     */
    @Test
    void anOutcomeCutToItsShareIsSaidAtInfoWhereACutGoalIsSaidAtWarn() {
        String log = errWhile(() ->
                promptFor(rosterOf(1, i -> "z".repeat(6_000)), tasksOf(1), "G".repeat(50_000)));

        assertThat(theLineSaying(log, "Truncated output from subagent"))
                .as("a cap nobody is told about reads as 'everything was carried', and the"
                        + " in-band Cut.MARKER is forgeable by the body it sits in")
                .contains("Truncated output from subagent s0 to 4000 chars for synthesis")
                .contains(" INFO ")
                .doesNotContain(" WARN ");
        assertThat(theLineSaying(log, "Truncated the original goal"))
                .as("a goal that was cut is this run answering a question it only partly"
                        + " received, and nobody but the operator can widen it")
                .contains(" WARN ")
                .doesNotContain(" INFO ");

        // The other side of the branch this test's own line sits on: an outcome inside its
        // share says nothing, so an unguarded info would pass the assertions above.
        assertThat(errWhile(() -> promptFor(rosterOf(1, i -> "z".repeat(100)), tasksOf(1),
                "brief me")))
                .as("an outcome that fit whole was announced as truncated")
                .doesNotContain("Truncated output from subagent");
    }

    /**
     * The single log entry containing {@code needle}, or a failure naming what was found.
     *
     * <p>A level assertion over the whole capture is satisfied by any other line in it, and
     * this class's two truncation lines sit in the same capture at different levels — which
     * is the entire thing under test.
     */
    private static String theLineSaying(String log, String needle) {
        List<String> found = log.lines().filter(line -> line.contains(needle)).toList();
        assertThat(found)
                .as("expected exactly one operator line saying '%s'", needle)
                .hasSize(1);
        return found.get(0);
    }

    // --- what the cap is scoped to (#297) and what a nested fan-out does to it (#298) ---

    /**
     * Blocks, bounded, until {@code wanted} bodies are live at once — the window in which
     * bodies that are allowed to overlap will be caught doing so. A lone body waits the
     * whole window and leaves, so the honest reading of a peak of one is "nobody else was
     * ever admitted", not "the timing happened not to line up".
     */
    private static void awaitCompany(AtomicInteger live, int wanted) {
        long deadline = System.nanoTime() + Duration.ofMillis(400).toNanos();
        while (live.get() < wanted && System.nanoTime() < deadline) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A subagent that records the highest number of its own bodies ever live at once. */
    private static Subagent peakRecording(String name, AtomicInteger live, AtomicInteger peak,
            int lookingFor) {
        return Subagent.handling(name, name, goal -> {
            peak.accumulateAndGet(live.incrementAndGet(), Math::max);
            awaitCompany(live, lookingFor);
            peak.accumulateAndGet(live.get(), Math::max);
            live.decrementAndGet();
            return AgentResult.completed("ok", 1);
        });
    }

    @Test
    @Timeout(30)
    void maxConcurrencyIsSharedAcrossConcurrentFanOuts() {
        // A supervisor is immutable, built from a roster, and takes its tasks as arguments,
        // so serving several goals at once is the shape it invites. A per-fan-out gate made
        // maxConcurrency(4) mean sixteen simultaneous model calls under four concurrent
        // callers — at the rate-limited backend the setting names.
        int limit = 2;
        AtomicInteger live = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Supervisor supervisor = Supervisor.builder(
                        SubagentRoster.of(peakRecording("probe", live, peak, limit + 1)))
                .maxConcurrency(limit).build();
        List<DelegatedTask> two = List.of(
                DelegatedTask.of("probe", "1"), DelegatedTask.of("probe", "2"));

        List<Thread> callers = List.of(
                Thread.ofVirtual().start(() -> supervisor.fanOut(Goal.of("a"), two)),
                Thread.ofVirtual().start(() -> supervisor.fanOut(Goal.of("b"), two)));
        callers.forEach(SupervisorTest::join);

        assertThat(peak.get())
                .as("two concurrent fan-outs under maxConcurrency(%d) must share the budget,"
                        + " not get one each", limit)
                .isLessThanOrEqualTo(limit);
    }

    @Test
    @Timeout(30)
    void aSubagentCanFanOutAgainUnderThePermitItAlreadyHolds() {
        // Sharing the gate across fan-outs is what #297 asks for, and on its own it imports
        // #298: a subagent that delegates further on the same supervisor would queue for a
        // permit its own caller is holding. That is a permanent hang with no result, no
        // error and nothing logged, so it is the shape the gate has to be lent around.
        AtomicReference<Supervisor> self = new AtomicReference<>();
        Subagent inner = Subagent.handling("inner", "inner", goal -> AgentResult.completed("leaf", 1));
        Subagent outer = Subagent.handling("outer", "outer", goal -> AgentResult.completed(
                self.get().fanOut(goal, List.of(DelegatedTask.of("inner", "sub"))).output(), 1));
        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(outer, inner))
                .maxConcurrency(1).build();
        self.set(supervisor);

        SupervisionResult result = supervisor.fanOut(
                Goal.of("g"), List.of(DelegatedTask.of("outer", "x")));

        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.output()).contains("leaf");
    }

    @Test
    @Timeout(60)
    void aNestedFanOutTakesTurnsInTheOuterSubagentsPermitRatherThanRunningFree() {
        // Lending must not be a free pass. If a nested fan-out simply skipped the gate,
        // maxConcurrency(1) would put three subagents on the backend at once and the bound
        // would again mean something other than what it says — the very complaint that
        // scoping it to the supervisor was meant to answer.
        AtomicInteger live = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicReference<Supervisor> self = new AtomicReference<>();
        Subagent outer = Subagent.handling("outer", "outer", goal -> AgentResult.completed(
                self.get().fanOut(goal, List.of(DelegatedTask.of("worker", "a"),
                        DelegatedTask.of("worker", "b"),
                        DelegatedTask.of("worker", "c"))).output(), 1));
        Supervisor supervisor = Supervisor.builder(SubagentRoster.of(
                        outer, peakRecording("worker", live, peak, 2)))
                .maxConcurrency(1).build();
        self.set(supervisor);

        SupervisionResult result = supervisor.fanOut(
                Goal.of("g"), List.of(DelegatedTask.of("outer", "x")));

        assertThat(result.allSucceeded()).isTrue();
        assertThat(peak.get())
                .as("maxConcurrency(1) still means one subagent at a time, nested or not")
                .isEqualTo(1);
    }

    /**
     * Everything {@code body} writes to {@code System.err}, with the stream put back.
     *
     * <p>{@code slf4j-simple} resolves {@code System.err} on each write — its {@code
     * cacheOutputStream} setting is off by default — so a logger initialised long before
     * this call still lands in the buffer. Restored in a {@code finally}, because a test
     * that leaves {@code System.err} replaced takes the rest of the suite's output with it.
     */
    private static String errWhile(Runnable body) {
        java.io.PrintStream stderr = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true,
                java.nio.charset.StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setErr(stderr);
        }
        return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
