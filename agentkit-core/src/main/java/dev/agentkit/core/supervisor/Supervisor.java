package dev.agentkit.core.supervisor;

import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.util.Quoted;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.concurrent.TaskContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates a roster of {@link Subagent}s: given a goal already decomposed
 * into {@link DelegatedTask}s, it fans the subgoals out (in parallel by default),
 * collects each {@link SubagentOutcome}, and hands them to a {@link Synthesizer}
 * for a single final answer — the delegate/collect/synthesize half of
 * supervision.
 *
 * <p><strong>Decomposition</strong> — turning one goal into subgoals — has two
 * supported paths:
 * <ul>
 *   <li><em>Programmatic:</em> the caller builds the {@link DelegatedTask} list
 *       and calls {@link #fanOut}. Best when the split is known up front and the
 *       subgoals are independent, so they can run concurrently.</li>
 *   <li><em>Model-driven:</em> wire {@link SubagentTools#delegateTool} into an
 *       ordinary supervisor {@link dev.agentkit.core.agent.Agent}; its model
 *       decides which subagents to call and with what subgoals, one at a time.
 *       Best when the split depends on intermediate results.</li>
 * </ul>
 *
 * <p>Independent subgoals run concurrently on an injectable executor (a
 * per-call virtual-thread executor by default). Because each {@link Subagent}
 * builds a fresh agent per delegation, parallel fan-out shares no per-run state.
 * Thread-locals do not cross that handoff, so set a {@link Builder#taskContext}
 * if you are tracing — otherwise each subagent's spans start a trace of their own.
 * A subagent that fails does not abort the others: its failure is captured in its
 * outcome and passed to the synthesizer, which decides how to present the gap.
 *
 * <p><strong>Reliability bounds (both opt-in).</strong> An optional
 * {@link Builder#maxConcurrency(int)} caps how many subagents run at once, so a
 * large decomposition does not fire unbounded simultaneous LLM calls at a
 * rate-limited backend — and the cap belongs to the supervisor, so concurrent
 * fan-outs share the budget rather than each getting their own. An optional
 * {@link Builder#timeout(Duration)} sets an
 * overall deadline for the whole fan-out: a subagent still running when it
 * elapses is cancelled and recorded as a failed (timed-out) outcome rather than
 * stranding the others. <em>Still running</em> is the operative phrase and is now
 * enforced as written (#255): a subagent that had finished has its outcome
 * collected however late the collecting thread gets to it, so a supervisor
 * descheduled past its own deadline reports the work that was done rather than a
 * fan-out of timeouts. On any exit — normal, timeout, or an unexpected error —
 * still-running futures are cancelled before {@code fanOut} returns.
 *
 * <p>Instances are immutable and reusable, and one supervisor can serve concurrent
 * fan-outs: every field is final and all per-fan-out state is local to
 * {@link #fanOut}. That is why {@code maxConcurrency} is scoped to the supervisor
 * rather than to the call — a per-call gate would let N concurrent fan-outs
 * multiply the cap by N at the very backend it names.
 */
public final class Supervisor {

    private static final Logger log = LoggerFactory.getLogger(Supervisor.class);

    private final SubagentRoster roster;
    private final Synthesizer synthesizer;
    private final ExecutorService executor; // nullable: null => a fresh per-call executor
    private final Semaphore gate;            // nullable: null => unbounded. Shared across fan-outs.
    private final Duration timeout;          // nullable => no deadline
    private final TaskContext taskContext;

    /**
     * The single permit a running subagent lends to any fan-out it starts on this same
     * supervisor, set on that subagent's own thread for the length of its delegation.
     * Null on a thread holding no permit, which is every thread that starts a top-level
     * fan-out. See {@link Builder#maxConcurrency(int)} for what it buys.
     */
    private final ThreadLocal<Semaphore> lentGate = new ThreadLocal<>();

    private Supervisor(Builder b) {
        this.roster = Objects.requireNonNull(b.roster, "roster");
        this.synthesizer = Objects.requireNonNull(b.synthesizer, "synthesizer");
        this.executor = b.executor;
        // One semaphore for the supervisor, not one per fan-out: the cap exists to protect
        // a rate-limited backend, and a per-fan-out gate would let N concurrent fan-outs
        // multiply it by N — which is exactly what an immutable, roster-built, reusable
        // supervisor invites. AgentGraph made and wrote down this decision first; this is
        // the same one, for the same reason.
        this.gate = b.maxConcurrency > 0 ? new Semaphore(b.maxConcurrency) : null;
        this.timeout = b.timeout;
        this.taskContext = b.taskContext;
    }

    public static Builder builder(SubagentRoster roster) {
        return new Builder(roster);
    }

    /** A supervisor over {@code roster} that concatenates subagent outputs. */
    public static Supervisor of(SubagentRoster roster) {
        return builder(roster).build();
    }

    /**
     * Runs each task's subagent (concurrently), collects the outcomes in task
     * order, and synthesizes a final answer.
     *
     * @throws IllegalArgumentException if a task names a subagent absent from the
     *                                  roster — a caller/configuration error caught
     *                                  before any subagent runs
     */
    public SupervisionResult fanOut(Goal original, List<DelegatedTask> tasks) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(tasks, "tasks");
        List<DelegatedTask> work = List.copyOf(tasks);
        validateRouting(work);

        List<SubagentOutcome> outcomes = work.isEmpty() ? List.of() : execute(work);

        int totalSteps = 0;
        TokenUsage totalUsage = TokenUsage.ZERO;
        for (SubagentOutcome outcome : outcomes) {
            totalSteps += outcome.result().steps();
            totalUsage = totalUsage.plus(outcome.result().usage());
        }

        String output = synthesizer.synthesize(original, outcomes);
        return new SupervisionResult(output, outcomes, totalSteps, totalUsage);
    }

    private void validateRouting(List<DelegatedTask> tasks) {
        for (DelegatedTask task : tasks) {
            if (roster.find(task.subagentName()).isEmpty()) {
                // The same line SubagentTools writes when a name does not resolve, and it
                // was raw here: a name the caller invented reached a framework message and
                // a List.toString rendered the roster, so one name could read as two. This
                // one reaches an operator's log rather than a model, which makes it #98's
                // problem rather than #92's — the same two tools answer both.
                throw new IllegalArgumentException("No subagent named '"
                        + Spotlight.name(task.subagentName()) + "' in the roster "
                        + Quoted.each(roster.names()));
            }
        }
    }

    private List<SubagentOutcome> execute(List<DelegatedTask> tasks) {
        if (executor != null) {
            return runOn(executor, tasks);
        }
        try (ExecutorService perCall = Executors.newVirtualThreadPerTaskExecutor()) {
            return runOn(perCall, tasks);
        }
    }

    private List<SubagentOutcome> runOn(ExecutorService exec, List<DelegatedTask> tasks) {
        // Read here, on the thread that called fanOut, because that is the thread a
        // subagent runs a nested fan-out's driver on. Carried into each submission by hand
        // rather than left to inherit: an injected pool's threads were created long before
        // this call and would inherit nothing — the same seam taskContext exists to cross.
        Semaphore lent = lentGate.get();
        long deadlineNanos = timeout != null ? System.nanoTime() + timeout.toNanos() : 0L;

        List<Future<SubagentOutcome>> futures = new ArrayList<>(tasks.size());
        for (DelegatedTask task : tasks) {
            // Captured here, on the submitting thread, so a subagent's work lands inside
            // whatever the caller had current — a trace, an MDC — rather than starting
            // from nothing on a pool thread. A third-party TaskContext that throws is
            // this subagent's failure like any other, not grounds for losing the rest.
            futures.add(exec.submit(submission(task, lent)));
        }
        try {
            List<SubagentOutcome> outcomes = new ArrayList<>(tasks.size());
            for (int i = 0; i < tasks.size(); i++) {
                outcomes.add(await(tasks.get(i), futures.get(i), deadlineNanos));
            }
            return outcomes;
        } finally {
            // Normal exit leaves nothing running; on timeout or an unexpected error
            // this frees any subagent still in flight instead of leaking it.
            for (Future<SubagentOutcome> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }
    }

    private Callable<SubagentOutcome> submission(DelegatedTask task, Semaphore lent) {
        try {
            return taskContext.wrap(() -> runOne(task, lent));
        } catch (RuntimeException e) {
            log.warn("TaskContext threw while submitting subagent '{}'",
                    Quoted.of(task.subagentName()), Quoted.failure(e));
            return () -> new SubagentOutcome(task.subagentName(), task.goal(), AgentResult.failed(e, 0));
        }
    }

    private SubagentOutcome runOne(DelegatedTask task, Semaphore lent) {
        // A subagent of a nested fan-out queues on the permit the outer subagent lent it,
        // not on the supervisor's gate — which that outer subagent is holding and will not
        // release until this returns. Waiting on the gate there is a deadlock with no
        // result, no error and nothing logged.
        Semaphore slot = lent != null ? lent : gate;
        if (slot != null) {
            try {
                slot.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return timedOut(task); // cancelled while waiting for a concurrency slot
            }
            // One permit to lend on, so a nested fan-out's subagents take turns inside this
            // subagent's slot instead of all running free: the cap stays a cap.
            lentGate.set(new Semaphore(1));
        }
        try {
            AgentResult result;
            try {
                result = roster.find(task.subagentName()).orElseThrow().handle(task.goal());
            } catch (Throwable t) {
                // Throwable, not RuntimeException, and for the two reasons AgentGraph.runNode
                // gives one package away (#135). A subagent must never take the whole fan-out
                // down: an Error escaping into the Future destroys every sibling's completed
                // work on the way out, because `await` abandons the remaining futures. And it
                // used to be laundered on that way out — `await` wrapped the cause in an
                // IllegalStateException, so an Error arrived at whoever called fanOut as a
                // RuntimeException. That matters more since #241: Agent.runTool now has an
                // Error branch that ends the run, and an Error dressed as a RuntimeException
                // walks straight past it into the branch that feeds the model an error result
                // and carries on. A broken JVM invariant was reported as an ordinary handled
                // tool failure.
                //
                // Terminal for this subagent, not for the fan-out — the same split AgentGraph
                // makes, and the reason the Throwable itself is kept on the outcome rather
                // than replaced by a summary: whoever reads the outcome can still see what it
                // was and decide, which is what laundering took away.
                log.warn("Subagent '{}' threw during delegation",
                        Quoted.of(task.subagentName()), Quoted.failure(t));
                result = AgentResult.failed(t, 0);
            }
            return new SubagentOutcome(task.subagentName(), task.goal(), result);
        } finally {
            if (slot != null) {
                // Removed, not left set: an injected pool reuses this thread, and a stale
                // lent permit would let the next unrelated subagent past the gate.
                lentGate.remove();
                slot.release();
            }
        }
    }

    private SubagentOutcome await(DelegatedTask task, Future<SubagentOutcome> future, long deadlineNanos) {
        try {
            if (timeout == null) {
                return future.get();
            }
            // The deadline bounds *waiting*, not *collecting* (#255). A subagent that has
            // already finished is not "still running when it elapses", which is the only
            // thing this class promises to cancel, so its outcome is read whatever the clock
            // says. Before this, a collector thread that lost the CPU past the deadline
            // discarded finished work: every subagent in the fan-out could have completed and
            // every one of them be reported as a timeout, because of where the *supervisor*
            // was descheduled rather than anything a subagent did. That is a wall-clock
            // reading of a property about work done, and on a contended machine it is the
            // reading that fires.
            //
            // Cancelled is excluded deliberately: isDone() is true for a cancelled future
            // too, and there is nothing to read there. A task that has completed normally
            // can no longer be cancelled, so this pair cannot go stale between the test and
            // the get.
            if (future.isDone() && !future.isCancelled()) {
                return future.get();
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                // One spelling for both ways the deadline can be reached, because they are
                // the same event: the clock ran out on a subagent that had not finished.
                // Two spellings left this one silent — a subagent could be reported as
                // timed out with nothing said to anybody — and gave the log line two lives,
                // so a mutant could delete either half and survive whichever test only
                // reached the other.
                return cancelAsTimedOut(task, future);
            }
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return cancelAsTimedOut(task, future);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting a subagent", e);
        } catch (ExecutionException e) {
            // runOne catches Throwable itself, so reaching here means something around the
            // subagent threw rather than the subagent: a TaskContext wrapper that throws
            // when the wrapped callable is *called* is the path that exists today.
            Throwable cause = e.getCause();
            if (cause instanceof Error error) {
                // Rethrown as itself. Wrapping it below would turn an Error into a
                // RuntimeException, which is #135's second harm one layer out: a caller
                // that deliberately treats an Error differently -- Agent.runTool since
                // #241 -- would never see one. This changes no control flow; the fan-out
                // was already ending here, and only the type it ends with is different.
                throw error;
            }
            throw new IllegalStateException("Subagent execution failed unexpectedly",
                    cause != null ? cause : e);
        }
    }

    /** Cancels a subagent the deadline caught, says so once, and records the failure. */
    private SubagentOutcome cancelAsTimedOut(DelegatedTask task, Future<SubagentOutcome> future) {
        future.cancel(true);
        log.warn("Subagent '{}' timed out after {}", Quoted.of(task.subagentName()), timeout);
        return timedOut(task);
    }

    private static SubagentOutcome timedOut(DelegatedTask task) {
        return new SubagentOutcome(task.subagentName(), task.goal(),
                AgentResult.failed(new TimeoutException("Subagent timed out"), 0));
    }

    /** Fluent construction; {@code synthesizer} defaults to concatenation. */
    public static final class Builder {
        private final SubagentRoster roster;
        private Synthesizer synthesizer = Synthesizers.concatenating();
        private ExecutorService executor;
        private TaskContext taskContext = TaskContext.NONE;
        private int maxConcurrency;
        private Duration timeout;

        private Builder(SubagentRoster roster) {
            this.roster = roster;
        }

        public Builder synthesizer(Synthesizer synthesizer) {
            this.synthesizer = Objects.requireNonNull(synthesizer, "synthesizer");
            return this;
        }

        /**
         * Carries the calling thread's ambient context into each subagent — with
         * {@code agentkit-otel}'s {@code telemetry.taskContext()}, that is what makes a
         * parallel fan-out arrive as one trace rather than one detached trace per
         * subagent. Applies whether or not you also supply an
         * {@link #executor(ExecutorService)}, which matters because the default per-call
         * executor is not yours to wrap.
         *
         * <p>It does not inherit: a subagent that itself runs an {@code AgentGraph} or a
         * nested {@code Supervisor} must set it there too, or that level detaches while
         * the rest of the trace looks fine.
         */
        public Builder taskContext(TaskContext taskContext) {
            this.taskContext = Objects.requireNonNull(taskContext, "taskContext");
            return this;
        }

        /**
         * Runs subagents on {@code executor} instead of a fresh per-call
         * virtual-thread executor. The supervisor does not take ownership: the
         * caller is responsible for its lifecycle (and must not shut it down while
         * a {@code fanOut} is in flight).
         *
         * <p>Do not have a subagent run a nested fan-out on this same executor if it is
         * bounded — the outer subagent occupies a thread while waiting for the inner
         * one, and a small fixed pool will deadlock. The same sentence stands on
         * {@code AgentGraph.Builder.executor}, for the same mechanism.
         *
         * <p>If you are tracing, prefer {@link #taskContext} to wrapping this: it also
         * covers the default per-call executor, so the setting does not silently stop
         * working the day someone removes the injection.
         */
        public Builder executor(ExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /**
         * Caps how many subagents run concurrently (default unbounded). Applies
         * regardless of the executor: excess subagents wait for a slot rather than
         * issuing simultaneous LLM calls.
         *
         * <p><strong>The cap belongs to the supervisor</strong>, so concurrent fan-outs
         * share the budget rather than each getting their own. A rate-limited backend
         * does not care how many fan-outs you started, and a supervisor is immutable,
         * built from a roster, and takes its tasks as arguments — so serving several
         * goals at once is the shape it invites, and a per-call gate would quietly turn
         * {@code maxConcurrency(4)} into sixteen simultaneous model calls under four
         * concurrent callers.
         *
         * <p><strong>A subagent that starts another fan-out on this same supervisor runs
         * it under the permit it already holds.</strong> Without that, a nested fan-out
         * would queue for a permit its own caller is holding and hang forever with no
         * result, no error and no log line. So the outer subagent lends its slot: the
         * nested fan-out's subagents take turns in that one permit, which keeps the cap
         * honest — a subagent blocked on a nested fan-out is not doing work, so the
         * supervisor still never has more than {@code maxConcurrency} subagents on the
         * backend at once — and makes the deadlock unexpressible. The lending
         * travels with the subagent's own thread, so a subagent that hands the nested
         * fan-out to some <em>other</em> thread and waits for it is back to the deadlock;
         * set a {@link #timeout(Duration)} if you build that, which turns the hang into
         * reported timed-out outcomes.
         *
         * <p>A permit is held for a whole delegation, and the budget is the supervisor's,
         * so a subagent that ignores cancellation holds its slot for as long as it keeps
         * running — against later fan-outs too, not only its own. That is the price of a
         * cap that spans fan-outs rather than one thrown away with each of them, and it is
         * the one behaviour a caller loses by the cap meaning what its name says.
         */
        public Builder maxConcurrency(int maxConcurrency) {
            if (maxConcurrency < 1) {
                throw new IllegalArgumentException("maxConcurrency must be >= 1, was " + maxConcurrency);
            }
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        /**
         * Sets an overall deadline for a {@code fanOut} (default none). A subagent
         * still running when it elapses is cancelled and recorded as a failed,
         * timed-out outcome; the rest are unaffected.
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive, was " + timeout);
            }
            this.timeout = timeout;
            return this;
        }

        public Supervisor build() {
            return new Supervisor(this);
        }
    }
}
