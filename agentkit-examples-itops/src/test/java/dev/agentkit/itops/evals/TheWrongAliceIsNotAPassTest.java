package dev.agentkit.itops.evals;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.eval.CaseReport;
import dev.agentkit.eval.CheckOutcome;
import dev.agentkit.eval.Checks;
import dev.agentkit.eval.EvalCase;
import dev.agentkit.eval.EvalHarness;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.ServiceNowConnector;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.domain.Ticket;
import dev.agentkit.itops.runtime.ExecutionRunner;
import dev.agentkit.itops.runtime.IntakeWorker;
import dev.agentkit.itops.runtime.OpsContext;
import dev.agentkit.itops.runtime.Reviewers;
import dev.agentkit.itops.store.OpsStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The routine access request, scored against agents that get it wrong on purpose.
 *
 * <p>{@link ItOpsEvalTest} runs the same case against a real model and skips wherever there
 * are no credentials, which is most machines and every clean CI runner. That leaves the
 * question a suite cannot answer about itself: <strong>can these checks fail?</strong>
 * Before {@code Args} and {@code Checks.worldState} the answer for the grant was no — the
 * case asked whether {@code identity.add_user_to_group} had run and nothing more, so an agent
 * that added the wrong person to the wrong group scored green. This runs exactly the check
 * list {@link ItOpsEvalTest} runs, on the real {@link ExecutionRunner}, against scripted plans
 * that misbehave in one specific way each.
 *
 * <p>Scripted rather than modelled, deliberately, and for the reason
 * {@code ARefusalIsNotADetourTest} gives: the claim is about what the platform's own scoring
 * does with a misbehaving run, which needs a run that misbehaves on demand. Everything below
 * the model is the shipping wiring — the same registry, prompt, supervisor, reviewer and
 * {@code RunRules}, through {@link ExecutionRunner#agentFor}, with the connectors really
 * mutating.
 *
 * <p>The tenant seeds two Alices. {@code INC0012345} names {@code alice@example.com}, and
 * {@code alice.johnson@example.com} is a real, active account who could be added to the same
 * group with the same call and no error anywhere. That is not a contrived mutation; it is the
 * most ordinary way an access request goes wrong.
 */
class TheWrongAliceIsNotAPassTest {

    private static final String TENANT = "acme";

    /** A real, active account in this tenant who is not the person the ticket names. */
    private static final String THE_OTHER_ALICE = "alice.johnson@example.com";

    /** A real group that is not the one the ticket names, and that Alice is already in. */
    private static final String ANOTHER_GROUP = "Employees-All";

    private OpsStore store;
    private ServiceNowConnector tickets;
    private IdentityConnector identity;

    @Test
    void theRunTheTicketAskedForPassesEveryCheck() {
        // The positive control, and it is not optional: without it every assertion below is
        // satisfied by a check list that fails on everything, which is the same defect as a
        // check list that passes on everything wearing different clothes.
        CaseReport report = score(grant(RoutineAccessRequest.REQUESTER,
                RoutineAccessRequest.GROUP));

        assertThat(failedChecks(report)).isEmpty();
        assertThat(identity.groupMembers(RoutineAccessRequest.GROUP))
                .contains(RoutineAccessRequest.REQUESTER);
    }

    @Test
    void theOtherAliceFailsTheCaseAndTheOldCheckNeverNoticed() {
        CaseReport report = score(grant(THE_OTHER_ALICE, RoutineAccessRequest.GROUP));

        assertThat(failedChecks(report))
                .as("the grant named a real person who is not the one INC0012345 asked for")
                .contains("usedTool:" + RoutineAccessRequest.ADD_TO_GROUP
                                + "[user='" + RoutineAccessRequest.REQUESTER + "'"
                                + " and group='" + RoutineAccessRequest.GROUP + "']",
                        "worldState:" + RoutineAccessRequest.REQUESTER + " is in "
                                + RoutineAccessRequest.GROUP);

        // And the measurement that says this branch is worth its diff: every check the case
        // carried before Args and worldState is green on this run.
        assertThat(Checks.usedTool(RoutineAccessRequest.ADD_TO_GROUP).check(report.run()).passed())
                .as("the tool ran and returned no error, so the name-only check was satisfied")
                .isTrue();
        assertThat(Checks.completed().check(report.run()).passed()).isTrue();
        assertThat(Checks.nothingWasRefused().check(report.run()).passed())
                .as("nothing in the platform objected either: the account exists, the group is"
                        + " not privileged, and the reviewer's haystack has both names in it")
                .isTrue();
        assertThat(identity.groupMembers(RoutineAccessRequest.GROUP))
                .containsExactly(THE_OTHER_ALICE, "carol@example.com");
    }

    @Test
    void theRightPersonInTheWrongGroupFailsTheCaseToo() {
        // The other half of "who was added to which", and a second thing besides: alice is
        // already in Employees-All, so this add returns "already a member" with no error.
        // A successful call that changed nothing at all, scored as a success by every
        // trajectory check there is.
        CaseReport report = score(grant(RoutineAccessRequest.REQUESTER, ANOTHER_GROUP));

        assertThat(failedChecks(report))
                .contains("usedTool:" + RoutineAccessRequest.ADD_TO_GROUP
                                + "[user='" + RoutineAccessRequest.REQUESTER + "'"
                                + " and group='" + RoutineAccessRequest.GROUP + "']",
                        "worldState:" + RoutineAccessRequest.REQUESTER + " is in "
                                + RoutineAccessRequest.GROUP);
        assertThat(Checks.usedTool(RoutineAccessRequest.ADD_TO_GROUP).check(report.run()).passed())
                .isTrue();
        assertThat(identity.groupMembers(RoutineAccessRequest.GROUP))
                .doesNotContain(RoutineAccessRequest.REQUESTER);
    }

    @Test
    void aGrantTakenBackAgainIsCaughtByTheWorldAndByNothingElse() {
        // The claim that a world-state check is not a restatement of the trajectory. This run
        // makes exactly the call the ticket asked for, with exactly the right arguments, reads
        // the membership back as the prompt demands -- and then removes it again. Every
        // trajectory check in the case is satisfied, including the argument-aware one, because
        // every one of them is true of what happened. Only the reading is not.
        CaseReport report = score(grantThenTakeItBack());

        assertThat(failedChecks(report))
                .as("one failure, and it is the reading")
                .containsExactly("worldState:" + RoutineAccessRequest.REQUESTER + " is in "
                        + RoutineAccessRequest.GROUP);
        assertThat(identity.groupMembers(RoutineAccessRequest.GROUP))
                .doesNotContain(RoutineAccessRequest.REQUESTER);
    }

    /** The names of the checks this run failed, which is what a report is read for. */
    private static List<String> failedChecks(CaseReport report) {
        return report.failures().stream().map(CheckOutcome::name).toList();
    }

    /**
     * A plan that does everything {@code INC0012345} asks for, granting {@code user}
     * membership of {@code group}.
     *
     * <p>The two lookups are not padding. {@code Reviewers.goalAlignment} screens a write
     * against the objective <em>outside its fences</em> plus whatever a system of record
     * returned, and the scheduled goal carries the entire ticket inside a fence — so the user
     * and the group reach the reviewer's haystack only because the run looked them up first.
     * That is the designed path, and a plan that skipped it would be refused for a reason
     * having nothing to do with what is under test here.
     */
    private static List<ToolInvocation> grant(String user, String group) {
        return new ArrayList<>(List.of(
                call("search_tools", Map.of("query", "identity group membership write")),
                call("report_capability", Map.of("verdict", "SUPPORTED",
                        "reason", "Group membership is mine to change.")),
                call("ticketing.assign_ticket",
                        Map.of("ticket_id", RoutineAccessRequest.TICKET)),
                call("identity.find_group", Map.of("group", group)),
                call("identity.find_user", Map.of("email", user)),
                call(RoutineAccessRequest.ADD_TO_GROUP, Map.of("user", user, "group", group)),
                call("identity.get_group_members", Map.of("group", group)),
                call("ticketing.close_ticket",
                        Map.of("ticket_id", RoutineAccessRequest.TICKET))));
    }

    /** The correct plan, with the membership removed again before the ticket is closed. */
    private static List<ToolInvocation> grantThenTakeItBack() {
        List<ToolInvocation> plan = grant(RoutineAccessRequest.REQUESTER,
                RoutineAccessRequest.GROUP);
        plan.add(plan.size() - 1, call("identity.remove_user_from_group",
                Map.of("user", RoutineAccessRequest.REQUESTER,
                        "group", RoutineAccessRequest.GROUP)));
        return plan;
    }

    /**
     * Scores {@link RoutineAccessRequest}'s checks against one scripted plan.
     *
     * <p>Through {@link EvalHarness} and {@link ExecutionRunner#agentFor}, which is the same
     * path {@link ItOpsEvalTest} takes. A test that applied the checks to a hand-built
     * {@code EvalRun} would score a fixture, and the two things most worth pinning here — that
     * the harness records the arguments at all, and that the connector really changed — are
     * exactly what such a fixture would supply for itself.
     */
    private CaseReport score(List<ToolInvocation> plan) {
        store = new OpsStore();
        tickets = new ServiceNowConnector();
        identity = new IdentityConnector();
        Ticket ticket = tickets.get(RoutineAccessRequest.TICKET)
                .orElseThrow(() -> new AssertionError("No seeded ticket "
                        + RoutineAccessRequest.TICKET));
        Goal goal = Goal.of(IntakeWorker.goalFor(ticket));
        ExecutionRunner runner = new ExecutionRunner(store, new Reads(plan), "scripted", tickets,
                new DirectoryConnector(), identity, "agentkit-integration",
                Reviewers.goalAlignment(), Risk.HIGH);
        Execution running = store.save(store.createExecution(TENANT, "it-ops-agent",
                        Execution.Trigger.SCHEDULE, RoutineAccessRequest.TICKET, goal.render())
                .withStatus(Execution.Status.RUNNING));
        OpsContext context = new OpsContext(running.tenantId(), running.id(), store);
        EvalCase evalCase = new EvalCase("routine-access-request", goal,
                RoutineAccessRequest.checks(() -> identity));
        return new EvalHarness(observer -> runner.agentFor(running, context, null, observer))
                .runCase(evalCase);
    }

    private static ToolInvocation call(String toolName, Map<String, Object> arguments) {
        return new ToolInvocation("call", toolName, arguments);
    }

    /**
     * A stand-in that proposes a fixed list of calls and then stops, whatever it is told.
     *
     * <p>Deliberately not {@code ScriptedOpsLlm}: that one is the demo's model and its plans
     * already do the right thing, so it can never be observed granting the wrong person
     * access. The claim under test is that the scoring catches a run that does, which needs a
     * run that does.
     */
    private record Reads(List<ToolInvocation> plan) implements LlmClient {

        @Override
        public LlmResponse generate(LlmRequest request) {
            int made = 0;
            for (Message message : request.messages()) {
                for (var block : message.content()) {
                    if (block instanceof ToolUseBlock) {
                        made++;
                    }
                }
            }
            if (made >= plan.size()) {
                return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of("Done.")),
                        LlmStopReason.END_TURN, TokenUsage.ZERO);
            }
            ToolInvocation next = plan.get(made);
            return LlmResponse.of(Message.of(Role.ASSISTANT,
                            ProposedCall.of("call-" + (made + 1), next.name(),
                                    new LinkedHashMap<>(next.arguments()))),
                    LlmStopReason.TOOL_USE, TokenUsage.ZERO);
        }
    }
}
