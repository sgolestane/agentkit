package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What an activity does when its thread arrives already interrupted (#185).
 *
 * <h2>The defect</h2>
 *
 * <p>#136 clears the interrupt flag on the way <em>out</em> of an activity, which is what
 * stops Temporal's completion call from breaking and the body from being re-delivered. It
 * did not clear it on the way <strong>in</strong>, so a body that started on an
 * already-interrupted thread ran under a flag that was not its own. Measured, with a tool
 * that never touches the flag, on a thread interrupted before {@code executeTool}:
 *
 * <pre>
 * toolRan=1 toolSawInheritedFlag=true flagAfter=false isError=false disposition=RAN
 * </pre>
 *
 * <p>The body ran with the flag set, and the outcome written to history says an ordinary
 * successful call. Any blocking I/O inside it — an HTTP send, a {@code sleep}, a queue
 * {@code take} — fails at once for a reason unrelated to the work, and what the model is
 * then told is that its <em>tool</em> failed. The two realistic sources are a worker
 * shutdown, which reaches the thread through {@code ExecutorService.shutdownNow()} with no
 * {@code Thread.interrupt()} call site to find by searching, and litter from a previous task
 * on the same pooled thread.
 *
 * <h2>What was decided</h2>
 *
 * <p>Do not start new work on a thread that has been told to stop — the rule {@code
 * Agent.run} adopted in #196, whose flag check sits before anything an iteration would do.
 * What differs is what "stop" can mean: in process there is nowhere else for the work to go
 * and the run ends {@code CANCELLED}; here an unreported activity task is re-delivered, so
 * refusing moves the work to a live worker rather than losing it.
 * {@link #theToolRunsOnceOnTheWorkerThatIsNotGoingAway} is that end to end.
 *
 * <p>{@code ActivityThread}'s javadoc carries the argument for why the refusal is a throw
 * rather than a returned outcome, and why clearing on entry and running anyway was measured
 * and rejected.
 */
class DurableArrivesInterruptedTest {

    private static final String TASK_QUEUE = "agentkit-arrives-interrupted-test";

    private static final ToolInvocation CALL =
            new ToolInvocation("t1", "publish", Map.of("text", "the payload"));

    private static final ApprovalVerdict APPROVED = new ApprovalVerdict("tkt-1", "t1",
            ApprovalDecision.Kind.APPROVE, "ok", null, "alice");

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    /** A tool that records whether it ran and what flag it found on the way in. */
    private static FunctionTool observer(AtomicInteger ran, boolean[] sawFlag) {
        return FunctionTool.builder("publish", "Publishes text somewhere public")
                .schema(Map.of("type", "object",
                        "properties", Map.of("text", Map.of("type", "string")),
                        "required", List.of("text")))
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> {
                    ran.incrementAndGet();
                    sawFlag[0] = Thread.currentThread().isInterrupted();
                    return ToolResult.ok("published");
                })
                .build();
    }

    /** What one activity call did on a thread interrupted before it, and what it left. */
    private record OnADirtyThread(Throwable escaped, boolean flagAfter) {}

    /**
     * Runs {@code call} on a thread of this test's own, interrupted first.
     *
     * <p>A thread of its own because the whole assertion is about the state a call leaves on
     * whatever thread ran it, and leaving JUnit's own thread interrupted would leak into
     * whatever runs next — the same reason {@code DurableInterruptTest} does this.
     */
    private static OnADirtyThread onADirtyThread(Runnable call) throws Exception {
        Throwable[] escaped = new Throwable[1];
        boolean[] flagAfter = new boolean[1];
        Thread caller = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                call.run();
            } catch (Throwable t) {
                escaped[0] = t;
            }
            flagAfter[0] = Thread.currentThread().isInterrupted();
        });
        caller.start();
        caller.join();
        return new OnADirtyThread(escaped[0], flagAfter[0]);
    }

    @Test
    void theBodyDoesNotRunUnderAFlagItInherited() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        boolean[] sawFlag = new boolean[1];
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(observer(ran, sawFlag)));

        OnADirtyThread measured = onADirtyThread(() -> activities.executeTool(CALL));

        assertThat(ran.get())
                .as("the tool body ran on a thread that had been told to stop, so its own"
                        + " blocking I/O could fail for a reason that is not about its work")
                .isZero();
        assertThat(sawFlag[0]).isFalse();
        assertThat(measured.escaped())
                .as("the activity completed rather than failing, so the task was memoized"
                        + " and the call is never made")
                .isInstanceOf(ApplicationFailure.class);
        assertThat(((ApplicationFailure) measured.escaped()).getType())
                .isEqualTo(ActivityThread.WORKER_GOING_AWAY);
    }

    @Test
    void theRefusalTypeIsPinnedBecauseHistoryIsWhatRecordsIt() throws Exception {
        // ApplicationFailure.getType() is written into Temporal history as the failure's
        // type, so it is a persisted name an operator greps a stalled run for — and it is
        // the seam a future workflow branch would match on, which is the shape #129
        // describes for a distinguishable activity failure.
        //
        // Asserted against the literal rather than against the constant, which is the
        // repository's rule for a name the wire carries (see
        // DurableJsonTest.theBudgetWireShapeIsPinnedAgainstARenameInCore). The mutation
        // pass measured why: changing the constant survived the whole module, because every
        // other assertion reads the type back through it. Fix the rename, do not update
        // this string.
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(observer(new AtomicInteger(), new boolean[1])));

        OnADirtyThread measured = onADirtyThread(() -> activities.executeTool(CALL));

        assertThat(((ApplicationFailure) measured.escaped()).getType())
                .isEqualTo("dev.agentkit.temporal.WorkerGoingAway");
    }

    @Test
    void theRefusalIsRetryableBecauseTheWorkerGoingAwayIsTheOneThatCannotDoIt() throws Exception {
        // The mutant is one word: newNonRetryableFailure instead of newFailure. It settles
        // the call on the worker that is shutting down, which is the single worker that
        // cannot serve it, and the model is then told its tool failed. Nothing else in this
        // file notices, because every other assertion here is about the first attempt.
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(observer(new AtomicInteger(), new boolean[1])));

        OnADirtyThread measured = onADirtyThread(() -> activities.executeTool(CALL));

        assertThat(((ApplicationFailure) measured.escaped()).isNonRetryable())
                .as("the refusal was marked non-retryable, so the call died on the one"
                        + " worker that could not run it")
                .isFalse();
    }

    @Test
    void theRefusedAttemptStillHandsBackACleanThread() throws Exception {
        // The refusal sits INSIDE the try whose finally clears the flag, and this is the
        // mutant that moves it out — above the try, where it reads just as naturally. The
        // refusal still has to be reported to the server, and the report is exactly the
        // call a set flag breaks (#136): a dirty thread here means the attempt is never
        // recorded, the task is re-delivered, and the refusal loop never terminates.
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(observer(new AtomicInteger(), new boolean[1])));

        OnADirtyThread measured = onADirtyThread(() -> activities.executeTool(CALL));

        assertThat(measured.flagAfter())
                .as("the refusing attempt handed the interrupt flag back to the executor's"
                        + " pool, where it breaks the very report that carries the refusal")
                .isFalse();
    }

    @Test
    void everyToolEntryPointRefusesAndNotJustTheOrdinaryOne() throws Exception {
        // The funnel claim again, and #136's own mutation pass is why it is asserted rather
        // than assumed: guarding executeTool alone passed that whole suite. The resume paths
        // matter most — a resumed call is one a person approved, and starting it on a
        // shutting-down worker spends that approval on an attempt that cannot report.
        AtomicInteger ran = new AtomicInteger();
        boolean[] sawFlag = new boolean[1];

        assertThat(onADirtyThread(() -> activities(ran, sawFlag)
                .executeToolUnderLoweredTrust(CALL)).escaped())
                .as("executeToolUnderLoweredTrust started work on a stopped thread")
                .isInstanceOf(ApplicationFailure.class);
        assertThat(onADirtyThread(() -> activities(ran, sawFlag).resumeTool(CALL, APPROVED))
                .escaped())
                .as("resumeTool started work on a stopped thread, for a call somebody had"
                        + " approved exactly once")
                .isInstanceOf(ApplicationFailure.class);
        assertThat(onADirtyThread(() -> activities(ran, sawFlag)
                .resumeToolUnderLoweredTrust(CALL, APPROVED)).escaped())
                .as("resumeToolUnderLoweredTrust started work on a stopped thread")
                .isInstanceOf(ApplicationFailure.class);
        assertThat(ran.get())
                .as("a tool body ran on one of the three, so one assertion above passed"
                        + " while measuring an entry point that had already done the work")
                .isZero();
    }

    private static ToolActivitiesImpl activities(AtomicInteger ran, boolean[] sawFlag) {
        return new ToolActivitiesImpl(new SimpleToolRegistry().register(observer(ran, sawFlag)));
    }

    @Test
    void theModelActivityRefusesTheSameWay() throws Exception {
        // The other activity on the same worker and the same pooled threads. #136's first
        // fix guarded the tool activity and left this one; the rule lives in one class so
        // that cannot happen twice, and this is what checks that it did not.
        AtomicInteger calls = new AtomicInteger();
        LlmActivitiesImpl activities = new LlmActivitiesImpl(request -> {
            calls.incrementAndGet();
            return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of("hello")),
                    LlmStopReason.END_TURN, TokenUsage.ZERO);
        });
        LlmCallSpec spec = new LlmCallSpec("m", null,
                List.of(Message.of(Role.USER, TextBlock.of("go"))), List.of(), 100, Map.of());

        OnADirtyThread measured = onADirtyThread(() -> activities.generate(spec));

        assertThat(calls.get())
                .as("a model call was sent from a thread that had been told to stop, where"
                        + " every first-party transport in this repository fails at once")
                .isZero();
        assertThat(measured.escaped()).isInstanceOf(ApplicationFailure.class);
        assertThat(measured.flagAfter()).isFalse();
    }

    @Test
    void aCleanThreadIsNotRefused() {
        // The negative control, and it is what stops the guard from being an inversion: a
        // one-character mutant (`if (inherited) return;`) refuses nothing and is invisible
        // to every assertion above, which all run on dirty threads.
        AtomicInteger ran = new AtomicInteger();
        boolean[] sawFlag = new boolean[1];

        ToolOutcome outcome = activities(ran, sawFlag).executeTool(CALL);

        assertThat(ran.get()).as("an ordinary call was refused").isEqualTo(1);
        assertThat(outcome.result().isError()).isFalse();
        assertThat(sawFlag[0]).isFalse();
    }

    @Test
    void theOperatorIsToldWhichThingWasNotStartedAndWhy() throws Exception {
        // The line an operator has during a shutdown, and the only reliable one: the
        // completion RPC that would carry the failure is itself dying, measured in
        // ActivityThread's javadoc as "The gRPC request was cancelled". slf4j-simple
        // resolves System.err per write, so capturing it works on a logger initialised long
        // before this call; restored in a finally, because a test that leaves System.err
        // replaced takes the rest of the suite's output with it.
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true,
                java.nio.charset.StandardCharsets.UTF_8));
        try {
            onADirtyThread(() -> activities(new AtomicInteger(), new boolean[1])
                    .executeTool(CALL));
        } finally {
            System.setErr(original);
        }

        assertThat(captured.toString(java.nio.charset.StandardCharsets.UTF_8))
                .as("nothing said what refused, or that nothing had run")
                .contains("The activity thread arrived interrupted, so Tool 'publish' was"
                        + " not started")
                .contains("re-delivered")
                .contains("Nothing ran, so nothing was done twice");
    }

    @Test
    void theToolRunsOnceOnTheWorkerThatIsNotGoingAway() {
        // The point of refusing rather than running, end to end: the work moves instead of
        // being lost or half-done. A ToolActivities that dirties its own thread on the first
        // delivery and then delegates stands in for a worker whose executor was told to shut
        // down between the poll and the body — the shape ExecutorService.shutdownNow()
        // produces, which has no Thread.interrupt() call site to script directly.
        AtomicInteger ran = new AtomicInteger();
        boolean[] sawFlag = new boolean[1];
        ToolActivitiesImpl real = activities(ran, sawFlag);
        AtomicInteger deliveries = new AtomicInteger();
        ToolActivities shuttingDownOnce = new ToolActivities() {
            @Override
            public ToolOutcome executeTool(ToolInvocation invocation) {
                if (deliveries.getAndIncrement() == 0) {
                    Thread.currentThread().interrupt();
                }
                return real.executeTool(invocation);
            }

            @Override
            public ToolOutcome executeToolUnderLoweredTrust(ToolInvocation invocation) {
                return real.executeToolUnderLoweredTrust(invocation);
            }

            @Override
            public ToolOutcome resumeTool(ToolInvocation invocation, ApprovalVerdict verdict) {
                return real.resumeTool(invocation, verdict);
            }

            @Override
            public ToolOutcome resumeToolUnderLoweredTrust(ToolInvocation invocation,
                                                           ApprovalVerdict verdict) {
                return real.resumeToolUnderLoweredTrust(invocation, verdict);
            }
        };

        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(TemporalAgent.dataConverter())
                        .build())
                .build());
        Worker worker = env.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(AgentWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new LlmActivitiesImpl(new ScriptedLlm()
                        .then(ScriptedLlm.toolUse("t1", "publish", Map.of("text", "the payload")))
                        .then(ScriptedLlm.text("done"))),
                shuttingDownOnce);
        env.start();

        AgentRunResult result = TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE)
                .run(DurableAgentRun.of(Goal.of("publish something"),
                        AgentConfig.builder("m").maxSteps(5).build(), List.of()));

        assertThat(deliveries.get())
                .as("the refused attempt was never re-delivered, so the call was simply lost")
                .isEqualTo(2);
        assertThat(ran.get())
                .as("the tool ran a number of times other than exactly once for one"
                        + " model-proposed call")
                .isEqualTo(1);
        assertThat(sawFlag[0])
                .as("the attempt that ran the tool still carried an inherited flag")
                .isFalse();
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(result.output())
                .as("the model was told a tool failure for a worker that was going away")
                .isEqualTo("done");
    }

    @Test
    void nothingIsRefusedWhenTheThreadIsClean() {
        // Guards the whole suite against the guard being a blanket refusal: a run with no
        // interrupt anywhere completes and the tool runs once.
        AtomicInteger ran = new AtomicInteger();
        boolean[] sawFlag = new boolean[1];
        SimpleToolRegistry tools = new SimpleToolRegistry().register(observer(ran, sawFlag));
        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(TemporalAgent.dataConverter())
                        .build())
                .build());
        TemporalAgent.register(env.newWorker(TASK_QUEUE), new ScriptedLlm()
                        .then(ScriptedLlm.toolUse("t1", "publish", Map.of("text", "the payload")))
                        .then(ScriptedLlm.text("done")),
                tools, dev.agentkit.core.reliability.ToolGate.ALLOW_ALL);
        env.start();

        AgentRunResult result = TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE)
                .run(DurableAgentRun.of(Goal.of("publish something"),
                        AgentConfig.builder("m").maxSteps(5).build(), tools.advertisedSpecs()));

        assertThat(ran.get())
                .as("an ordinary durable run had its tool call refused")
                .isEqualTo(1);
        assertThat(sawFlag[0]).isFalse();
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(result.output()).isEqualTo("done");
    }
}
