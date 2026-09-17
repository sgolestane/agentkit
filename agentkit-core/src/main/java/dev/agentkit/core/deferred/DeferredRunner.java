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
 * with the restricted tools and the subject gate from {@link DeferredActions}. The store records how each
 * ended. A sweep can be triggered directly ({@link #runDue()}), which is how tests and the demo clock drive
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
            if (!store.claim(action.id(), now)) {
                continue;
            }
            ran++;
            try {
                Optional<SubjectRecord> subject = resolver.resolve(action.subjectKind(), action.subjectId());
                if (subject.isEmpty()) {
                    store.finish(action.id(), false, "The " + action.subjectKind() + " " + action.subjectId()
                            + " no longer exists, so nothing was done.", clock.get());
                    continue;
                }
                DeclaredTools allowed = DeferredActions.restrict(tools);
                AgentResult result = agent.run(DeferredActions.goalFor(action, subject.get(), now), allowed,
                        DeferredActions.gateFor(action, subject.get(), allowed));
                store.finish(action.id(), result.isSuccess(), result.output(), clock.get());
                LOG.info("Deferred action {} finished {}: {}", action.id(), result.stopReason(), result.output());
            } catch (RuntimeException e) {
                store.finish(action.id(), false, "Failed: " + e.getMessage(), clock.get());
                LOG.warn("Deferred action {} failed", action.id(), e);
            }
        }
        try {
            hook.afterSweep(clock.get());
        } catch (RuntimeException e) {
            LOG.warn("Deferred runner sweep hook failed", e);
        }
        return ran;
    }

    /** Sweeps every {@code every}, until closed. */
    public synchronized void start(Duration every) {
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
            } catch (RuntimeException e) {
                LOG.warn("Deferred runner sweep failed", e);
            }
        }, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }
}
