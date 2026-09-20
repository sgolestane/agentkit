package dev.agentkit.core.deferred;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.DeclaredTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs deferred actions when their time comes.
 *
 * <p>Each sweep claims every due action, resolves its subject as it is now, and runs its goal on an agent
 * with the restricted tools and the subject gate from {@link DeferredActions}. The gate is also checked inside each tool
 * ({@link DeferredActions#guard}), so an {@link ActionAgent} that does not install it still cannot act beyond the
 * subject. The store records how each ended; it runs an action at least once, not exactly once (see
 * {@link DeferredActionStore}). A sweep can be triggered directly ({@link #runDue()}), which is how tests drive
 * it, or on a timer ({@link #start(Duration)}).
 */
public final class DeferredRunner implements AutoCloseable {

    /** Runs one deferred action's goal with the tools and gate it is allowed. */
    @FunctionalInterface
    public interface ActionAgent {
        AgentResult run(Goal goal, DeclaredTools tools, ToolGate gate);
    }

    /** Something to do on every sweep besides running actions, such as a backstop. */
    @FunctionalInterface
    public interface SweepHook {
        void afterSweep(Instant now);
    }

    private static final Logger LOG = LoggerFactory.getLogger(DeferredRunner.class);

    private final DeferredActionStore store;
    private final SubjectResolver resolver;
    private final DeclaredTools tools;
    private final ActionAgent agent;
    private final Supplier<Instant> clock;
    private final SweepHook hook;
    private final Object timerLock = new Object();
    private ScheduledExecutorService timer;

    /**
     * @param tools every tool a deferred action could be given; each run gets only those
     *              {@link DeferredActions#restrict} allows
     */
    public DeferredRunner(DeferredActionStore store, SubjectResolver resolver, DeclaredTools tools, ActionAgent agent,
                          Supplier<Instant> clock, SweepHook hook) {
        this.store = Objects.requireNonNull(store, "store");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.hook = hook == null ? now -> { } : hook;
    }

    /** Runs every due action now, then the sweep hook; returns how many actions ran. */
    public synchronized int runDue() {
        Instant now = clock.get();
        int ran = 0;
        for (DeferredAction action : store.due(now)) {
            try {
                if (!store.claim(action.id(), now)) {
                    continue;
                }
            } catch (RuntimeException e) {
                // Still scheduled: the next sweep tries again.
                LOG.warn("Could not claim deferred action {}", action.id(), e);
                continue;
            }
            ran++;
            try {
                Optional<SubjectRecord> subject = resolver.resolve(action.subjectKind(), action.subjectId());
                if (subject.isEmpty()) {
                    finish(action, false, "The " + action.subjectKind() + " " + action.subjectId()
                            + " no longer exists, so nothing was done.");
                    continue;
                }
                DeclaredTools allowed = DeferredActions.restrict(tools);
                ToolGate gate = DeferredActions.gateFor(action, subject.get(), allowed);
                AgentResult result = agent.run(DeferredActions.goalFor(action, subject.get(), now),
                        DeferredActions.guard(allowed, gate), gate);
                finish(action, result.isSuccess(), result.output());
                LOG.info("Deferred action {} finished {}: {}", action.id(), result.stopReason(), result.output());
            } catch (Throwable e) {
                finish(action, false, "Failed: " + e.getMessage());
                LOG.warn("Deferred action {} failed", action.id(), e);
                if (e instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
            }
        }
        try {
            hook.afterSweep(clock.get());
        } catch (RuntimeException e) {
            LOG.warn("Deferred runner sweep hook failed", e);
        }
        return ran;
    }

    /** Records how an action ended; a failure to record it is logged, and the store runs it again after a restart. */
    private void finish(DeferredAction action, boolean succeeded, String outcome) {
        try {
            store.finish(action.id(), succeeded, outcome, clock.get());
        } catch (RuntimeException e) {
            LOG.error("Could not record how deferred action {} ended ({}); it will run again after a restart",
                    action.id(), succeeded ? "succeeded" : "failed", e);
        }
    }

    /**
     * Sweeps every {@code every}, until closed. A sweep that throws is logged and the next one still runs.
     *
     * @throws IllegalArgumentException if {@code every} is not positive
     */
    public void start(Duration every) {
        Objects.requireNonNull(every, "every");
        if (every.isNegative() || every.isZero()) {
            throw new IllegalArgumentException("A deferred runner sweeps at a positive interval, not " + every);
        }
        synchronized (timerLock) {
            if (timer != null) {
                return;
            }
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "deferred-runner");
                thread.setDaemon(true);
                return thread;
            });
            timer.scheduleWithFixedDelay(() -> {
                try {
                    runDue();
                } catch (Throwable e) {
                    LOG.error("Deferred runner sweep failed", e);
                    if (e instanceof VirtualMachineError fatal) {
                        throw fatal;
                    }
                }
            }, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Stops the timer without waiting for a sweep in progress, which is interrupted. */
    @Override
    public void close() {
        synchronized (timerLock) {
            if (timer != null) {
                timer.shutdownNow();
                timer = null;
            }
        }
    }
}
