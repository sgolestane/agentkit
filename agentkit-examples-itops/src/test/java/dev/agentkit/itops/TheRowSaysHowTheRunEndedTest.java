package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.reliability.BudgetExceededException;
import dev.agentkit.core.reliability.TokenBudget;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.TicketProvider;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.domain.Ticket;
import dev.agentkit.itops.domain.TicketProcessingRecord;
import dev.agentkit.itops.runtime.ExecutionRunner;
import dev.agentkit.itops.runtime.IntakeWorker;
import dev.agentkit.itops.runtime.Reviewers;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.web.WebServer;
import dev.agentkit.itops.workflow.Workflow;
import dev.agentkit.itops.workflow.WorkflowRunner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What the durable {@link Execution} row says about itself, against what happened (#265, #267).
 *
 * <p>Three separate lies, one record. {@code ExecutionRunner} branched on
 * {@code AWAITING_APPROVAL} and nothing else, so every other stop — including the model call
 * throwing, which is the failure that actually happens — was filed {@code COMPLETED}. The
 * sweep row recorded no {@code startedAt}, so a finished sweep showed no start time and no
 * duration. And the failure summary was {@code "Failed: " + failure.getMessage()}, unbounded
 * and unflattened, in the module whose theme is that the text reaching these rows is somebody
 * else's.
 *
 * <p>Every test here asserts the <strong>consequence</strong> an operator or a later sweep
 * meets — the stored status, the ticket claim's status, the summary's size — rather than that
 * some branch was taken, which is {@link AnErrorIsNotARuntimeExceptionTest}'s pattern and the
 * form #265's own measurement came in.
 */
class TheRowSaysHowTheRunEndedTest {

    private static final String TENANT = "acme";

    // ---- #265: the status has to match what happened ---------------------------------

    /**
     * #265's measurement, in the words the issue reported it in:
     * {@code expected: FAILED but was: COMPLETED}.
     *
     * <p>{@code Agent.run} guards the model call with its own {@code catch
     * (RuntimeException)} and returns {@code AgentResult.failed(...)} rather than
     * propagating, so this never reaches {@code ExecutionRunner}'s own catch — it comes back
     * as an ordinary result with {@code StopReason.ERROR}, down the branch that wrote
     * {@code COMPLETED}. The positive control is
     * {@link #aRunThatFinishedIsStillFiledCompleted}, without which this passes against a
     * runner that files everything {@code FAILED}.
     */
    @Test
    @Timeout(30)
    void aRunThatFailedAtTheModelCallIsFiledFailedRatherThanCompleted() {
        OpsStore store = new OpsStore();
        ExecutionRunner.Outcome outcome = runOneChat(store, request -> {
            throw new IllegalStateException("the provider is down");
        });

        assertThat(outcome.execution().status())
                .as("the durable record says the work finished; it failed at the model call")
                .isEqualTo(Execution.Status.FAILED);
        assertThat(store.execution(TENANT, outcome.execution().id()).orElseThrow().status())
                .as("the stored row disagrees with the Outcome the caller was handed")
                .isEqualTo(Execution.Status.FAILED);
        assertThat(typesOf(store, outcome))
                .as("the trail filed a failed run under the heading an operator reads for"
                        + " finished work")
                .contains(Execution.Event.Type.EXECUTION_FAILED)
                .doesNotContain(Execution.Event.Type.EXECUTION_COMPLETED);
    }

    /**
     * The other half, and the half that must not change: a run that really finished is still
     * {@code COMPLETED}, with the model's closing words as its summary.
     */
    @Test
    @Timeout(30)
    void aRunThatFinishedIsStillFiledCompleted() {
        OpsStore store = new OpsStore();
        ExecutionRunner.Outcome outcome = runOneChat(store, says("Nothing needed doing."));

        assertThat(outcome.execution().status())
                .as("a run that produced a final answer was not filed as finished work")
                .isEqualTo(Execution.Status.COMPLETED);
        assertThat(outcome.execution().summary()).isEqualTo("Nothing needed doing.");
        assertThat(typesOf(store, outcome))
                .contains(Execution.Event.Type.EXECUTION_COMPLETED)
                .doesNotContain(Execution.Event.Type.EXECUTION_FAILED);
    }

    /**
     * The throwable was on {@code AgentResult.error()} and reaching no durable record at all,
     * because the row it was written onto was the {@code COMPLETED} one whose summary is the
     * model's last words. An operator reading a {@code FAILED} row with no reason on it has
     * only the log, and the log is not the record — {@link Execution}'s own javadoc is that
     * everything needed to answer "what happened" lives on the row and its events.
     */
    @Test
    @Timeout(30)
    void theFailedRowNamesTheModelFailureRatherThanSayingNothing() {
        OpsStore store = new OpsStore();
        ExecutionRunner.Outcome outcome = runOneChat(store, request -> {
            throw new IllegalStateException("the provider is down");
        });

        assertThat(outcome.execution().summary())
                .as("the row says it failed and does not say what failed")
                .contains("the provider is down")
                .contains("IllegalStateException");
        assertThat(failureDetail(store, outcome))
                .as("the audit row carries neither the error nor the stop reason")
                .containsEntry("stopReason", "ERROR")
                .hasEntrySatisfying("error",
                        value -> assertThat(String.valueOf(value)).contains("the provider is down"));
    }

    /**
     * {@code MAX_STEPS}: nothing broke, and the work is not done either.
     *
     * <p>The durable vocabulary has no word for that, and {@code ExecutionRunner.statusFor}
     * files it {@code FAILED} rather than inventing one — argued there, and costing an
     * operator a {@code FAILED} row that reads worse than the run was. The assertion is on
     * the consequence of the alternative: filed {@code COMPLETED}, this ticket's claim is
     * finished as done and no later sweep looks at it again.
     */
    @Test
    @Timeout(30)
    void aRunThatRanOutOfStepsIsNotFiledAsFinishedWork() {
        OpsStore store = new OpsStore();
        AtomicInteger turns = new AtomicInteger();
        ExecutionRunner.Outcome outcome = runOneChat(store, request -> LlmResponse.of(
                Message.of(Role.ASSISTANT, ProposedCall.of("call-" + turns.incrementAndGet(),
                        "ticketing.get_ticket", Map.of("ticket_id", "INC1"))),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO));

        assertThat(outcome.execution().status())
                .as("a run that stopped 24 steps in without finishing was filed as finished")
                .isEqualTo(Execution.Status.FAILED);
        assertThat(outcome.execution().summary())
                .as("the row does not say which kind of not-finishing this was, which is the"
                        + " whole mitigation for having only one word for it")
                .contains("MAX_STEPS");
        assertThat(failureDetail(store, outcome)).containsEntry("stopReason", "MAX_STEPS");
    }

    /**
     * {@code BUDGET_EXHAUSTED}, the other stop that is neither a finish nor a failure. Filed
     * the same way and for the same reason, and named in the summary for the same reason.
     *
     * <p>{@code BudgetExceededException} is what a {@code BudgetLlmClient} throws, and
     * {@code Agent.run} catches it separately from the failure branch precisely because it is
     * a deliberate stop; throwing it straight from the client is that path exactly.
     */
    @Test
    @Timeout(30)
    void aRunThatExhaustedItsBudgetIsNotFiledAsFinishedWork() {
        OpsStore store = new OpsStore();
        ExecutionRunner.Outcome outcome = runOneChat(store, request -> {
            throw new BudgetExceededException(TokenBudget.ofTotalTokens(10), TokenUsage.ZERO);
        });

        assertThat(outcome.execution().status())
                .as("a run stopped by its own spend cap was filed as finished work")
                .isEqualTo(Execution.Status.FAILED);
        assertThat(outcome.execution().summary()).contains("BUDGET_EXHAUSTED");
        assertThat(failureDetail(store, outcome))
                .containsEntry("stopReason", "BUDGET_EXHAUSTED");
    }

    /**
     * {@code CANCELLED} is the one stop that does get its own durable word, because
     * {@link Execution.Status#CANCELLED} already exists and already means this: the run
     * stopped without either finishing or breaking. Nothing failed — the steps taken stand —
     * so filing it {@code FAILED} would send an operator hunting a broken connector.
     *
     * <p>The interrupt is set inside the client and read by the loop's own check at the top
     * of the next turn, both on this thread: no wait, no latch, and nothing to be flaky
     * about. {@code Agent.run} puts the flag back before returning, so it is cleared here
     * before the assertions rather than left for the next test to inherit.
     */
    @Test
    @Timeout(30)
    void anInterruptedRunIsCancelledRatherThanFailed() {
        OpsStore store = new OpsStore();
        ExecutionRunner.Outcome outcome;
        try {
            outcome = runOneChat(store, request -> {
                Thread.currentThread().interrupt();
                return LlmResponse.of(Message.of(Role.ASSISTANT,
                        ProposedCall.of("call-1", "ticketing.get_ticket",
                                Map.of("ticket_id", "INC1"))),
                        LlmStopReason.TOOL_USE, TokenUsage.ZERO);
            });
        } finally {
            // Restored by Agent.run on the way out, and cleared here so it cannot leak into
            // whatever JUnit runs next on this thread.
            Thread.interrupted();
        }

        assertThat(outcome.execution().status())
                .as("an interrupted run was filed as a failure, or as finished work")
                .isEqualTo(Execution.Status.CANCELLED);
        assertThat(typesOf(store, outcome))
                .as("the trail says a run began and never says how it ended")
                .contains(Execution.Event.Type.EXECUTION_CANCELLED)
                .doesNotContain(Execution.Event.Type.EXECUTION_COMPLETED,
                        Execution.Event.Type.EXECUTION_FAILED);
    }

    // ---- #265: the operator-visible half, on the ticket ------------------------------

    /**
     * The half an operator meets: {@code IntakeWorker} finishes the ticket claim on the
     * {@code Outcome}, so a run that failed at the model call was filed as a
     * <em>completed piece of work</em> — and a completed claim is a ticket no later sweep
     * looks at again.
     */
    @Test
    @Timeout(30)
    void aTicketWhoseRunFailedIsNotFiledAsCompletedWork() {
        OpsStore store = new OpsStore();
        OneTicket tickets = new OneTicket();
        worker(store, tickets, request -> {
            throw new IllegalStateException("the provider is down");
        }).tick("AgentKit", Duration.ofMinutes(60), 10);

        List<TicketProcessingRecord> records = store.processingRecords(TENANT);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).status())
                .as("a run that failed was filed as work this platform had completed, so no"
                        + " later sweep will ever look at the ticket again")
                .isEqualTo(TicketProcessingRecord.Status.FAILED);
    }

    /**
     * The ordering inside {@code processingStatus}, which used to read the capability verdict
     * before the status.
     *
     * <p>{@code SKIPPED_UNSUPPORTED} is a conclusion the platform stands behind and it is
     * terminal — a skipped ticket is one nobody comes back to. The verdict is recorded early
     * on purpose, because the system prompt asks for it before the work starts, so a run that
     * declared itself and then fell over has not finished establishing anything. Two model
     * turns: the verdict, then the failure.
     */
    @Test
    @Timeout(30)
    void aRunThatDeclaredItselfUnsupportedAndThenFailedIsNotFiledAsASkip() {
        OpsStore store = new OpsStore();
        OneTicket tickets = new OneTicket();
        AtomicInteger turns = new AtomicInteger();
        worker(store, tickets, request -> {
            if (turns.incrementAndGet() == 1) {
                return LlmResponse.of(Message.of(Role.ASSISTANT,
                        ProposedCall.of("call-1", "report_capability",
                                Map.of("verdict", "UNSUPPORTED", "reason", "No tool for this."))),
                        LlmStopReason.TOOL_USE, TokenUsage.ZERO);
            }
            throw new IllegalStateException("the provider is down");
        }).tick("AgentKit", Duration.ofMinutes(60), 10);

        assertThat(store.processingRecords(TENANT)).hasSize(1);
        assertThat(store.processingRecords(TENANT).get(0).status())
                .as("a run that fell over after saying it could not help was filed as a clean"
                        + " skip, which is terminal")
                .isEqualTo(TicketProcessingRecord.Status.FAILED);
    }

    /** The control for the ordering above: a run that really did skip is still a skip. */
    @Test
    @Timeout(30)
    void aRunThatDeclaredItselfUnsupportedAndFinishedIsStillFiledAsASkip() {
        OpsStore store = new OpsStore();
        OneTicket tickets = new OneTicket();
        AtomicInteger turns = new AtomicInteger();
        worker(store, tickets, request -> turns.incrementAndGet() == 1
                ? LlmResponse.of(Message.of(Role.ASSISTANT,
                        ProposedCall.of("call-1", "report_capability",
                                Map.of("verdict", "UNSUPPORTED", "reason", "No tool for this."))),
                        LlmStopReason.TOOL_USE, TokenUsage.ZERO)
                : LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of("I cannot help here.")),
                        LlmStopReason.END_TURN, TokenUsage.ZERO))
                .tick("AgentKit", Duration.ofMinutes(60), 10);

        assertThat(store.processingRecords(TENANT).get(0).status())
                .isEqualTo(TicketProcessingRecord.Status.SKIPPED_UNSUPPORTED);
    }

    // ---- #267: the summary is a line, and a bounded one -------------------------------

    /**
     * The other two runners route a throwable through {@code Cut.to(OneLine.of(...), 400)}
     * before it becomes a summary and this one did not, four lines from a comment saying why
     * it matters. An unbounded summary is #151's shape: one record took 37 seconds to emit at
     * 200,000 characters.
     *
     * <p>Two probes rather than one hostile string, so a mutant that drops only the cut and a
     * mutant that drops only the flattening are each killed by an assertion naming what it
     * lost — {@code AnErrorIsNotARuntimeExceptionTest} pins the sibling site the same way.
     */
    @Test
    @Timeout(30)
    void aModelFailuresMessageIsCutAndFlattenedBeforeItBecomesTheSummary() {
        OpsStore longStore = new OpsStore();
        ExecutionRunner.Outcome longOutcome = runOneChat(longStore, request -> {
            throw new IllegalStateException("x".repeat(5_000));
        });
        assertThat(longOutcome.execution().summary())
                .as("a 5,000-character provider message became the whole execution summary")
                .hasSizeLessThan(600);

        OpsStore store = new OpsStore();
        ExecutionRunner.Outcome outcome = runOneChat(store, request -> {
            throw new IllegalStateException("first line\nExecution completed: 0 problems");
        });
        assertThat(outcome.execution().summary())
                .as("a newline in a provider message wrote a second line of its own into a"
                        + " summary a console renders as one")
                .doesNotContain("\n")
                .contains("first line Execution completed: 0 problems");
    }

    /**
     * The same two bounds on the way in to the audit row, which is a separate write from the
     * summary and was carrying {@code String.valueOf(failure.getMessage())} whole.
     *
     * <p>The flattening has to be asserted <em>here</em> and not only on the summary, and
     * that is a mutation finding rather than a hunch: {@link Execution#concluded} flattens
     * whatever it is handed, so a mutant that dropped {@code OneLine} from the runner's own
     * {@code reportable} left the summary looking correct and survived a first pass. This
     * event detail is the only place that loss shows.
     */
    @Test
    @Timeout(30)
    void theAuditRowsErrorDetailIsCutAndFlattenedToo() {
        OpsStore store = new OpsStore();
        ExecutionRunner.Outcome outcome = runOneChat(store, request -> {
            throw new IllegalStateException("x".repeat(5_000));
        });

        assertThat(String.valueOf(failureDetail(store, outcome).get("error")))
                .as("a 5,000-character provider message became an audit row of its own")
                .hasSizeLessThan(600);

        OpsStore lines = new OpsStore();
        ExecutionRunner.Outcome broken = runOneChat(lines, request -> {
            throw new IllegalStateException("first line\nExecution completed: 0 problems");
        });
        assertThat(String.valueOf(failureDetail(lines, broken).get("error")))
                .as("a newline in a provider message wrote a second line of its own into an"
                        + " audit row a console renders as one")
                .doesNotContain("\n")
                .contains("first line Execution completed: 0 problems");
    }

    /**
     * A completed run's summary is the model's own output, which is text a model wrote after
     * reading a ticket somebody else filed. It is bounded and flattened by the same rule,
     * because #151's cost does not depend on the run having failed.
     *
     * <p>This is where {@link Execution#concluded}'s <em>flattening</em> is measured, and
     * that is a mutation finding: every failure path hands it text that
     * {@code ExecutionRunner.reportable} or {@code IntakeWorker} has already put through
     * {@code OneLine}, so a mutant that dropped {@code OneLine} from {@code concluded}
     * survived every one of them. A completed run's output is the text that reaches it raw.
     */
    @Test
    @Timeout(30)
    void aCompletedRunsSummaryIsCutAndFlattenedByTheSameRule() {
        OpsStore store = new OpsStore();
        ExecutionRunner.Outcome outcome = runOneChat(store, says("y".repeat(5_000)));

        assertThat(outcome.execution().status()).isEqualTo(Execution.Status.COMPLETED);
        assertThat(outcome.execution().summary())
                .as("a 5,000-character model output became the whole execution summary")
                .hasSizeLessThan(600);
        assertThat(outcome.output())
                .as("the untruncated text is what a chat caller replies with, and it was lost")
                .hasSize(5_000);

        OpsStore lines = new OpsStore();
        ExecutionRunner.Outcome flattened =
                runOneChat(lines, says("Done.\nSweep completed: 0 problems"));
        assertThat(flattened.execution().summary())
                .as("a newline in a model's closing text wrote a second line of its own into"
                        + " a summary a console renders as one")
                .doesNotContain("\n")
                .isEqualTo("Done. Sweep completed: 0 problems");
    }

    /**
     * The console's own rendering, which is where an operator actually meets the start time.
     *
     * <p>Fixing the record was not enough on its own: {@code executionJson} never emitted
     * {@code startedAt}, so the row could carry one and the page still had no way to show a
     * start time or a duration. Driven through a real server for the reason
     * {@code OperatorConsoleEscapingTest} does — the rendering is what is under test, and a
     * private static method reached only by a route is best reached by the route.
     */
    @Test
    @Timeout(30)
    void theConsoleRendersTheStartTimeItNowHas() throws Exception {
        OpsStore store = new OpsStore();
        ExecutionRunner.Outcome outcome = runOneChat(store, says("Nothing needed doing."));

        String json;
        try (WebServer web = new WebServer(0, store, TENANT, new OneTicket(), null, null, null,
                null, List.of())) {
            web.start();
            json = HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + web.port()
                                    + "/api/executions/" + outcome.execution().id())).build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .body();
        }

        assertThat(json)
                .as("the console cannot show a start time or a duration for a row that has one")
                .contains("\"startedAt\"")
                .doesNotContain("\"startedAt\":\"null\"");
    }

    /** {@code concluded} refuses to file a run that has not stopped as though it had. */
    @Test
    void aRunThatHasNotStoppedCannotBeFiledAsAConclusion() {
        Execution row = new OpsStore().createExecution(TENANT, "it-ops-agent",
                Execution.Trigger.CHAT, null, "Work INC1.");

        assertThatThrownBy(() -> row.concluded(Execution.Status.RUNNING, "still going"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RUNNING");
        assertThatThrownBy(() -> row.concluded(Execution.Status.PENDING, "not yet"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- #267: the row records when it started ----------------------------------------

    /**
     * {@code IntakeWorker.tick} dropped what {@code save} handed back, so every terminal save
     * rebuilt from the {@code PENDING} record and wrote {@code startedAt} back as null —
     * on the completed path as much as the failed one. An operator saw no start time and
     * could compute no duration.
     */
    @Test
    @Timeout(30)
    void aFinishedSweepRecordsWhenItStarted() {
        OpsStore store = new OpsStore();
        worker(store, new OneTicket(), says("Nothing needed doing."))
                .tick("AgentKit", Duration.ofMinutes(60), 10);

        Execution sweep = sweepRowIn(store);
        assertThat(sweep.status()).isEqualTo(Execution.Status.COMPLETED);
        assertThat(sweep.startedAt())
                .as("a finished sweep shows an operator no start time and no duration")
                .isNotNull();
        assertThat(sweep.completedAt()).isNotNull();
        assertThat(sweep.startedAt()).isBeforeOrEqualTo(sweep.completedAt());
    }

    /** The same row on the path #263 added, which rebuilt from the same stale record. */
    @Test
    @Timeout(30)
    void aSweepThatFailedRecordsWhenItStarted() {
        OpsStore store = new OpsStore();
        IntakeWorker worker = worker(store, new ProviderThatIsDown(),
                says("no run should have started"));

        assertThatThrownBy(() -> worker.tick("AgentKit", Duration.ofMinutes(60), 10))
                .isInstanceOf(IllegalStateException.class);

        Execution sweep = sweepRowIn(store);
        assertThat(sweep.status()).isEqualTo(Execution.Status.FAILED);
        assertThat(sweep.startedAt())
                .as("a failed sweep shows an operator no start time and no duration")
                .isNotNull();
    }

    /**
     * The third writer, at a site #267 did not name. {@code WorkflowRunner} dropped the same
     * return value on the same line, so its rows had the same null.
     */
    @Test
    @Timeout(30)
    void aWorkflowRunRecordsWhenItStarted() {
        OpsStore store = new OpsStore();
        WorkflowRunner.Result result = new WorkflowRunner(store, new OneTicket(),
                new DirectoryConnector(), new IdentityConnector(), "agentkit-integration", Risk.HIGH)
                .run(TENANT, new Workflow("probe", 1, "Probe", "One step.",
                        List.of(Workflow.Node.start(),
                                Workflow.Node.tool("step", "Read it", "ticketing.get_ticket",
                                        Map.of("ticket_id", "INC1")),
                                Workflow.Node.end("done", "Done")),
                        List.of(Workflow.Edge.of("start", "step"),
                                Workflow.Edge.of("step", "done")),
                        true), Map.of());

        assertThat(result.execution().status()).isEqualTo(Execution.Status.COMPLETED);
        assertThat(result.execution().startedAt())
                .as("a finished workflow run shows an operator no start time")
                .isNotNull();
    }

    /**
     * A per-ticket run's own row, which {@code ExecutionRunner} always built from the saved
     * {@code RUNNING} record: the control that says this suite is measuring the drop rather
     * than something about {@code withStatus}.
     */
    @Test
    @Timeout(30)
    void aChatRunAlreadyRecordedWhenItStarted() {
        OpsStore store = new OpsStore();
        ExecutionRunner.Outcome outcome = runOneChat(store, says("Nothing needed doing."));

        assertThat(outcome.execution().startedAt()).isNotNull();
    }

    // ---- harness ---------------------------------------------------------------------

    /** A stand-in that says one thing and stops. */
    private static LlmClient says(String text) {
        return request -> LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.END_TURN, TokenUsage.ZERO);
    }

    private static ExecutionRunner runner(OpsStore store, TicketProvider tickets, LlmClient llm) {
        return new ExecutionRunner(store, llm, "scripted", tickets, new DirectoryConnector(),
                new IdentityConnector(), "agentkit-integration", Reviewers.goalAlignment(),
                Risk.HIGH);
    }

    private static IntakeWorker worker(OpsStore store, TicketProvider tickets, LlmClient llm) {
        return new IntakeWorker(store, tickets, runner(store, tickets, llm), TENANT,
                "it-ops-agent");
    }

    private static ExecutionRunner.Outcome runOneChat(OpsStore store, LlmClient llm) {
        Execution work = store.createExecution(TENANT, "it-ops-agent", Execution.Trigger.CHAT,
                "conversation-1", "Work INC1.");
        return runner(store, new OneTicket(), llm).run(work, null);
    }

    private static List<Execution.Event.Type> typesOf(OpsStore store,
            ExecutionRunner.Outcome outcome) {
        return store.events(outcome.execution().id()).stream()
                .map(Execution.Event::type).toList();
    }

    /** The detail on the terminal failure row, or an empty map if there is not one. */
    private static Map<String, Object> failureDetail(OpsStore store,
            ExecutionRunner.Outcome outcome) {
        return store.events(outcome.execution().id()).stream()
                .filter(event -> event.type() == Execution.Event.Type.EXECUTION_FAILED)
                .reduce((first, second) -> second)
                .map(Execution.Event::detail)
                .orElse(Map.of());
    }

    private static Execution sweepRowIn(OpsStore store) {
        return store.executions(TENANT).stream()
                .filter(execution -> execution.triggerReference().startsWith("intake:"))
                .findFirst().orElseThrow();
    }

    /** A provider holding exactly one recent ticket, so a sweep has one claim to make. */
    private static final class OneTicket implements TicketProvider {
        private final Ticket only = new Ticket("INC1", "servicenow", "Access request",
                "Please add me to Contractors.", Ticket.Status.OPEN, "AgentKit", null,
                Instant.now(), Instant.now(), List.of());

        @Override public String name() {
            return "servicenow";
        }

        @Override public List<Ticket> searchRecent(String group, Duration lookback, int limit) {
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

        @Override public Ticket assign(String id, String assignee, String group) {
            return only;
        }

        @Override public Ticket comment(String id, String author, String body) {
            return only;
        }

        @Override public Ticket updateStatus(String id, Ticket.Status status) {
            return only;
        }
    }

    /** A provider whose every read throws, so the sweep fails before it claims anything. */
    private static final class ProviderThatIsDown implements TicketProvider {
        private <T> T down() {
            throw new IllegalStateException("the connector is down");
        }

        @Override public String name() {
            return "servicenow";
        }

        @Override public List<Ticket> searchRecent(String group, Duration lookback, int limit) {
            return down();
        }

        @Override public List<Ticket> search(String query, int limit) {
            return down();
        }

        @Override public Optional<Ticket> get(String id) {
            return down();
        }

        @Override public List<Ticket.Comment> comments(String id) {
            return down();
        }

        @Override public Ticket assign(String id, String assignee, String group) {
            return down();
        }

        @Override public Ticket comment(String id, String author, String body) {
            return down();
        }

        @Override public Ticket updateStatus(String id, Ticket.Status status) {
            return down();
        }
    }
}
