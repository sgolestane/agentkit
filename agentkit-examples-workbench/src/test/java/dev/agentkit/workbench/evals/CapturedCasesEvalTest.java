package dev.agentkit.workbench.evals;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.eval.CaseReport;
import dev.agentkit.eval.Check;
import dev.agentkit.eval.CheckOutcome;
import dev.agentkit.eval.Checks;
import dev.agentkit.eval.EvalCase;
import dev.agentkit.eval.EvalHarness;
import dev.agentkit.eval.EvalReport;
import dev.agentkit.workbench.WorkbenchApp;
import dev.agentkit.workbench.capture.EvalCaptures;
import dev.agentkit.workbench.capture.EvalCaptures.CapturedCase;
import dev.agentkit.workbench.connector.HttpTransport;
import dev.agentkit.workbench.connector.JiraClient;
import dev.agentkit.workbench.domain.Approval;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.domain.Ticket;
import dev.agentkit.workbench.runtime.AnswerBox;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.RunContext;
import dev.agentkit.workbench.runtime.Workbench;
import dev.agentkit.workbench.sim.JiraSimulator;
import dev.agentkit.workbench.store.WorkbenchStore;
import dev.agentkit.workbench.tools.WorkbenchTools;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Replays the eval cases the product captured from live runs — the regression suite that
 * writes itself.
 *
 * <p>{@code EvalCaptures} snapshots a finished run as facts: the ticket as filed, the
 * knowledge the run had, the mode, and how it ended. This suite loads every case under
 * {@code WORKBENCH_EVAL_DIR} (default {@code data/evals}), seeds an empty {@link JiraSimulator}
 * with exactly that ticket, replays through the shipped wiring, and derives the checks from
 * the captured facts:
 *
 * <ul>
 *   <li>the original raised a <strong>question</strong> → the replay parks on
 *       {@code ask_human}, world untouched;</li>
 *   <li>the original was <strong>supervised</strong> (parked on an action, or completed a
 *       ladder a person approved) → a from-scratch replay parks for a person before
 *       anything changes;</li>
 *   <li>a completed <strong>preview</strong> → the replay changes nothing;</li>
 *   <li>a completed <strong>AUTO</strong> run → the replay completes, performs the writes
 *       the original performed, and leaves the ticket in the same status category.</li>
 * </ul>
 *
 * <p>Derived at replay rather than stored as expectations, so the derivation can improve
 * without invalidating captured data. Skips — stated, not passed — when no model is
 * configured or nothing has been captured.
 *
 * <pre>{@code
 * WORKBENCH_LLM=… WORKBENCH_EVAL_DIR=path/to/evals \
 *   ./mvnw -pl agentkit-examples-workbench test -Dtest=CapturedCasesEvalTest
 * }</pre>
 */
class CapturedCasesEvalTest {

    private static final String TENANT = "default";

    @Test
    void everyCapturedRunStillHoldsOnReplay() throws Exception {
        Optional<WorkbenchApp.Backend> configured = WorkbenchApp.realModel();
        Assumptions.assumeTrue(configured.isPresent(),
                "no model configured — set WORKBENCH_LLM and its key; a captured case replayed "
                        + "against a scripted stand-in would measure the fixture.");
        Path dir = Path.of(System.getenv().getOrDefault("WORKBENCH_EVAL_DIR", "data/evals"));
        List<CapturedCase> cases = EvalCaptures.load(dir);
        Assumptions.assumeTrue(!cases.isEmpty(),
                "no captured cases under " + dir + " — run the workbench, work a ticket, and "
                        + "click 'Save as eval' on the run (or POST /api/runs/{id}/capture).");

        List<CaseReport> reports = new ArrayList<>(cases.size());
        for (CapturedCase captured : cases) {
            reports.add(replay(configured.get(), captured));
        }
        EvalReport report = new EvalReport(reports);

        assertThat(report.total()).isEqualTo(cases.size());
        assertThat(report.failures())
                .as("%s", report.summary())
                .extracting(CaseReport::caseId)
                .isEmpty();
    }

    /** One captured case on a world holding exactly what the original run saw. */
    private CaseReport replay(WorkbenchApp.Backend backend, CapturedCase captured)
            throws Exception {
        JiraSimulator simulator = JiraSimulator.empty(0);
        try {
            simulator.seedIssue(captured.ticketKey(), captured.summary(),
                    captured.description(), captured.priority(), captured.reporter());
            for (EvalCaptures.CapturedComment comment : captured.comments()) {
                simulator.seedComment(captured.ticketKey(), comment.author(), comment.body());
            }
            simulator.start();

            JiraClient jira = new JiraClient(HttpTransport.overTheWire(), simulator.baseUrl(),
                    "replay@example.com", "any-token", null);
            WorkbenchStore store = new WorkbenchStore();
            Learnings learnings = new Learnings(MemoryStore.inMemory(), TENANT);
            captured.learnings().forEach(learnings::record);
            Workbench workbench = new Workbench(store, backend.llm(), backend.model(), jira,
                    learnings, TENANT);

            Ticket ticket = jira.ticket(captured.ticketKey()).orElseThrow();
            Goal goal = Goal.of(workbench.goalFor(ticket, captured.mode()));
            Run running = store.save(store
                    .createRun(TENANT, captured.ticketKey(), captured.mode(),
                            Run.Trigger.OPERATOR, goal.description())
                    .withStatus(Run.Status.RUNNING));
            RunContext context = new RunContext(TENANT, running.id(), store);
            context.fact("ticketKey", captured.ticketKey());

            EvalHarness harness = new EvalHarness(observer -> workbench.agentFor(running,
                    context, List.of(), AnswerBox.EMPTY, observer));
            return harness.runCase(new EvalCase("captured:" + captured.runId(), goal,
                    checksFor(captured, jira, store)));
        } finally {
            simulator.close();
        }
    }

    /** The checks a captured run's facts imply — see the class note for the mapping. */
    private List<Check> checksFor(CapturedCase captured, JiraClient jira,
            WorkbenchStore store) {
        String key = captured.ticketKey();
        if ("QUESTION".equals(captured.parkedKind())) {
            return List.of(
                    Checks.parkedOn(WorkbenchTools.ASK_HUMAN),
                    untouched(jira, key));
        }
        if (captured.mode() == Run.Mode.PREVIEW) {
            return List.of(
                    Checks.completed(),
                    Checks.didNotUseTool("jira.add_comment"),
                    Checks.didNotUseTool("jira.assign_to_me"),
                    Checks.didNotUseTool("jira.transition_ticket"),
                    untouched(jira, key));
        }
        if (captured.mode() == Run.Mode.AUTO
                && captured.finalStatus() == Run.Status.COMPLETED) {
            List<Check> checks = new ArrayList<>();
            checks.add(Checks.completed());
            captured.writesThatRan().stream().distinct().forEach(write ->
                    checks.add(Checks.usedTool(write)));
            checks.add(Checks.worldState(key + " ends where the original left it ("
                            + captured.finalTicketCategory() + ")",
                    () -> jira.ticket(key).orElseThrow(),
                    ticket -> ticket.statusCategory().name()
                            .equals(captured.finalTicketCategory())));
            return checks;
        }
        // Everything else the capture admits is supervised work: an action parked, or a
        // ladder a person walked. From scratch, both must park before anything changes.
        return List.of(
                parked(),
                untouched(jira, key),
                Checks.worldState("the pending item is an action",
                        () -> store.approvals(TENANT).stream()
                                .filter(approval -> approval.state() == Approval.State.PENDING)
                                .toList(),
                        pending -> !pending.isEmpty()
                                && pending.get(0).kind() == Approval.Kind.ACTION));
    }

    private static Check parked() {
        return run -> run.result().isAwaitingApproval()
                ? CheckOutcome.pass("parked")
                : CheckOutcome.fail("parked", "the run ended " + run.result().stopReason()
                        + " without asking anybody");
    }

    private static Check untouched(JiraClient jira, String key) {
        return Checks.worldState(key + " is untouched",
                () -> jira.ticket(key).orElseThrow(),
                ticket -> "To Do".equals(ticket.status())
                        && ticket.assignee() == null
                        && jira.comments(key).isEmpty());
    }
}
