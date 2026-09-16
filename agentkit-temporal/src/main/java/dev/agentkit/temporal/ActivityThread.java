package dev.agentkit.temporal;

import dev.agentkit.core.util.Quoted;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rule about the interrupt flag on an activity thread, in one place (#136).
 *
 * <p>An activity method here returns a value on every path — a {@code ToolOutcome}, an
 * {@code LlmTurn} — rather than throwing. So a flag left set by whatever the activity called
 * survives into Temporal's own completion call, that call is interrupted, the attempt is
 * never recorded, the server re-delivers the task, and <strong>the body runs again</strong>.
 * Measured with a tool that increments a counter, sets the flag and throws:
 *
 * <pre>
 * executions=3 | output=done
 * </pre>
 *
 * <p>Three executions of a tool declaring {@code SideEffects.EXTERNAL}, under a run that
 * reported success. That is #103's failure — one model-proposed call, more than one
 * execution — reached by a route no {@code catch} can see, because the damage is done by the
 * <em>flag</em> and not by a throwable. Both catches in {@code ToolActivitiesImpl} were
 * reached and both returned normally, instrumented and measured; nothing failed.
 *
 * <p><strong>One class rather than a method on each impl.</strong> Both activity
 * implementations run user-supplied code — a {@code Tool} and an {@code LlmClient} — and
 * {@code TemporalAgent.register} puts them on the same worker, hence the same task executor
 * and the same pooled threads. Two spellings of this rule is how one of them would come to
 * lack it, which is what happened: the first version of #136's fix guarded the tool activity
 * and left the model activity, whose client is <em>first-party</em> code that interrupts —
 * {@code RetryingLlmClient} and {@code JdkHttpTransport} both call
 * {@code Thread.currentThread().interrupt()} when their sleep or send is interrupted.
 *
 * <h2>Why clearing does not swallow a cancellation</h2>
 *
 * <p>#136 gave this as the reason not to do it — "Temporal uses thread interruption to cancel
 * activities" — and for this module the question does not arise: <strong>nothing here ever
 * heartbeats.</strong> A server-requested cancellation reaches an activity by
 * {@link io.temporal.activity.ActivityExecutionContext#heartbeat} throwing — an
 * {@link io.temporal.client.ActivityCompletionException}, whose subtype depends on the
 * cancellation type: {@code ActivityCanceledException} under
 * {@code WAIT_CANCELLATION_COMPLETED}, and {@code ActivityNotExistsException} under
 * {@code TRY_CANCEL}, which is the default and what {@code AgentWorkflowImpl} uses. Measured
 * across both, and across a start-to-close timeout and a graceful shutdown: the flag is never
 * how it arrives.
 *
 * <p>And no code in {@code agentkit-temporal} calls {@code heartbeat} or
 * {@code Activity.getExecutionContext()} at all, so no cancellation can reach the flag here,
 * because none can reach the activity.
 *
 * <p>The SDK does interrupt activity threads in one situation, and it is not cancellation:
 * worker shutdown. {@code PollTaskExecutor.shutdown} routes to
 * {@code ShutdownManager.shutdownExecutorNowUntimed}, which calls
 * {@code ExecutorService.shutdownNow()} — so the interrupt arrives without any
 * {@code Thread.interrupt()} call site to find by searching, which is worth saying because
 * an earlier version of this comment concluded "no SDK code on the activity-execution path
 * interrupts the activity thread" from exactly that search.
 *
 * <p>Clearing neither helps nor hurts on shutdown, and an earlier version of this comment
 * claimed it helped. Measured at {@code WorkerFactory.shutdownNow()}, guarded and unguarded:
 *
 * <pre>
 * both: WARN ActivityWorker - Failure during reporting of activity result to the server
 *       java.util.concurrent.CancellationException: The gRPC request was cancelled
 * </pre>
 *
 * <p>The channel is going down too, so the completion call dies either way. The reason to
 * clear is the ordinary case, not that one.
 *
 * <h2>Why the flag is read before as well as after</h2>
 *
 * <p>{@link #arrivesInterrupted()} exists so the warning does not accuse the wrong party. A
 * thread can arrive already interrupted — from a shutdown, or as litter from a previous task
 * on the same pooled thread — and a guard that only looks afterwards blames whatever ran this
 * time. Measured, with a tool that never touches the flag on a thread interrupted before the
 * call: {@code WARN Tool 'publish' left the interrupt flag set}, naming a tool that had not.
 *
 * <p><strong>The opposite rule, one module over.</strong>
 * {@code Observations.restoreInterruptIfLost} <em>re-sets</em> a flag that an observer
 * swallowed (#166). Not a contradiction: there the interrupt belongs to whoever asked the
 * thread to stop and an observer has no business eating it; here it is litter on a pooled
 * thread the caller does not own, and carrying it out costs a duplicate side effect. Both
 * rules say the flag belongs to the thread's owner.
 *
 * <p><strong>The in-process loop is not fixed by this.</strong> Measured, one tool that sets
 * the flag and throws: {@code ran=1 flagAtLlmCall=[false, true] flagAtEnd=true} — no
 * duplicate, because nothing re-delivers, but the flag reaches the next model call and then
 * the caller. The same guard would be wrong there: {@code Agent.run} executes on the
 * <em>caller's</em> thread, where an interrupt may be the caller saying stop. That needs a
 * different answer and is #183.
 *
 * <h2>What arrives interrupted does not start (#185)</h2>
 *
 * <p>#136 cleared the flag on the way <em>out</em> and deliberately left the way in open,
 * because clearing on entry silently decides a question it had no measurement for: should a
 * tool run to completion during a worker shutdown? Measured, with a tool that never touches
 * the flag, on a thread interrupted before {@code executeTool}:
 *
 * <pre>
 * toolRan=1 toolSawInheritedFlag=true flagAfter=false isError=false disposition=RAN
 * </pre>
 *
 * <p>The body ran under a flag that was not its own, and the outcome recorded in history
 * says an ordinary successful call. Any blocking I/O inside it — an HTTP send, a
 * {@code sleep}, a queue {@code take} — fails at once for a reason that has nothing to do
 * with the work, and what the model is then told is that its <em>tool</em> failed.
 *
 * <p>{@link #refuseToStartIfArrivedInterrupted} answers the question rather than dodging
 * it, and the answer is <strong>do not start new work on a thread that has been told to
 * stop</strong>. That is the rule the in-process loop adopted in #196: {@code Agent.run}
 * checks the flag before anything an iteration would do, "because everything it would do is
 * work on a thread that has been told to stop". What differs is what "stop" can mean. In
 * process there is nowhere else for the work to go, so the run ends with
 * {@code StopReason.CANCELLED}. Here there is: an unreported activity task is re-delivered,
 * so refusing is not a loss of work but a move of it to a live worker. One rule, and the
 * durable half of it is the half that can afford to be strict.
 *
 * <p><strong>Why it throws rather than returning an outcome.</strong> #185 proposed
 * "returning an outcome that says the worker is going away", and every value this activity
 * can return is a sentence the model reads and reacts to. There is no component of
 * {@code ToolOutcome} meaning "this was not done, and should be" — a returned outcome
 * completes the activity, memoizes it in history, and the call is never made. Failing the
 * activity is the only way the durable path has of saying "not answered", and it is the
 * mechanism the durable path exists for.
 *
 * <p><strong>Retryable, and that is the point.</strong> A shutdown is the realistic source,
 * and it goes away — the next attempt is delivered to a worker that is not shutting down.
 * A worker that keeps refusing exhausts {@code toolMaxAttempts}, and
 * {@code AgentWorkflowImpl} then reports that every tool call failed at the activity level
 * and that the tool worker is likely unavailable, which during a shutdown loop is the true
 * diagnosis rather than the mistaken one #103 complained about.
 *
 * <p><strong>The other source is litter, and refusing costs it one retry.</strong> A flag
 * left behind by a previous task on the same pooled thread is indistinguishable from a
 * shutdown here, and nothing in this class tries to tell them apart. It self-heals:
 * {@link #clearBeforeReturning} runs in the {@code finally} on the refusing path too, so
 * the thread goes back to the pool clean and the retry is served normally. The cost is one
 * backoff interval on a call that could have run; the alternative — running anyway — is a
 * body whose blocking I/O dies part-way through an {@code EXTERNAL} write, reported to the
 * model as a tool failure and to history as one, with no retry, because the activity
 * succeeded.
 *
 * <p><strong>Clearing on entry and running anyway was the other candidate, and was
 * rejected.</strong> It is correct for litter and free, and it is a gamble on shutdown: the
 * executor is going down, so the body has whatever remains of the grace period to finish
 * and report. A long {@code EXTERNAL} tool that loses that race has its task re-delivered
 * after the start-to-close timeout and runs a second time, which is the duplicate side
 * effect this whole class exists to prevent, arriving through the fix for it.
 */
final class ActivityThread {

    private static final Logger LOG = LoggerFactory.getLogger(ActivityThread.class);

    private ActivityThread() {
    }

    /**
     * Whether this thread was already interrupted before the activity body ran.
     *
     * <p>Read without clearing, so the answer describes the thread rather than changing it.
     */
    static boolean arrivesInterrupted() {
        return Thread.currentThread().isInterrupted();
    }

    /**
     * The activity type reported when a worker refuses to start work on an interrupted
     * thread (#185).
     *
     * <p>A name rather than a bare {@code ApplicationFailure}, because
     * {@code ApplicationFailure.getType()} is what history records and what an operator
     * greps for. It is deliberately not the class name of anything: nothing is thrown
     * <em>through</em> here, so there is no cause to take a type from.
     */
    static final String WORKER_GOING_AWAY = "dev.agentkit.temporal.WorkerGoingAway";

    /**
     * Refuses to start an activity body on a thread that arrived interrupted (#185).
     *
     * <p>Called first in every activity method, before anything is resolved, gated or sent
     * — the check is worth nothing after the work has begun. See this class's javadoc for
     * why the refusal is a throw and why it is retryable.
     *
     * @param kind      what kind of thing was about to run, in the framework's own words
     * @param name      its name, raw — quoted here, exactly once
     * @param inherited what {@link #arrivesInterrupted()} answered
     * @throws io.temporal.failure.ApplicationFailure if {@code inherited}
     */
    static void refuseToStartIfArrivedInterrupted(String kind, String name, boolean inherited) {
        if (!inherited) {
            return;
        }
        // Logged before the throw rather than left to the SDK's failure reporting, which
        // during a worker shutdown is the call that dies: ActivityThread's javadoc measures
        // the completion RPC failing with "The gRPC request was cancelled" at
        // WorkerFactory.shutdownNow(), so the operator's only reliable record of why this
        // attempt did nothing is this line.
        LOG.warn("The activity thread arrived interrupted, so {} '{}' was not started; the"
                + " attempt is failed so the task is re-delivered to a worker that is not"
                + " shutting down. Nothing ran, so nothing was done twice", kind,
                Quoted.of(name));
        // Retryable — the default, said explicitly because the default is the decision.
        // newNonRetryableFailure here would settle the call on the worker that is going
        // away, which is the one worker that cannot do it.
        throw ApplicationFailure.newFailure(
                "The worker is going away, so '" + name + "' was not started.",
                WORKER_GOING_AWAY);
    }

    /**
     * Clears the flag before the activity returns, and says whose it probably was.
     *
     * @param kind      what kind of thing ran, in the framework's own words
     * @param name      its name, raw — quoted here, exactly once
     * @param inherited what {@link #arrivesInterrupted()} answered before the body ran
     */
    static void clearBeforeReturning(String kind, String name, boolean inherited) {
        // Thread.interrupted() rather than isInterrupted(): it reports AND clears, which is
        // the whole point. A guard that only reads looks correct and does nothing, and the
        // mutant swapping one for the other is the one worth having a test for.
        if (!Thread.interrupted()) {
            return;
        }
        // Raw name in, quoted once here. An earlier version had the caller build
        // "Tool " + Quoted.of(name) and then quoted that again, which double-escaped —
        // measured, a name of we\ird\nname logged as we\\\\ird\\u000Aname, so a single
        // unescape yields a name that never existed, and Quoted's javadoc makes
        // reversibility the reason it escapes backslash at all. The concatenation also lost
        // the delimiters, so a tool named "publish left the interrupt flag set on the
        // activity thread" was indistinguishable from this method's own prose.
        try {
            if (inherited) {
                // Cleared just the same, but not blamed on whatever ran this time, which
                // had no chance to be the cause.
                LOG.warn("The activity thread running {} '{}' was already interrupted when"
                        + " the task started, and is still interrupted; cleared it before"
                        + " reporting. A worker shutting down does this, and so does a"
                        + " previous task that left the flag behind", kind, Quoted.of(name));
            } else {
                LOG.warn("{} '{}' left the interrupt flag set on the activity thread; cleared"
                        + " it before reporting, because carrying it out of the activity"
                        + " makes Temporal re-deliver the task and run the body again",
                        kind, Quoted.of(name));
            }
        } catch (Throwable loggingFailed) {
            // The flag is already cleared, which is this method's job; the warning is
            // instrumentation. #174 established the rule for exactly this shape —
            // "Instrumentation must not decide whether a run completes" — and this sits in a
            // finally, so a throwing appender here would discard a ToolOutcome and turn a
            // completed tool call into a thrown activity. Which is the duplicate execution
            // this class exists to prevent, arriving through the fix for it.
            LOG.debug("Reporting a stray interrupt flag failed", loggingFailed);
        }
    }
}
