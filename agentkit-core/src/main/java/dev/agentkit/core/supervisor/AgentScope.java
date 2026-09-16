package dev.agentkit.core.supervisor;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.concurrent.TaskContext;
import dev.agentkit.core.reliability.PendingApproval;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Work a run started and has not collected yet, bounded by the run that started it (#328).
 *
 * <p>{@code Supervisor.fanOut} decomposes when the subgoals are known up front, and
 * {@code SubagentTools.delegate} decomposes one subgoal at a time — but both <em>block</em>.
 * A run cannot start something, carry on, and collect later, so parallelism is the
 * deployment's to arrange and never the model's to ask for.
 *
 * <h2>Nested lifetime, not actors</h2>
 *
 * <p>Work started here outlives the <em>call</em> that started it and never the
 * <em>run</em>. That is the whole design, and it is what keeps the rest of this framework
 * intact: the run stays the unit of policy ({@code Conformance.holdingTo} is
 * {@code boundToOneRun}), the unit of record (one {@code RunLedger}), and the unit of
 * report. Agents that outlived a run would make every one of them the kind of thing
 * {@code ToolActivitiesImpl} already refuses at registration.
 *
 * <p>The parent link (#317) survives for a reason worth stating, because it looks like it
 * should not: {@code Tool.boundTo} works because the parent's dispatch is on the stack when
 * a child runs, and deferred execution takes that away. It does not matter. <strong>The
 * link is minted at {@link #start}, and starting is synchronous.</strong> The parent
 * {@link AgentRun} is captured on the calling thread and handed to
 * {@link Subagent#handle(Goal, AgentRun)}, which mints {@code parent.child(name)} exactly as
 * a blocking delegation would.
 *
 * <p><strong>Pass the same {@link AgentRun} instance the parent agent is running under</strong>
 * — the one handed to {@code agent.run(goal, run)}. Nothing here can check that, and a scope
 * built over a freshly minted run produces children whose parent link points at a run that
 * never executed. {@code AgentRun}'s own javadoc rejected a {@code ThreadLocal} for this
 * reason: a link that is right on the common path and quietly wrong on the other is worse
 * than none.
 *
 * <h2>Three decisions, settled</h2>
 *
 * <ul>
 *   <li><strong>No cancellation.</strong> Started work runs to its own completion or its own
 *       step limit. A child is already bounded by {@code maxSteps} and by the gate on every
 *       call it makes, so cancellation would buy latency rather than safety — and it would
 *       add a second flavour of "we do not know whether this happened" to a trail that #131,
 *       #181 and #323 each made unambiguous. Adding it later is additive; removing it would
 *       not be.
 *       <p>This is why {@link #close} and {@link #collect} are <strong>uninterruptible</strong>.
 *       An earlier draft restored the interrupt flag and returned a synthesized failure; that
 *       made one interrupt fabricate an {@code ERROR} outcome for every child, all of which
 *       then finished normally — and on an owned executor the following
 *       {@code ExecutorService.close()} escalated to {@code shutdownNow()}, cancelling live
 *       children through the back door of the decision that says we never cancel.</li>
 *   <li><strong>{@link #close} waits.</strong> A run reporting {@code COMPLETED} while a
 *       child is partway through a write is the audit lie those same three changes removed.
 *       Latency is the right thing to spend.</li>
 *   <li><strong>A park does not hang the scope.</strong> A parked child's run <em>ends</em>
 *       — {@code AWAITING_APPROVAL} is a stop reason, not a suspended thread — so waiting on
 *       it returns promptly with a result whose {@code awaiting()} the caller propagates.
 *       This was the one decision the issue got wrong, having assumed a parked child was a
 *       thread to avoid blocking on.
 *       <p>Every approval any child parks on is accumulated in {@link #awaiting()} as it is
 *       awaited, so a park cannot be lost by whoever happened to collect it — {@link #close}
 *       discards the outcomes it joins, and {@code ScopeTools} renders one and drops the
 *       rest.</li>
 * </ul>
 *
 * <h2>A handle is not a trace id</h2>
 *
 * <p>{@link #start} returns {@code task-N}, minted here. It is deliberately not shaped like
 * an {@link AgentRun} id ({@code researcher#4}) because it is not one: the child's identity
 * is minted inside {@code Subagent}'s dispatch and is not returned, so a scope cannot know
 * it. Correlating a handle to a trace row is therefore not possible today. That is a real
 * gap rather than a naming choice, and shaping the handle like an id would have hidden it.
 *
 * <p><strong>Not safe to share across runs</strong>, which is the whole point of it, and why
 * tools built over one declare {@link dev.agentkit.core.tool.Tool#boundToOneRun()}.
 */
public final class AgentScope implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentScope.class);

    private final AgentRun run;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final TaskContext taskContext;
    private final Semaphore concurrency;
    private final AtomicLong tickets = new AtomicLong();
    private volatile boolean closed;

    /** Insertion-ordered so {@link #outstanding} and {@link #joinAll} report in start order. */
    private final Map<String, Started> started =
            Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * The threads currently running a task of this scope, so a task that waits on a sibling
     * can hand its permit back first. See {@link #await}.
     */
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();

    /**
     * Tasks some thread has claimed and is waiting on right now.
     *
     * <p>{@link #collect} takes its handle out of {@link #started} atomically, so exactly one
     * caller can win a task — but a claimed task is still <em>running</em>, and a
     * {@link #close} that could not see it would return having joined everything it knew
     * about. This is what it joins instead. Guarded by the {@code started} monitor, so a
     * claim and its removal are one step.
     */
    private final Set<Started> inFlight = ConcurrentHashMap.newKeySet();

    /** Every approval any child parked on, in the order the parks were observed. */
    private final List<PendingApproval> awaiting =
            Collections.synchronizedList(new ArrayList<>());

    private record Started(String subagentName, Goal goal, Future<SubagentOutcome> future) {}

    /**
     * A scope for {@code run}, owning a virtual-thread executor it shuts down on
     * {@link #close}.
     *
     * @param run the run the parent agent is executing under, not a freshly minted one
     * @param maxConcurrent how many may run at once, or {@code 0} for no limit
     */
    public static AgentScope forRun(AgentRun run, int maxConcurrent) {
        return new AgentScope(run, Executors.newVirtualThreadPerTaskExecutor(), true,
                TaskContext.NONE, maxConcurrent);
    }

    /** As {@link #forRun(AgentRun, int)} over a caller's executor, which it will not shut down. */
    public static AgentScope forRun(AgentRun run, ExecutorService executor,
                                    TaskContext taskContext, int maxConcurrent) {
        return new AgentScope(run, Objects.requireNonNull(executor, "executor"), false,
                Objects.requireNonNull(taskContext, "taskContext"), maxConcurrent);
    }

    private AgentScope(AgentRun run, ExecutorService executor, boolean ownsExecutor,
                       TaskContext taskContext, int maxConcurrent) {
        this.run = Objects.requireNonNull(run, "run");
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.taskContext = taskContext;
        if (maxConcurrent < 0) {
            throw new IllegalArgumentException("maxConcurrent must be >= 0");
        }
        this.concurrency = maxConcurrent > 0 ? new Semaphore(maxConcurrent) : null;
    }

    /**
     * Starts {@code subagent} on {@code goal} and returns a handle to collect it by.
     *
     * <p>The parent identity is read here, on the calling thread, which is what makes the
     * trace a tree even though the work is deferred.
     *
     * @throws IllegalStateException if the scope is closed — a closed scope has already made
     *     its "nothing of mine is still running" guarantee, and work accepted afterwards
     *     would outlive the run rather than the call. Thrown rather than submitted-and-lost
     *     because an injected executor would happily run it with nobody left to join it.
     */
    public String start(Subagent subagent, Goal goal) {
        Objects.requireNonNull(subagent, "subagent");
        Objects.requireNonNull(goal, "goal");
        String handle = "task-" + tickets.incrementAndGet();
        Callable<SubagentOutcome> work = () -> runOne(subagent, goal);
        Callable<SubagentOutcome> submitted;
        try {
            submitted = taskContext.wrap(work);
        } catch (RuntimeException contextFailed) {
            // A wrapper that throws is this task's failure, not the run's -- Supervisor
            // takes the same line, and for the same reason.
            log.warn("TaskContext threw while starting '{}'", subagent.name(), contextFailed);
            submitted = work;
        }
        // The refusal and the submit are one step, under the monitor close() sets the flag
        // with. Checked-then-submitted was a window rather than a fix: close() could set the
        // flag, join an empty map and return in it, and the child then ran anyway -- left
        // outstanding forever on an injected executor, or rejected out of `submit` on an
        // owned one, which are precisely the two failures this refusal exists to prevent.
        synchronized (started) {
            if (closed) {
                throw new IllegalStateException("this scope for run '" + run.id()
                        + "' is closed, so '" + subagent.name() + "' was not started: work"
                        + " started now would outlive the run that owns the scope.");
            }
            started.put(handle, new Started(subagent.name(), goal, executor.submit(submitted)));
        }
        return handle;
    }

    /** One delegation, with a failure turned into an outcome rather than a thrown exception. */
    private SubagentOutcome runOne(Subagent subagent, Goal goal) {
        acquire();
        workers.add(Thread.currentThread());
        try {
            return new SubagentOutcome(subagent.name(), goal, subagent.handle(goal, run));
        } catch (RuntimeException failed) {
            return new SubagentOutcome(subagent.name(), goal, AgentResult.failed(failed, 0));
        } finally {
            workers.remove(Thread.currentThread());
            release();
        }
    }

    private void acquire() {
        if (concurrency != null) {
            concurrency.acquireUninterruptibly();
        }
    }

    private void release() {
        if (concurrency != null) {
            concurrency.release();
        }
    }

    /**
     * Waits for {@code handle} and returns its outcome, or empty if no such handle is
     * outstanding — because it was never started, or has already been collected.
     *
     * <p>Empty rather than a throw: the handle reaches this from a model argument by way of
     * {@code ScopeTools}, and a model that collects twice is choosing badly rather than
     * breaking an invariant. The tool turns empty into an error result it can act on.
     *
     * <p>The claim is atomic, and a claimed task moves to {@link #inFlight} rather than
     * vanishing. Reading the handle and removing it afterwards let two callers both win it:
     * both returned the outcome, and both recorded its park, so one person was asked twice
     * for one decision. Removing it without the hand-off had the opposite fault — a
     * concurrent {@link #close} could not see the task and returned without waiting for it.
     */
    public Optional<SubagentOutcome> collect(String handle) {
        Started work;
        synchronized (started) {
            work = started.remove(handle);
            if (work == null) {
                return Optional.empty();
            }
            inFlight.add(work);
        }
        try {
            return Optional.of(await(work, true));
        } finally {
            inFlight.remove(work);
        }
    }

    /** Handles started and not yet collected, in start order. */
    public List<String> outstanding() {
        synchronized (started) {
            return List.copyOf(started.keySet());
        }
    }

    /**
     * What each outstanding handle is working on, for a report a person reads, in start
     * order — {@code Map.copyOf} would not keep it, and {@code running_tasks} renders
     * straight from this.
     */
    public Map<String, String> outstandingWork() {
        Map<String, String> summary = new LinkedHashMap<>();
        synchronized (started) {
            started.forEach((handle, work) -> summary.put(handle, work.subagentName()));
        }
        return Collections.unmodifiableMap(summary);
    }

    /**
     * Waits for everything outstanding and returns it, leaving the scope empty.
     *
     * <p>Called by {@link #close}, and callable directly by a runner that wants the outcomes
     * rather than only the guarantee that nothing is still running.
     */
    public List<SubagentOutcome> joinAll() {
        List<SubagentOutcome> collected = new ArrayList<>();
        while (true) {
            String handle = null;
            Started claimedByAnother = null;
            // Re-read the first outstanding handle each time rather than iterating a
            // snapshot: a collected task may start another before this loop ends, and a
            // scope that returned while one was still running would be the thing close()
            // exists to prevent.
            synchronized (started) {
                var handles = started.keySet().iterator();
                if (handles.hasNext()) {
                    handle = handles.next();
                } else {
                    var claimed = inFlight.iterator();
                    if (!claimed.hasNext()) {
                        break;
                    }
                    claimedByAnother = claimed.next();
                }
            }
            if (handle != null) {
                collect(handle).ifPresent(collected::add);
                continue;
            }
            // Somebody else claimed this one and is waiting on it. Wait too -- close() must
            // not return while it runs -- but do not record it: its collector returns the
            // outcome and records the park, and doing it twice would ask one person twice.
            await(claimedByAnother, false);
        }
        return List.copyOf(collected);
    }

    /**
     * Waits for one task, uninterruptibly, and records any park it came back with.
     *
     * <p>Uninterruptibly for the reason in this class's first decision: there is no way to
     * stop a child, so returning early on an interrupt would report an outcome the child had
     * not reached. The flag is re-asserted before returning so the caller still sees it.
     *
     * <p>A task that waits on a sibling gives its permit back first. Holding it across the
     * wait deadlocks a bounded scope on exactly the case {@link #joinAll}'s comment describes
     * — a running task starting another and collecting it — and
     * {@code acquireUninterruptibly} means nothing can break it out.
     */
    private SubagentOutcome await(Started work, boolean record) {
        boolean nested = concurrency != null && workers.contains(Thread.currentThread());
        if (nested) {
            release();
        }
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return record ? recording(work.future().get()) : work.future().get();
                } catch (InterruptedException wait) {
                    interrupted = true;
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                    SubagentOutcome outcome = new SubagentOutcome(work.subagentName(),
                            work.goal(), AgentResult.failed(cause, 0));
                    return record ? recording(outcome) : outcome;
                }
            }
        } finally {
            if (nested) {
                acquire();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private SubagentOutcome recording(SubagentOutcome outcome) {
        if (outcome.awaitsAPerson()) {
            awaiting.addAll(outcome.result().awaiting());
        }
        return outcome;
    }

    /**
     * Every approval a child of this scope has parked on, whoever collected it.
     *
     * <p>The other half of the park decision. A runner puts these on the parent's own result
     * so one person sees one list, and it is an accumulator rather than a scan because the
     * outcome that carries a park is routinely discarded: {@link #close} drops what it joins,
     * and {@code collect_task} renders one outcome to a model and keeps none of it.
     */
    public List<PendingApproval> awaiting() {
        synchronized (awaiting) {
            return List.copyOf(awaiting);
        }
    }

    /**
     * Every approval the given outcomes are waiting on, in the order they were given — for a
     * caller holding outcomes from {@link #joinAll} that wants only those.
     *
     * <p>{@link #awaiting()} is the one to reach for otherwise: it cannot miss a park that
     * some other caller collected.
     */
    public static List<PendingApproval> parked(List<SubagentOutcome> outcomes) {
        List<PendingApproval> waiting = new ArrayList<>();
        for (SubagentOutcome outcome : outcomes) {
            if (outcome.awaitsAPerson()) {
                waiting.addAll(outcome.result().awaiting());
            }
        }
        return List.copyOf(waiting);
    }

    /**
     * Waits for everything outstanding, then releases the executor if this scope owns it.
     *
     * <p>Refuses further {@link #start} calls first, so a child that starts work as it
     * finishes cannot keep the join going indefinitely. Whatever parks the joined outcomes
     * carried are in {@link #awaiting()}, which outlives this.
     */
    @Override
    public void close() {
        synchronized (started) {
            closed = true;
        }
        joinAll();
        if (ownsExecutor) {
            // The flag is set aside across this and restored after. ExecutorService.close()
            // calls awaitTermination, which throws at once when an interrupt is already
            // pending, and its handler answers with shutdownNow() -- interrupting children,
            // which is the one thing the first decision says this class never does. joinAll
            // has already joined everything by here, so there is nothing left to cancel in
            // the ordinary case; this makes that true rather than probable.
            boolean interrupted = Thread.interrupted();
            try {
                executor.close();
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
