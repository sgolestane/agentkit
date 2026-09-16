package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.ServiceNowConnector;
import dev.agentkit.itops.connector.TicketProvider;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.domain.Ticket;
import dev.agentkit.itops.domain.TicketProcessingRecord;
import dev.agentkit.itops.runtime.ExecutionRunner;
import dev.agentkit.itops.runtime.IntakeWorker;
import dev.agentkit.itops.runtime.OpsScheduler;
import dev.agentkit.itops.runtime.Reviewers;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.workflow.Workflow;
import dev.agentkit.itops.workflow.WorkflowRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Catch blocks in this module that stated an invariant {@code catch (RuntimeException)} did
 * not enforce (#135, #262), each pinned by its <em>consequence</em> rather than by the fact
 * that something was caught: the schedule still ticking, the claim released, the execution no
 * longer sitting {@code RUNNING}.
 *
 * <p>#135 covered three of them. #262 found two more of the same shape — an {@link Execution}
 * left {@code RUNNING} in the store with nothing that would ever move it — at
 * {@code ExecutionRunner.run} and at {@code IntakeWorker.tick}'s own sweep row, and a sweep of
 * that method found a third that needs no {@code Error} at all: the {@code searchRecent} call
 * the {@code for} statement makes is outside every {@code try} the method had.
 *
 * <p><strong>The class javadoc said "three" and is corrected rather than replaced</strong>,
 * because the sentence was true of #135 and false of the file: two of the five sites #262
 * names were already reachable from this harness when it was written, and the count was a
 * count of what had been fixed rather than of what this module does.
 *
 * <p>Every test that needs one throws a {@link TestError} — a private {@code Error} subclass —
 * rather than a real {@code OutOfMemoryError}. The invariant under test is about the
 * {@code Error} branch of the type hierarchy, which a subclass exercises exactly; throwing a
 * real one would break the harness before the assertion ran.
 */
class AnErrorIsNotARuntimeExceptionTest {

    private static final String TENANT = "acme";

    /** An {@code Error} a test can throw without taking the JVM with it. */
    private static final class TestError extends Error {
        TestError(String message) {
            super(message);
        }
    }

    // ---- OpsScheduler: a failing tick must not cancel the timer ----------------------

    /**
     * The worst of #135's five, and the only one already verified by execution in the issue.
     * {@code ScheduledExecutorService} suppresses every subsequent execution when a task
     * throws — on any {@code Throwable}, not only a {@code RuntimeException} — so an
     * {@code Error} from one tick used to end the schedule permanently, with no log line, no
     * state update and nothing anywhere saying it had happened. Measured in the issue's
     * harness against the old catch:
     *
     * <pre>
     * ticks in 300ms, tick throws a RuntimeException : 30   (still firing)
     * ticks in 300ms, tick throws an Error           : 2    (gone for good)
     * </pre>
     *
     * <p>So the assertion is that it is still ticking afterwards, counted rather than timed:
     * the latch is the bound, and a slow machine costs this test duration, which
     * {@link Timeout} bounds, rather than a verdict.
     */
    @Test
    @Timeout(30)
    void anErrorFromOneTickDoesNotCancelTheSchedule() throws Exception {
        AtomicInteger ticks = new AtomicInteger();
        CountDownLatch keptFiring = new CountDownLatch(3);
        try (OpsScheduler scheduler = new OpsScheduler()) {
            scheduler.register(schedule("intake", true), input -> {
                int n = ticks.incrementAndGet();
                if (n == 1) {
                    throw new TestError("the JVM had opinions");
                }
                keptFiring.countDown();
                return n;
            });

            assertThat(keptFiring.await(20, TimeUnit.SECONDS))
                    .as("the schedule stopped after the tick that threw; ticks seen=%s",
                            ticks.get())
                    .isTrue();
        }
    }

    @Test
    @Timeout(30)
    void aTickThatThrewIsRecordedAndReportedToTheOperator() throws Exception {
        // The two things an operator has to go on, and both are deletable without the test
        // above noticing: the ScheduleState the console reads, and the WARN line. Captured
        // off System.err, which is what slf4j-simple resolves per write.
        java.io.PrintStream stderr = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        String log;
        List<OpsScheduler.ScheduleState> states;
        try {
            System.setErr(new java.io.PrintStream(captured, true,
                    java.nio.charset.StandardCharsets.UTF_8));
            try (OpsScheduler scheduler = new OpsScheduler()) {
                scheduler.register(schedule("intake", true), input -> {
                    throw new TestError("the JVM had opinions");
                });
                // Waited for rather than slept through, and waited for the *later* of the
                // two writes. The handler's throw happens before the scheduler's catch runs,
                // so a latch tripped inside the task would let this thread read the state
                // before it was written; lastRunAt is set on the line after the log, so
                // seeing it means both have happened. The bound is work done — one recorded
                // tick — and a loaded machine pays for it in seconds, which is what
                // {@code Timeout} is here to cap.
                states = awaitRecordedTick(scheduler);
            }
        } finally {
            System.setErr(stderr);
            log = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        assertThat(log).contains("WARN")
                .contains("Schedule 'intake' failed this tick")
                .contains("the JVM had opinions");
        assertThat(states).hasSize(1);
        assertThat(states.get(0).lastRunAt())
                .as("a tick that threw left the console showing a schedule that had never run")
                .isNotNull();
        assertThat(states.get(0).lastStartedExecutions()).isZero();
    }

    /** Polls until the scheduler has recorded a tick, or gives up saying it never did. */
    private static List<OpsScheduler.ScheduleState> awaitRecordedTick(OpsScheduler scheduler)
            throws InterruptedException {
        long giveUpAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        List<OpsScheduler.ScheduleState> states = scheduler.schedules();
        while (states.get(0).lastRunAt() == null && System.nanoTime() < giveUpAt) {
            Thread.sleep(5);
            states = scheduler.schedules();
        }
        return states;
    }

    private static OpsScheduler.Schedule schedule(String name, boolean enabled) {
        return new OpsScheduler.Schedule(name, "* * * * *", Duration.ofMillis(20),
                "it-ops-agent", Map.of("assignment_group", "AgentKit"), enabled);
    }

    // ---- IntakeWorker: the claim must be released -----------------------------------

    /**
     * A ticket is claimed so that the next sweep does not start a second run on it. An
     * {@code Error} out of the agent used to fly past a catch that named
     * {@code RuntimeException}, so {@code finishProcessing} was never called and the claim
     * stayed {@code CLAIMED} for good: the ticket was stuck rather than retried, which is the
     * one outcome the claim exists to prevent.
     *
     * <p>The {@code Error} is thrown by the {@code LlmClient}, which is where one plausibly
     * comes from and, more to the point, the only seam a test has: {@code Agent.run} catches
     * {@code RuntimeException} around the model call, so an {@code Error} leaves the agent,
     * leaves {@code ExecutionRunner.run} — whose catch is also {@code RuntimeException} — and
     * arrives at the worker.
     */
    @Test
    @Timeout(30)
    void anErrorReleasesTheTicketClaimAndStopsTheSweep() {
        OpsStore store = new OpsStore();
        OneTicket tickets = new OneTicket();
        LlmClient breaks = request -> {
            throw new TestError("the JVM had opinions");
        };
        IntakeWorker worker = new IntakeWorker(store, tickets,
                new ExecutionRunner(store, breaks, "scripted", tickets, new DirectoryConnector(),
                        new IdentityConnector(), "agentkit-integration", Reviewers.goalAlignment(),
                        Risk.HIGH),
                TENANT, "it-ops-agent");

        assertThatThrownBy(() -> worker.tick("AgentKit", Duration.ofMinutes(60), 10))
                .as("the Error was swallowed or laundered on its way out of the sweep")
                .isInstanceOf(TestError.class);

        // The consequence. A record left CLAIMED is a ticket no later sweep will look at.
        List<TicketProcessingRecord> records = store.processingRecords(TENANT);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).status())
                .as("the claim was never finished, so the ticket is stuck rather than retried")
                .isEqualTo(TicketProcessingRecord.Status.FAILED);
        assertThat(records.get(0).finishedAt()).isNotNull();
    }

    /**
     * The sweep's own {@link Execution} row, which is a different record from the claim
     * above and from the per-ticket row the runner files (#262).
     *
     * <p>#135 fixed the claim, and #261 fixed the neighbouring thing in the same class,
     * which is why this needs saying plainly: the sweep row was <em>unchanged</em> by
     * either. It is saved {@code RUNNING} before the first candidate is listed and moved to
     * {@code COMPLETED} after the last, so an {@code Error} on the way through left an
     * operator console showing a sweep still in progress that no thread was running.
     *
     * <p>Asserted separately from the claim rather than bolted onto the test above, because
     * they are two records and a mutant can break either one alone.
     */
    @Test
    @Timeout(30)
    void anErrorFilesTheSweepsOwnExecutionRatherThanLeavingItRunning() {
        OpsStore store = new OpsStore();
        OneTicket tickets = new OneTicket();
        LlmClient breaks = request -> {
            throw new TestError("the JVM had opinions");
        };
        IntakeWorker worker = new IntakeWorker(store, tickets,
                new ExecutionRunner(store, breaks, "scripted", tickets, new DirectoryConnector(),
                        new IdentityConnector(), "agentkit-integration", Reviewers.goalAlignment(),
                        Risk.HIGH),
                TENANT, "it-ops-agent");

        assertThatThrownBy(() -> worker.tick("AgentKit", Duration.ofMinutes(60), 10))
                .as("the Error was swallowed or laundered on its way out of the sweep")
                .isInstanceOf(TestError.class);

        List<Execution> sweeps = sweepRowsIn(store);
        assertThat(sweeps).hasSize(1);
        assertThat(sweeps.get(0).status())
                .as("the Error left the sweep RUNNING with nothing that would move it")
                .isEqualTo(Execution.Status.FAILED);
        assertThat(store.events(sweeps.get(0).id()).stream()
                .map(Execution.Event::type).toList())
                .as("the trail says a sweep began and never says how it ended")
                .containsSubsequence(Execution.Event.Type.EXECUTION_STARTED,
                        Execution.Event.Type.EXECUTION_FAILED);
    }

    /**
     * The site the sweep of {@code tick} turned up, and the one that needs no {@code Error}.
     *
     * <p>{@code tickets.searchRecent} is evaluated by the {@code for} statement itself, so it
     * sits outside every {@code try} the method had. A ticket provider that is down throws an
     * ordinary {@code RuntimeException} there, before a single ticket is discovered or
     * claimed, and the sweep row was orphaned on the first line of work — the same shape as
     * #135's {@code WorkflowRunner} site, which also turned out not to need an {@code Error}.
     *
     * <p>The rethrow is asserted too, and it is not decoration: {@code OpsScheduler} and
     * {@code WebServer} both tell a failed tick from a successful one by whether this method
     * threw, so filing the row and then swallowing would report a clean tick that happened to
     * start nothing.
     */
    @Test
    @Timeout(30)
    void aProviderThatThrowsFilesTheSweepRatherThanAbandoningIt() {
        OpsStore store = new OpsStore();
        ThrowingProvider down =
                new ThrowingProvider(new IllegalStateException("the connector is down"), null);
        IntakeWorker worker = workerOver(store, down);

        assertThatThrownBy(() -> worker.tick("AgentKit", Duration.ofMinutes(60), 10))
                .as("the sweep reported success after listing nothing")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the connector is down");

        List<Execution> sweeps = sweepRowsIn(store);
        assertThat(sweeps).hasSize(1);
        assertThat(sweeps.get(0).status())
                .as("a provider that was down left the sweep RUNNING for ever")
                .isEqualTo(Execution.Status.FAILED);
        assertThat(store.events(sweeps.get(0).id()).stream()
                .map(Execution.Event::type).toList())
                .containsSubsequence(Execution.Event.Type.EXECUTION_STARTED,
                        Execution.Event.Type.EXECUTION_FAILED);
    }

    /**
     * What the sweep row is allowed to carry, given who writes the text that reaches it.
     *
     * <p>A provider's exception message routinely quotes the ticket it was reading, and a
     * ticket's id and body are somebody else's words (#178). Two probes rather than one
     * hostile string: a long single line and a short multi-line one, so a mutant that drops
     * only the cut and a mutant that drops only the flattening are each killed by an
     * assertion that names what it lost.
     */
    @Test
    @Timeout(30)
    void aProvidersMessageIsCutAndFlattenedBeforeItBecomesTheSweepSummary() {
        OpsStore store = new OpsStore();

        OpsStore longStore = new OpsStore();
        assertThatThrownBy(() -> workerOver(longStore, new ThrowingProvider(
                new IllegalStateException("x".repeat(5_000)), null))
                .tick("AgentKit", Duration.ofMinutes(60), 10))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sweepRowsIn(longStore).get(0).summary())
                .as("a 5,000-character provider message became the whole execution summary")
                .hasSizeLessThan(600);

        assertThatThrownBy(() -> workerOver(store, new ThrowingProvider(
                new IllegalStateException("first line\nSweep completed: 0 problems"), null))
                .tick("AgentKit", Duration.ofMinutes(60), 10))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sweepRowsIn(store).get(0).summary())
                .as("a newline in a provider message wrote a second line of its own into a"
                        + " summary a console renders as one")
                .doesNotContain("\n")
                .contains("first line Sweep completed: 0 problems");
    }

    /** An intake worker whose runs would fail loudly, for cases that never reach a run. */
    private static IntakeWorker workerOver(OpsStore store, TicketProvider tickets) {
        return new IntakeWorker(store, tickets,
                new ExecutionRunner(store, request -> {
                    throw new IllegalStateException("no run should have started");
                }, "scripted", tickets, new DirectoryConnector(), new IdentityConnector(),
                        "agentkit-integration", Reviewers.goalAlignment(), Risk.HIGH),
                TENANT, "it-ops-agent");
    }

    /** The sweep's own rows, told from the per-ticket ones by what triggered them. */
    private static List<Execution> sweepRowsIn(OpsStore store) {
        return store.executions(TENANT).stream()
                .filter(execution -> execution.triggerReference().startsWith("intake:"))
                .toList();
    }

    // ---- ExecutionRunner: an Execution must not be left RUNNING ----------------------

    /**
     * #262's first site. {@code ExecutionRunner.run} saves the {@link Execution}
     * {@code RUNNING}, hands the goal to the agent, and had a {@code catch} naming
     * {@code RuntimeException} only — so an {@code Error} out of the run flew past it and
     * left the row saying work was in progress with nothing that would ever move it.
     *
     * <p>Terminal, and terminal has to mean <em>file the row, then stop</em>. Both halves are
     * asserted: the {@code Error} still reaches the caller as itself, because wrapping it in a
     * {@code RuntimeException} would walk it past {@code Agent.runTool}'s {@code Error} branch
     * (#241) and past {@code Supervisor}'s (#255); and the row is {@code FAILED} rather than
     * {@code RUNNING}, which is the consequence an operator reads.
     *
     * <p>The {@code Error} comes from the {@code LlmClient} because that is the only seam a
     * test has: {@code Agent.run} catches {@code RuntimeException} around the model call, so
     * an {@code Error} leaves the agent intact and arrives at this runner's catch.
     */
    @Test
    @Timeout(30)
    void anErrorFromTheAgentFilesTheExecutionRatherThanLeavingItRunning() {
        OpsStore store = new OpsStore();
        OneTicket tickets = new OneTicket();
        LlmClient breaks = request -> {
            throw new TestError("the JVM had opinions");
        };
        ExecutionRunner runner = new ExecutionRunner(store, breaks, "scripted", tickets,
                new DirectoryConnector(), new IdentityConnector(), "agentkit-integration",
                Reviewers.goalAlignment(), Risk.HIGH);
        Execution work = store.createExecution(TENANT, "it-ops-agent",
                Execution.Trigger.CHAT, "conversation-1", "Work INC1.");

        assertThatThrownBy(() -> runner.run(work, null))
                .as("the Error was swallowed or laundered on its way out of the run")
                .isInstanceOf(TestError.class);

        assertThat(store.execution(TENANT, work.id()).orElseThrow().status())
                .as("the Error left the execution RUNNING with nothing that would move it")
                .isEqualTo(Execution.Status.FAILED);
        assertThat(store.events(work.id()).stream().map(Execution.Event::type).toList())
                .as("the trail says a run began and never says how it ended")
                .contains(Execution.Event.Type.EXECUTION_FAILED);
    }

    /**
     * The other half of the same rule, and the half that must not change.
     *
     * <p>Widening the catch to {@code RuntimeException | Error} would be worth nothing if it
     * also turned a {@code RuntimeException} into a rethrow: this runner's ordinary vocabulary
     * for a run that did not work is a {@code FAILED} {@code Outcome} handed back, and
     * {@code IntakeWorker} files the ticket on it.
     *
     * <p><strong>The seam is {@code Goal.of} rather than a model that throws, and that is a
     * finding rather than a convenience.</strong> {@code Goal.of(goalText)} is evaluated
     * inside the guarded region, and a blank goal is refused there — {@code Execution}
     * requires a goal to be non-null and does not require it to be non-blank, so an empty
     * chat message reaches this line. A model that throws does <em>not</em> reach this catch:
     * {@code Agent.run} guards the model call with its own {@code catch (RuntimeException)},
     * guards tool and gate calls with {@code runTool}'s, and absorbs observer failures in
     * {@code Observations.ran}. Measured by pointing this test at an {@code LlmClient}
     * throwing {@code IllegalStateException}: the run came back
     * {@code COMPLETED}, not {@code FAILED} and not thrown.
     *
     * <p><strong>That last sentence described a defect and is kept as history rather than
     * deleted (#265).</strong> The asymmetry is unchanged — a model that throws still does
     * not reach this catch — but where it lands did change: it arrives as
     * {@code StopReason.ERROR} on an ordinary result, and {@code ExecutionRunner.statusFor}
     * now files that {@code FAILED}. {@code TheRowSaysHowTheRunEndedTest} pins it. So this
     * test's seam is still {@code Goal.of}, and it is no longer the <em>only</em> way to
     * reach a {@code FAILED} row.
     */
    @Test
    @Timeout(30)
    void aRuntimeExceptionInTheGuardedRegionStillComesBackAsAFailedOutcome() {
        OpsStore store = new OpsStore();
        OneTicket tickets = new OneTicket();
        LlmClient unused = request -> {
            throw new IllegalStateException("no model call should have been made");
        };
        ExecutionRunner runner = new ExecutionRunner(store, unused, "scripted", tickets,
                new DirectoryConnector(), new IdentityConnector(), "agentkit-integration",
                Reviewers.goalAlignment(), Risk.HIGH);
        Execution blank = store.createExecution(TENANT, "it-ops-agent",
                Execution.Trigger.CHAT, "conversation-1", "   ");

        ExecutionRunner.Outcome outcome = runner.run(blank, null);

        assertThat(outcome.execution().status())
                .as("a refused goal started throwing at the caller instead of being reported")
                .isEqualTo(Execution.Status.FAILED);
        assertThat(outcome.parked()).isFalse();
        assertThat(store.execution(TENANT, blank.id()).orElseThrow().status())
                .as("the stored row disagrees with the Outcome the caller was handed")
                .isEqualTo(Execution.Status.FAILED);
    }

    /** A provider holding exactly one recent ticket, so the sweep has one claim to make. */
    private static final class OneTicket implements TicketProvider {
        private final Ticket only = new Ticket("INC1", "servicenow", "Access request",
                "Please add me to Contractors.", Ticket.Status.OPEN, "AgentKit", null,
                Instant.now(), Instant.now(), List.of());

        @Override public String name() {
            return "servicenow";
        }

        @Override public List<Ticket> searchRecent(String assignmentGroup, Duration lookback, int limit) {
            return List.of(only);
        }

        @Override public List<Ticket> search(String query, int limit) {
            return List.of(only);
        }

        @Override public Optional<Ticket> get(String id) {
            return only.id().equals(id) ? Optional.of(only) : Optional.empty();
        }

        @Override public List<Ticket.Comment> comments(String id) {
            return List.of();
        }

        @Override public Ticket assign(String id, String assignee, String assignmentGroup) {
            return only;
        }

        @Override public Ticket comment(String id, String author, String body) {
            return only;
        }

        @Override public Ticket updateStatus(String id, Ticket.Status status) {
            return only;
        }
    }

    // ---- WorkflowRunner: an Execution must not be left RUNNING -----------------------

    /** One tool step and an end, so the audit rows this is about are unambiguous. */
    private static Workflow oneStep(String tool, Map<String, Object> arguments) {
        return new Workflow("throw-probe", 1, "Throw probe", "One step that throws.",
                List.of(
                        Workflow.Node.start(),
                        Workflow.Node.tool("step", "The step that throws", tool, arguments),
                        Workflow.Node.end("done", "Done")),
                List.of(
                        Workflow.Edge.of("start", "step"),
                        Workflow.Edge.of("step", "done")),
                true);
    }

    private static WorkflowRunner runnerOver(OpsStore store, TicketProvider tickets) {
        return new WorkflowRunner(store, tickets, new DirectoryConnector(),
                new IdentityConnector(), "agentkit-integration", Risk.HIGH);
    }

    /** A provider whose reads throw whatever it was built with. */
    private record ThrowingProvider(RuntimeException runtime, Error error) implements TicketProvider {
        private <T> T fail() {
            if (error != null) {
                throw error;
            }
            throw runtime;
        }

        @Override public String name() {
            return "servicenow";
        }

        @Override public List<Ticket> searchRecent(String group, Duration lookback, int limit) {
            return fail();
        }

        @Override public List<Ticket> search(String query, int limit) {
            return fail();
        }

        @Override public Optional<Ticket> get(String id) {
            return fail();
        }

        @Override public List<Ticket.Comment> comments(String id) {
            return fail();
        }

        @Override public Ticket assign(String id, String assignee, String group) {
            return fail();
        }

        @Override public Ticket comment(String id, String author, String body) {
            return fail();
        }

        @Override public Ticket updateStatus(String id, Ticket.Status status) {
            return fail();
        }
    }

    @Test
    @Timeout(30)
    void aToolThatThrowsARuntimeExceptionFilesTheExecutionRatherThanAbandoningIt() {
        // #135's third site, and the half that did not even need an Error: the execute() call
        // was unguarded, so a plain RuntimeException escaped run() after TOOL_STARTED had
        // been appended and before any terminal event. The Execution sat RUNNING in the store
        // with nothing in the system that would ever move it.
        OpsStore store = new OpsStore();
        WorkflowRunner.Result result = runnerOver(store,
                new ThrowingProvider(new IllegalStateException("the connector is down"), null))
                .run(TENANT, oneStep("ticketing.get_ticket", Map.of("ticket_id", "INC1")), Map.of());

        assertThat(result.execution().status())
                .as("a thrown tool left the execution unfinished")
                .isEqualTo(Execution.Status.FAILED);
        List<Execution.Event.Type> types = store.events(result.execution().id()).stream()
                .map(Execution.Event::type).toList();
        assertThat(types).as("the trail says a call began and never says how it ended")
                .containsSubsequence(Execution.Event.Type.TOOL_STARTED,
                        Execution.Event.Type.TOOL_FAILED,
                        Execution.Event.Type.EXECUTION_FAILED);
    }

    @Test
    @Timeout(30)
    void anErrorFilesTheExecutionAndStillReachesTheCaller() {
        // The other decision, argued rather than inherited. The same invariant is honoured —
        // the Execution is filed FAILED, so nothing is left RUNNING — and then the Error is
        // rethrown as itself, because turning it into an error ToolResult is exactly the
        // "an OutOfMemoryError looks like a handled tool failure" that #135 warns against.
        OpsStore store = new OpsStore();
        WorkflowRunner runner = runnerOver(store,
                new ThrowingProvider(null, new TestError("the JVM had opinions")));

        assertThatThrownBy(() -> runner.run(TENANT,
                oneStep("ticketing.get_ticket", Map.of("ticket_id", "INC1")), Map.of()))
                .as("the Error was turned into an ordinary failed workflow")
                .isInstanceOf(TestError.class);

        List<Execution> executions = store.executions(TENANT);
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).status())
                .as("the Error left the execution RUNNING with nothing that would move it")
                .isEqualTo(Execution.Status.FAILED);
        assertThat(store.events(executions.get(0).id()).stream()
                .map(Execution.Event::type).toList())
                .containsSubsequence(Execution.Event.Type.TOOL_STARTED,
                        Execution.Event.Type.EXECUTION_FAILED);
    }

    @Test
    @Timeout(30)
    void aGateThatThrowsAlsoFilesTheExecution() {
        // The same invariant, reached from the other line inside invoke(). gate.evaluate is
        // after TOOL_STARTED too, so a gate that throws breaks the Execution's status exactly
        // as a tool that throws does — which is why the catch is around the call and not
        // around one statement inside it.
        OpsStore store = new OpsStore();
        WorkflowRunner.Result result = runnerOver(store, new ServiceNowConnector())
                .run(TENANT, oneStep("identity.get_group_members",
                                Map.of("group", "Production-Administrators")), Map.of(),
                        (tool, invocation) -> {
                            throw new IllegalStateException("the policy service is down");
                        });

        assertThat(result.execution().status()).isEqualTo(Execution.Status.FAILED);
        assertThat(store.events(result.execution().id()).stream()
                .map(Execution.Event::type).toList())
                .containsSubsequence(Execution.Event.Type.TOOL_STARTED,
                        Execution.Event.Type.EXECUTION_FAILED);
    }
}
