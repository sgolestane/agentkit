package dev.agentkit.core.concurrent;

import java.util.concurrent.Callable;

/**
 * Carries a caller's ambient thread-local context across a thread handoff.
 *
 * <p>AgentKit's parallel primitives — {@code Supervisor.fanOut}, {@code AgentGraph} —
 * submit work to an executor, and thread-locals do not survive that. For tracing that is
 * not a cosmetic problem: OpenTelemetry keeps the current span in a thread-local, so a
 * supervisor's fan-out arrives at a backend as one detached trace per subagent instead of
 * one tree, and the run that started them is nowhere in it.
 *
 * <pre>{@code
 * Supervisor supervisor = Supervisor.builder(roster)
 *         .taskContext(telemetry.taskContext())   // agentkit-otel
 *         .build();
 * }</pre>
 *
 * <p>{@code agentkit-core} depends on no tracing library, so this is a seam rather than
 * an implementation. {@code agentkit-otel} supplies one; anything else with a
 * thread-local worth propagating — an MDC, a security principal, a tenant id — can supply
 * its own in a few lines, which is the reason this exists as a type at all. Decorating
 * the {@code ExecutorService} instead would work for tracing, but it would let an
 * implementation return a <em>different</em> executor (quietly changing what
 * {@code maxConcurrency} means), it would hand out a wrapper that can shut down a pool
 * these primitives promise not to own, and it would make the non-tracing cases above cost
 * a fifteen-method delegate instead of six lines.
 *
 * <h2>Implementing one</h2>
 *
 * <ul>
 *   <li><strong>Capture on the calling thread</strong>, inside {@link #wrap}, and restore
 *       inside the returned task. A {@code wrap} that reads the context when the task
 *       <em>runs</em> reads the worker's context and propagates nothing — which looks like
 *       it works, because the task still runs.</li>
 *   <li><strong>Restore what was there</strong> when the task finishes, in a
 *       {@code finally}. Pool threads are reused, and a context left behind leaks into
 *       whatever runs next on that thread.</li>
 *   <li><strong>Be thread-safe.</strong> One instance is shared, and a graph reused across
 *       concurrent runs calls {@code wrap} from several threads at once.</li>
 *   <li><strong>Return non-null, and do not throw.</strong> Both primitives treat a
 *       failure here as that unit's failure rather than the whole run's, but an
 *       implementation that throws turns a wrapper into a source of errors, which is not
 *       what anyone wants from a decorator.</li>
 * </ul>
 *
 * <p>Note it is not a strict superset of doing nothing: capturing an <em>empty</em>
 * context still replaces whatever the worker thread had. That is the right behaviour for
 * tracing — the alternative is a span attaching to an unrelated leftover — but it means
 * this setting can change what a pool thread sees even when nothing was current at submit.
 */
public interface TaskContext {

    /** Propagates nothing. The default everywhere, and free. */
    TaskContext NONE = new TaskContext() {
        @Override
        public <T> Callable<T> wrap(Callable<T> task) {
            return task;
        }

        @Override
        public String toString() {
            return "TaskContext.NONE";
        }
    };

    /**
     * Returns a task that runs {@code task} with the calling thread's context restored.
     *
     * <p>Called on the thread doing the submitting, so "the calling thread" is the one
     * whose context should be carried. Never returns {@code null}.
     */
    <T> Callable<T> wrap(Callable<T> task);
}
