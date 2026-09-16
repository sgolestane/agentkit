package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * How many times a tool body runs when the tool leaves the interrupt flag set (#136).
 *
 * <h2>The defect</h2>
 *
 * <p>A tool that calls {@code Thread.currentThread().interrupt()} leaves the flag set on the
 * pooled activity-executor thread. Every path in {@code ToolActivitiesImpl} returns a
 * {@code ToolOutcome} rather than throwing, so the flag survives into Temporal's own
 * completion call; that call is interrupted, the attempt is never recorded, the server
 * re-delivers the task, and the tool body runs again. Measured, with a tool that increments a
 * counter, sets the flag and throws:
 *
 * <pre>
 * ### PROBE throw=true  executions=3 | output=done
 * </pre>
 *
 * <p>Three executions of a tool declaring {@code SideEffects.EXTERNAL}, and the run reported
 * <em>success</em>. That is the thing worth fixing: #103's failure — one model-proposed call,
 * more than one execution — arriving by a route neither {@code catch} in that class can see,
 * because the damage is done by the flag rather than by the throwable. Both catches were
 * reached and both returned normally, instrumented and measured; nothing failed.
 *
 * <p>#136 records this as "executions=2, run dies". The run does not die, which is worse: a
 * loud failure is at least loud, and this is a duplicated external side effect under a
 * successful-looking run.
 *
 * <h2>Why the count varies, and why that is the harness rather than the bug</h2>
 *
 * <p>gRPC's blocking stub reads the flag on every call.
 * {@code ClientCalls$ThreadlessExecutor.waitAndDrain} opens with {@code throwIfInterrupted()},
 * whose body is {@code if (Thread.interrupted()) throw new InterruptedException()} — note
 * {@code Thread.interrupted()}, which clears — and {@code blockingUnaryCall} catches that,
 * cancels the call with the literal {@code "Thread interrupted"}, and then calls
 * {@code Thread.currentThread().interrupt()} in its {@code finally} to put the flag back.
 * Verified in the bytecode of {@code grpc-stub-1.54.1}.
 *
 * <p>But {@code waitAndDrain} is only entered while the response future is <em>not already
 * done</em>. Against {@code TestWorkflowEnvironment}'s in-process server the reply sometimes
 * lands first, so a dirty-flag RPC sometimes slips through, and the flag is restored each
 * time so the pooled thread stays dirty and keeps rolling. That is the whole of the observed
 * spread — the same mutant measured executions=1 (182s) and executions=3 (363s) on two
 * identical runs.
 *
 * <p>Which means the nondeterminism is an artifact of the <strong>test harness</strong>, not
 * of the defect. Against a real server over a network the response is never already-done,
 * {@code throwIfInterrupted()} always fires, and the duplication is essentially deterministic.
 * An earlier version of this file declined to attribute the variance at all, which was honest
 * but understated the bug.
 *
 * <h2>Speed as corroboration</h2>
 *
 * <p>This class took 182s before the fix and under a second after, in the same environment.
 * The missing 180s is Temporal's retry backoff, which is what was re-running the tool. The
 * deterministic tests below are what detect a regression, and they do it in milliseconds;
 * the slow end-to-end pair is corroboration that the whole path behaves, not the guard.
 *
 * <h2>Why the end-to-end tests are not the ones that pin this</h2>
 *
 * <p>The duplication is <strong>nondeterministic when the guard is absent</strong>. The same
 * mutant — the {@code finally} deleted — measured, on two identical runs:
 *
 * <pre>
 * run 1: executions=1, suite passed, 182s   &lt;- retried, but the tool was not re-entered
 * run 2: executions=3, suite failed, 363s
 * </pre>
 *
 * <p>So {@code assertThat(sideEffects).isEqualTo(1)} is a probabilistic detector, not a
 * regression test. It never flakes on <em>correct</em> code — with the flag cleared there are
 * no retries at all and the count is deterministically one — but it can miss a breakage, and
 * a test that sometimes fails to notice is not the one to rely on.
 *
 * <p>{@code theActivityDoesNotReturnWithTheFlagStillSet} is the one that pins it. It calls
 * the activity implementation directly, with no Temporal at all, and asserts the property the
 * guard actually establishes: this method does not hand a dirty thread back to its pool.
 * Everything downstream — the broken completion call, the re-delivery, the duplicate — is a
 * consequence of that one fact, and it is deterministic and takes milliseconds.
 */
class DurableInterruptTest {

    private static final String TASK_QUEUE = "agentkit-interrupt-test";

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    /**
     * A tool with a bug: it litters the interrupt flag on a thread it does not own.
     *
     * @param alsoThrows whether it also fails, which is the shape that reproduced
     */
    private static FunctionTool litterer(AtomicInteger sideEffects, boolean alsoThrows) {
        return FunctionTool.builder("publish", "Publishes text somewhere public")
                .schema(Map.of("type", "object",
                        "properties", Map.of("text", Map.of("type", "string")),
                        "required", java.util.List.of("text")))
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> {
                    sideEffects.incrementAndGet();
                    Thread.currentThread().interrupt();
                    if (alsoThrows) {
                        throw new IllegalStateException("the tool's own bug");
                    }
                    return ToolResult.ok("published");
                })
                .build();
    }

    private static ScriptedLlm callsThenStops() {
        return new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "publish", Map.of("text", "the payload")))
                .then(ScriptedLlm.text("done"));
    }

    private AgentRunResult runDurably(LlmClient llm, ToolRegistry tools) {
        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(TemporalAgent.dataConverter())
                        .build())
                .build());
        Worker worker = env.newWorker(TASK_QUEUE);
        TemporalAgent.register(worker, llm, tools,
                dev.agentkit.core.reliability.ToolGate.ALLOW_ALL);
        env.start();
        return TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE)
                .run(DurableAgentRun.of(Goal.of("publish something"),
                        AgentConfig.builder("m").maxSteps(5).build(),
                        ((SimpleToolRegistry) tools).advertisedSpecs()));
    }

    @Test
    void aToolThatInterruptsAndThrowsRunsOnce() {
        // The reproducing shape. Without the guard this measured three executions of an
        // EXTERNAL tool under a run that reported success.
        AtomicInteger sideEffects = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(litterer(sideEffects, true));

        AgentRunResult result = runDurably(callsThenStops(), tools);

        assertThat(sideEffects.get())
                .as("the tool body ran more than once for one model-proposed call, because"
                        + " the interrupt flag it left behind broke Temporal's completion"
                        + " call and the server re-delivered the task")
                .isEqualTo(1);
        assertThat(result.output()).isEqualTo("done");
    }

    @Test
    void aToolThatInterruptsAndSucceedsRunsOnce() {
        // The same litter without the throw. It measured one execution before the guard as
        // well, so this is not a regression test for a defect it caught — it is here so
        // that the guard covers the success path too, which is the path where a duplicate
        // would be a duplicate of a tool that reported it worked.
        AtomicInteger sideEffects = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(litterer(sideEffects, false));

        AgentRunResult result = runDurably(callsThenStops(), tools);

        assertThat(sideEffects.get()).isEqualTo(1);
        assertThat(result.output()).isEqualTo("done");
    }

    /** Runs one activity call on a thread of its own and reports the flag it left behind. */
    private static boolean flagLeftBehindBy(ToolActivitiesImpl activities) throws Exception {
        boolean[] flagAfter = new boolean[1];
        Throwable[] escaped = new Throwable[1];
        // A thread of this test's own, because the assertion is about the state the call
        // leaves on whatever thread ran it -- and leaving JUnit's thread interrupted would
        // leak into whatever runs next.
        Thread caller = new Thread(() -> {
            try {
                activities.executeTool(new ToolInvocation("t1", "publish",
                        Map.of("text", "the payload")));
            } catch (Throwable t) {
                escaped[0] = t;
            }
            flagAfter[0] = Thread.currentThread().isInterrupted();
        });
        caller.start();
        caller.join();
        assertThat(escaped[0])
                .as("the activity threw instead of returning an outcome, so this measured"
                        + " nothing about the flag")
                .isNull();
        return flagAfter[0];
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "tool also throws: {0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void theActivityDoesNotReturnWithTheFlagStillSet(boolean alsoThrows) throws Exception {
        // The property the guard establishes, asserted where it can be asserted: directly,
        // with no Temporal. ToolActivitiesImpl is an ordinary object and every entry point
        // funnels through the same private method, so this is the whole contract.
        //
        // BOTH variants, and the succeeding one is not padding. #136's first open question
        // was "whether the flag can be cleared only on the throwing path", and when this
        // test covered only the throwing variant that exact alternative -- the guard moved
        // from the finally into the two catch blocks -- passed the entire suite. The
        // succeeding path is also where a duplicate would be a duplicate of a tool that
        // reported it worked, which is the worse of the two.
        AtomicInteger sideEffects = new AtomicInteger();
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(litterer(sideEffects, alsoThrows)));

        boolean flagAfter = flagLeftBehindBy(activities);

        assertThat(sideEffects.get())
                .as("the tool did not run, so it never set the flag")
                .isEqualTo(1);
        assertThat(flagAfter)
                .as("the activity handed the interrupt flag back to the executor's thread"
                        + " pool, where it breaks Temporal's completion call for a body that"
                        + " has already run")
                .isFalse();
    }

    /** Every activity entry point on the tool side, and what each leaves on the thread. */
    private static boolean flagLeftBy(ToolActivitiesImpl activities,
                                      java.util.function.Consumer<ToolActivitiesImpl> call)
            throws Exception {
        boolean[] flagAfter = new boolean[1];
        Throwable[] escaped = new Throwable[1];
        Thread caller = new Thread(() -> {
            try {
                call.accept(activities);
            } catch (Throwable t) {
                escaped[0] = t;
            }
            flagAfter[0] = Thread.currentThread().isInterrupted();
        });
        caller.start();
        caller.join();
        assertThat(escaped[0])
                .as("the entry point threw instead of returning an outcome, so this measured"
                        + " nothing about the flag")
                .isNull();
        return flagAfter[0];
    }

    @Test
    void everyEntryPointGoesThroughTheGuardAndNotJustTheOrdinaryOne() throws Exception {
        // The funnel claim, pinned. All four activity methods route through one private
        // method, which is structurally true and was entirely untested: the mutant that
        // guards executeTool alone passed the whole suite in 2.9s. Measured against it:
        //
        //   resumeTool                    ran=1 flagLeft=true
        //   resumeToolUnderLoweredTrust   ran=1 flagLeft=true
        //   executeToolUnderLoweredTrust  ran=1 flagLeft=true
        //
        // Three activity entry points handing dirty threads back to the pool, and the one
        // covered test passing. The resume paths matter most of the three: a resumed call
        // is one a person approved, so a duplicate there is a duplicate of an action
        // somebody authorised exactly once.
        AtomicInteger sideEffects = new AtomicInteger();
        ToolInvocation call = new ToolInvocation("t1", "publish", Map.of("text", "payload"));
        ApprovalVerdict approved = new ApprovalVerdict("tkt-1", "t1",
                dev.agentkit.core.reliability.ApprovalDecision.Kind.APPROVE, "ok", null,
                "alice");

        assertThat(flagLeftBy(litteringActivities(sideEffects),
                a -> a.executeToolUnderLoweredTrust(call)))
                .as("executeToolUnderLoweredTrust left the flag set")
                .isFalse();
        assertThat(flagLeftBy(litteringActivities(sideEffects),
                a -> a.resumeTool(call, approved)))
                .as("resumeTool left the flag set, on a call a person had approved")
                .isFalse();
        assertThat(flagLeftBy(litteringActivities(sideEffects),
                a -> a.resumeToolUnderLoweredTrust(call, approved)))
                .as("resumeToolUnderLoweredTrust left the flag set")
                .isFalse();
        assertThat(sideEffects.get())
                .as("one of the three entry points never reached the tool, so its assertion"
                        + " passed without measuring anything")
                .isEqualTo(3);
    }

    private static ToolActivitiesImpl litteringActivities(AtomicInteger sideEffects) {
        return new ToolActivitiesImpl(
                new SimpleToolRegistry().register(litterer(sideEffects, true)));
    }

    @Test
    void theModelActivityIsGuardedTheSameWay() throws Exception {
        // The other activity on the same worker. TemporalAgent.register puts both impls on
        // one Worker, so they share a task executor and its pooled threads -- and an
        // LlmClient is user-supplied code on a thread it does not own, exactly as a Tool is.
        //
        // Not hypothetical: RetryingLlmClient and JdkHttpTransport, both first-party, call
        // Thread.currentThread().interrupt() when their sleep or send is interrupted. That
        // is correct of them and leaves the flag set here.
        //
        // The first version of #136's fix guarded the tool activity and left this one, which
        // is the "one rule, several runners" divergence the rule now lives in one class to
        // prevent.
        AtomicInteger calls = new AtomicInteger();
        LlmActivitiesImpl activities = new LlmActivitiesImpl(request -> {
            calls.incrementAndGet();
            Thread.currentThread().interrupt();
            return dev.agentkit.core.llm.LlmResponse.of(
                    dev.agentkit.core.message.Message.of(
                            dev.agentkit.core.message.Role.ASSISTANT,
                            dev.agentkit.core.message.TextBlock.of("hello")),
                    dev.agentkit.core.llm.LlmStopReason.END_TURN,
                    dev.agentkit.core.llm.TokenUsage.ZERO);
        });

        boolean[] flagAfter = new boolean[1];
        Throwable[] escaped = new Throwable[1];
        Thread caller = new Thread(() -> {
            try {
                activities.generate(new LlmCallSpec("m", null,
                        List.of(dev.agentkit.core.message.Message.of(
                                dev.agentkit.core.message.Role.USER,
                                dev.agentkit.core.message.TextBlock.of("go"))),
                        List.of(), 100, Map.of()));
            } catch (Throwable t) {
                escaped[0] = t;
            }
            flagAfter[0] = Thread.currentThread().isInterrupted();
        });
        caller.start();
        caller.join();

        assertThat(escaped[0])
                .as("the model activity threw, so this measured nothing about the flag")
                .isNull();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(flagAfter[0])
                .as("the model activity handed a dirty thread back to the pool the tool"
                        + " activity shares with it")
                .isFalse();
    }

    @Test
    void aWellBehavedToolIsNotDisturbedByTheGuard() {
        // The negative control. Its earlier rationale was wrong -- it claimed the guard
        // risked "clearing state on every call", and clearing a flag that is not set is a
        // no-op, so there was no such hazard. What it actually covers is the opposite
        // mutant: a guard that SETS the flag instead of clearing it, or that turns an
        // ordinary call into a failure. Both are cheap to get wrong in a finally.
        AtomicInteger ran = new AtomicInteger();
        ToolActivitiesImpl polite = new ToolActivitiesImpl(new SimpleToolRegistry().register(
                FunctionTool.builder("publish", "Publishes text somewhere public")
                        .schema(Map.of("type", "object"))
                        .sideEffects(SideEffects.EXTERNAL)
                        .handler(invocation -> {
                            ran.incrementAndGet();
                            return ToolResult.ok("published");
                        })
                        .build()));

        ToolOutcome outcome =
                polite.executeTool(new ToolInvocation("t1", "publish", Map.of()));

        assertThat(outcome.result().isError())
                .as("an ordinary tool call was turned into a failure by the guard")
                .isFalse();
        assertThat(ran.get()).isEqualTo(1);
        assertThat(Thread.currentThread().isInterrupted())
                .as("the guard set a flag rather than clearing one")
                .isFalse();
    }

    @Test
    void theModelIsToldTheOrdinaryFailureForTheActionItAsked() {
        // This replaced an assertion that the model's text doesNotContain("interrupt").
        // That was vacuous and a booby trap at once. Nothing in run, gated or
        // ActivityThread can put the word into model-visible text -- the warning goes only
        // to the log -- so it could not fail for the reason it claimed to guard. And it
        // read the FIXTURE's literal: changing the tool's own message to "it was
        // interrupted mid-publish" failed the test with the production code untouched.
        //
        // What is worth asserting is the positive: the model is told the ordinary failure
        // for the action it asked for, because a tool's misuse of the thread is an
        // operator's problem and not something the model can act on.
        AtomicInteger sideEffects = new AtomicInteger();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(litterer(sideEffects, true));
        ScriptedLlm llm = callsThenStops();

        runDurably(llm, tools);

        String toldTheModel = llm.requests().get(1).messages().stream()
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof dev.agentkit.core.message.ToolResultBlock)
                .map(b -> ((dev.agentkit.core.message.ToolResultBlock) b).content())
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(toldTheModel)
                .as("the model was told something other than that its call failed")
                .contains("publish")
                .contains("failed");
    }
}
