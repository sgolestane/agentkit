package dev.agentkit.core.util;

import java.util.Objects;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatching to somebody else's observer, so that instrumentation cannot decide whether a
 * run completes (#166).
 *
 * <h2>What went wrong without it</h2>
 *
 * <p>{@code Agent} called its observer in eight places and guarded none of them.
 * {@code runTool} has a {@code catch (RuntimeException)} whose comment reads "A thrown
 * gate/confirmation handler or tool must not abort the run" — and the observer calls sit
 * outside it, including {@code onToolResult}, which runs <em>after</em> the tool has.
 *
 * <p>Measured, with an observer doing exactly what the itops audit observer did —
 * {@code Map.copyOf} on arguments carrying a JSON null:
 *
 * <pre>
 * ESCAPED Agent.run: java.lang.NullPointerException
 * tool actually ran : true
 * audit rows written: []
 * onFinish called   : false
 * </pre>
 *
 * <p>So the privileged action happened, nothing recorded it, the caller got an exception
 * rather than an {@code AgentResult}, and any observer holding per-run state was left with
 * a run it will never see the end of. An audit observer is the one most likely to exist in
 * a deployment that cares and the one most likely to touch attacker-influenced data, which
 * makes "the observer threw" and "there is no record of what just happened" the same event.
 *
 * <h2>Who is told, and why it is not the result</h2>
 *
 * <p>Swallowing is right — a reporting seam must not fail a run. Swallowing with nobody
 * told is not, because the party that can do something about a missing audit row is the
 * audit component, and it is already in the frame where the failure happened.
 *
 * <p>So the failure goes to a handler the deployment supplies, carrying <em>which</em>
 * callback failed and <em>what</em> it threw. An {@code AuditObserver} can write the row it
 * failed to write, naming the call. A count on {@code AgentResult} — which this class
 * carried for one draft — could not: it said "some number of somethings failed", which is
 * what the log already said, and it shipped with no consumer reading it.
 *
 * <p>Two measurements killed that draft. {@code onFinish} is where an audit observer writes
 * its closing record, and its failure cannot appear in a result that already exists — so
 * the field could not answer the one question it was added for. And {@code onTextDelta}
 * fires once per streamed chunk: a run with 800 deltas and a throwing observer reported 800
 * failures, next to which one missing audit row also counted 1. A handler has neither
 * problem, because the deployment decides what a given callback's failure is worth.
 *
 * <h2>Not a policy seam</h2>
 *
 * <p>An observer that wants to <em>refuse</em> something is in the wrong place — that is a
 * {@code ToolGate}, which is asked before the tool runs and whose refusal is a result the
 * model is told about. Nothing here can stop a call, and an observer written as though it
 * could now fails without stopping anything.
 */
public final class Observations {

    private static final Logger LOG = LoggerFactory.getLogger(Observations.class);

    /**
     * What happens to a failed callback when the deployment has not said otherwise.
     *
     * <p>A log line, which is the honest default: the framework cannot know whether a
     * missing {@code onTextDelta} matters and a missing {@code onToolResult} does not.
     */
    public static final BiConsumer<String, Throwable> LOGGING = (callback, failure) ->
            // Quoted, because the callback name is ours but the failure is somebody else's
            // and its message can carry anything a tool or a remote service put in it.
            LOG.warn("Observer {} threw; the run continues", Quoted.of(callback),
                    Quoted.failure(failure));

    private Observations() {
    }

    /**
     * Runs {@code dispatch}, absorbing anything it throws and telling {@code onFailure}.
     *
     * <p>{@link Throwable} and not {@link RuntimeException}: an observer allocating per call
     * is where a {@code StackOverflowError} turns up, and a broken observer class is exactly
     * a {@code NoClassDefFoundError}. Catching only {@code RuntimeException} would leave
     * those doing what they did before, which is taking the run down.
     *
     * <p>This is the first place in this repository that absorbs a {@code Throwable} outright
     * — {@code TracingToolGate}, {@code TracingTool} and {@code TracingLlmClient} rethrow
     * after recording, and {@code AgentGraph.runNode} turns it into a failed result. The
     * difference is that those are on the path of the work; this one is not on any path at
     * all, so there is no result for it to fail and nothing downstream that changes.
     *
     * <p>{@code onFailure} is itself guarded, and only logged if it throws. A handler that
     * could abort a run would put the defect back one level up, which is the entire point of
     * this class.
     *
     * <p>An interrupt carried inside the absorbed failure is put back on the thread before
     * anything else happens — see {@link #restoreInterruptIfLost}. Absorbing without that
     * would silently disarm cancellation.
     *
     * <p>One caveat about the default handler on the streaming path: {@code onTextDelta}
     * fires once per chunk, so an observer that always throws there produces one log line
     * per chunk — hundreds for a long turn. A deployment that streams and cares should pass
     * a handler that samples. The framework does not sample on its behalf, because it cannot
     * tell a broken observer from a noisy one.
     *
     * @param callback the method being dispatched, for the handler and the log — a reader
     *     needs to know that {@code onToolResult} failed rather than that "an observer" did
     */
    public static void ran(String callback, Runnable dispatch,
                           BiConsumer<String, Throwable> onFailure) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(dispatch, "dispatch");
        Objects.requireNonNull(onFailure, "onFailure");
        try {
            dispatch.run();
        } catch (Throwable failed) {
            restoreInterruptIfLost(failed);
            try {
                onFailure.accept(callback, failed);
            } catch (Throwable handlerFailed) {
                LOG.warn("The handler for a failed observer callback {} threw as well; both"
                        + " are absorbed", Quoted.of(callback), Quoted.failure(handlerFailed));
            }
        }
    }

    /**
     * Re-sets the interrupt flag when the absorbed failure was a lost cancellation.
     *
     * <p>Absorbing is otherwise a cancellation regression, and a quiet one. An
     * {@code AgentObserver} cannot declare {@code InterruptedException}, so an observer
     * doing blocking work has no choice but to wrap it — and {@code Thread.sleep} has
     * already cleared the flag by the time it throws. Measured before this: flag {@code true}
     * going in, the wrapper absorbed, flag {@code false} coming out. The run would then keep
     * calling the model and running tools after its caller had cancelled it.
     *
     * <p>Before #166 that wrapper escaped {@code Agent.run} and surfaced at whatever was
     * waiting. That was an accident of exception propagation rather than a contract —
     * {@code Agent.run} checks the flag nowhere — but it is the accident that made
     * cancellation work, and absorbing it without this would take it away.
     *
     * <p>The walk is bounded, because the chain belongs to somebody else and a self-
     * referencing cause is a hang rather than a wrong answer. The idiom is the one this
     * repository already uses five times, in {@code Supervisor}, {@code AgentGraph} and
     * {@code RetryingLlmClient}.
     */
    private static void restoreInterruptIfLost(Throwable failed) {
        for (Throwable at = failed; at != null && at != at.getCause(); at = at.getCause()) {
            if (at instanceof InterruptedException
                    || at instanceof java.nio.channels.ClosedByInterruptException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
