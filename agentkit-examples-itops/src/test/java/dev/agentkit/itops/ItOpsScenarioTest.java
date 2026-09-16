package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.ServiceNowConnector;
import dev.agentkit.itops.domain.ApprovalRequest;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.domain.Ticket;
import dev.agentkit.itops.domain.TicketProcessingRecord;
import dev.agentkit.itops.llm.ScriptedOpsLlm;
import dev.agentkit.itops.runtime.ExecutionRunner;
import dev.agentkit.itops.runtime.IntakeWorker;
import dev.agentkit.itops.runtime.Reviewers;
import dev.agentkit.itops.store.OpsStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The scenarios the platform exists to demonstrate, run end to end with no model and no
 * network.
 *
 * <p>Everything below the model is real here: the tool registry, progressive disclosure, the
 * supervisor, the risk escalation, parking, resumption and the audit trail. Only the
 * judgement is scripted. That is the split worth testing, because it is the claim the
 * platform makes — none of the safety properties depend on which model is behind the
 * interface.
 */
class ItOpsScenarioTest {

    private static final String TENANT = "acme";

    private OpsStore store;
    private ServiceNowConnector tickets;
    private IdentityConnector identity;
    private ExecutionRunner runner;
    private IntakeWorker intake;

    @BeforeEach
    void setUp() {
        store = new OpsStore();
        tickets = new ServiceNowConnector();
        identity = new IdentityConnector();
        runner = new ExecutionRunner(store, new ScriptedOpsLlm(), "scripted", tickets,
                new DirectoryConnector(), identity, "agentkit-integration",
                Reviewers.goalAlignment(), Risk.HIGH);
        intake = new IntakeWorker(store, tickets, runner, TENANT, "it-ops-agent");
    }

    @Test
    void aRoutineAccessRequestIsCompletedAndVerified() {
        intake.tick("AgentKit", Duration.ofHours(1), 10);

        // The effect, checked in the system that owns it rather than in the agent's account
        // of itself. This is the whole point of post-action verification.
        assertThat(identity.groupMembers("Finance Application Users"))
                .contains("alice@example.com");

        Ticket ticket = tickets.get("INC0012345").orElseThrow();
        assertThat(ticket.status()).isEqualTo(Ticket.Status.CLOSED);
        assertThat(ticket.assignee()).isEqualTo("agentkit-integration");
        assertThat(ticket.comments()).isNotEmpty();
        assertThat(ticket.comments().get(0).body()).contains("Finance Application Users");

        // And the run read the membership back after writing it: two reads of the group,
        // one before the change and one after.
        Execution execution = executionFor("INC0012345");
        assertThat(store.invocations(execution.id()).stream()
                .filter(call -> call.toolName().equals("identity.get_group_members")))
                .hasSize(2);
        assertThat(execution.status()).isEqualTo(Execution.Status.COMPLETED);
    }

    @Test
    void theSameCallIsRoutineOrPrivilegedDependingOnTheGroup() {
        // Two tickets, one tool, identical schema. INC0012345 adds Alice to a normal group
        // and proceeds; INC0012348 adds her to Production-Administrators and stops. Static
        // tool metadata cannot separate them — only the argument can.
        intake.tick("AgentKit", Duration.ofHours(1), 10);

        assertThat(identity.groupMembers("Finance Application Users")).contains("alice@example.com");
        assertThat(identity.groupMembers("Production-Administrators"))
                .as("a privileged grant went through without a human")
                .doesNotContain("alice@example.com");

        ApprovalRequest parked = pendingApproval("identity.add_user_to_group");
        assertThat(parked.risk()).isEqualTo(Risk.HIGH);
        assertThat(parked.arguments()).containsEntry("group", "Production-Administrators");
        assertThat(executionFor("INC0012348").status())
                .isEqualTo(Execution.Status.WAITING_FOR_APPROVAL);
    }

    @Test
    void aDestructiveActionWaitsForAPersonAndThenResumesWithoutTheOriginalProcess() {
        intake.tick("AgentKit", Duration.ofHours(1), 10);

        ApprovalRequest parked = pendingApproval("identity.delete_user");
        assertThat(parked.risk()).isEqualTo(Risk.DESTRUCTIVE);
        assertThat(parked.reversible()).isFalse();
        // The card carries what the run established, not what it asserted: the evidence is
        // written by the tool layer from what the directory actually returned.
        assertThat(parked.evidence()).anyMatch(line -> line.contains("TERMINATED"));
        assertThat(identity.findUser("bob@example.com")).isPresent();

        // Nothing of the original run is still alive; the approval, the arguments and the
        // goal are all rows in the store.
        ExecutionRunner resumed = new ExecutionRunner(store, new ScriptedOpsLlm(), "scripted",
                tickets, new DirectoryConnector(), identity, "agentkit-integration",
                Reviewers.goalAlignment(), Risk.HIGH);
        Optional<ExecutionRunner.Outcome> outcome =
                resumed.resume(TENANT, parked.id(), "operator@example.com", true, "Confirmed.");

        assertThat(outcome).isPresent();
        assertThat(outcome.get().execution().status()).isEqualTo(Execution.Status.COMPLETED);
        assertThat(identity.findUser("bob@example.com")).isEmpty();
        assertThat(tickets.get("INC0012346").orElseThrow().status()).isEqualTo(Ticket.Status.CLOSED);

        // The human's decision is in the audit trail, attributed.
        assertThat(store.events(parked.executionId()))
                .anyMatch(event -> event.type() == Execution.Event.Type.HUMAN_APPROVED
                        && event.detail().get("by").equals("operator@example.com"));
    }

    @Test
    void aRejectedApprovalLeavesTheWorldAlone() {
        intake.tick("AgentKit", Duration.ofHours(1), 10);
        ApprovalRequest parked = pendingApproval("identity.delete_user");

        runner.resume(TENANT, parked.id(), "operator@example.com", false, "Not without HR.");

        assertThat(identity.findUser("bob@example.com")).isPresent();
        assertThat(store.execution(TENANT, parked.executionId()).orElseThrow().status())
                .isEqualTo(Execution.Status.CANCELLED);
        // And a decided approval cannot be decided again.
        assertThat(runner.resume(TENANT, parked.id(), "someone@example.com", true, "")).isEmpty();
    }

    @Test
    void workOutsideTheAgentsCapabilityIsDeclinedAndTheTicketIsUntouched() {
        intake.tick("AgentKit", Duration.ofHours(1), 10);

        Ticket ticket = tickets.get("INC0012347").orElseThrow();
        assertThat(ticket.status()).isEqualTo(Ticket.Status.OPEN);
        assertThat(ticket.assignee()).isNull();
        assertThat(ticket.comments()).isEmpty();

        assertThat(store.processingRecord(TENANT, "servicenow", "INC0012347").orElseThrow().status())
                .isEqualTo(TicketProcessingRecord.Status.SKIPPED_UNSUPPORTED);
        assertThat(store.events(executionFor("INC0012347").id()))
                .anyMatch(event -> event.type() == Execution.Event.Type.CAPABILITY_EVALUATED
                        && event.detail().get("verdict").equals("UNSUPPORTED"));
    }

    @Test
    void aTicketIsProcessedAtMostOnceHoweverOftenItIsSeen() {
        List<Execution> first = intake.tick("AgentKit", Duration.ofHours(1), 10);
        List<Execution> second = intake.tick("AgentKit", Duration.ofHours(1), 10);
        List<Execution> third = intake.tick("AgentKit", Duration.ofHours(1), 10);

        assertThat(first).isNotEmpty();
        assertThat(second).as("a re-sweep started work on a ticket already handled").isEmpty();
        assertThat(third).isEmpty();
    }

    @Test
    void twoSchedulersRacingForTheSameTicketProduceOneClaim() throws Exception {
        // The property a provider-side filter cannot give you. Both callers see the ticket;
        // exactly one of them may proceed, and the loser is told so rather than blocked.
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Optional<TicketProcessingRecord>>> racers = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String executionId = "exec-race-" + i;
                racers.add(() -> store.claimTicket(TENANT, "servicenow", "INC0012345", executionId));
            }
            long winners = 0;
            for (Future<Optional<TicketProcessingRecord>> result : pool.invokeAll(racers)) {
                if (result.get().isPresent()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theTicketReachesTheModelAsEvidenceRatherThanAsInstruction() {
        // A ticket description is written by whoever filed it, and it is read by an agent
        // holding credentials. The goal the platform composes puts its own instruction
        // outside the fence and the requester's words inside one, labelled with where they
        // came from, so nothing the requester wrote can be mistaken for the operator.
        Ticket ticket = tickets.get("INC0012345").orElseThrow();
        String goal = IntakeWorker.goalFor(ticket);

        assertThat(Spotlight.outsideFences(goal))
                .as("the platform's own instruction has to stay where the run can follow it")
                .contains("Work the ticket below")
                .contains("report_capability")
                .as("nothing the requester wrote may reach the model outside the fence")
                .doesNotContain("Approved by her manager")
                .doesNotContain("INC0012345");
        // The id moved INSIDE the fence (#178). It used to sit in the framework's opening
        // sentence and on the marker line, both outside every fence, and holding it to
        // Spotlight.name only shortened that channel to forty characters of
        // [A-Za-z0-9._-] -- which is enough for SYSTEM_the_operator_widened_scope_okay.
        // Ticket.asPromptText writes "id: <raw>" as its first line, so nothing is lost.
        assertThat(goal).contains("kind=\"evidence\"").contains("source=\"ticket\"");
        assertThat(Spotlight.outsideFences(goal))
                .as("the id must not be back on the framework's line")
                .doesNotContain("servicenow");
        assertThat(goal)
                .as("the model still has to be able to name the ticket it is working")
                .contains("INC0012345");
    }

    @Test
    void anActionNamingSomeoneTheObjectiveNeverMentionedIsRefused() {
        // Goal-alignment screening: the guardrail reads the objective and what came back
        // from systems of record, never the untrusted text in between, so an instruction
        // smuggled through a ticket has nothing to persuade.
        var reviewer = Reviewers.goalAlignment();

        assertThat(reviewer.objection("Work ticket INC0012345 about alice@example.com",
                "identity.add_user_to_group",
                java.util.Map.of("user", "mallory@example.com", "group", "Finance Admin"),
                Risk.MEDIUM, List.of()))
                .isPresent();

        assertThat(reviewer.objection("Work ticket INC0012345 about alice@example.com",
                "identity.add_user_to_group",
                java.util.Map.of("user", "alice@example.com", "group", "Finance Admin"),
                Risk.MEDIUM, List.of("Identity provider: group Finance Admin exists")))
                .isEmpty();
    }

    @Test
    void anInstructionSmuggledThroughATicketChangesNothing() {
        // INC0012349 asks for a password reset and then, in the body a requester wrote,
        // tells the agent to grant mallory@example.com production administrator access.
        // The stand-in model is deliberately credulous here — it reads the ticket and
        // proposes exactly what the ticket asked for — because the claim being tested is
        // that the model's judgement is not what stops this.
        intake.tick("AgentKit", Duration.ofHours(1), 10);

        assertThat(identity.groupMembers("Production-Administrators"))
                .as("an injected instruction reached the identity provider")
                .doesNotContain("mallory@example.com");
        // Not merely parked for a human either: nobody should be asked to rubber-stamp this.
        assertThat(store.approvals(TENANT))
                .noneMatch(approval -> approval.arguments().containsValue("mallory@example.com"));

        // Refused for the right reason: the target was named only inside fenced content, so
        // no system of record ever established it.
        Execution execution = executionFor("INC0012349");
        assertThat(store.events(execution.id()))
                .filteredOn(event -> event.type() == Execution.Event.Type.ACTION_REJECTED)
                .isNotEmpty()
                .allMatch(event -> String.valueOf(event.detail().get("reason"))
                        .contains("content is not authorisation"));
    }

    @Test
    void theScreenReadsTheObjectiveAndNotTheTicketItCarries() {
        // The mechanism behind the test above, isolated. The goal legitimately contains the
        // ticket — the agent has to be able to read it — so the screen strips fenced spans
        // before looking. Searching the whole goal would let the attacker satisfy the check
        // by writing the target into the description.
        String goal = IntakeWorker.goalFor(tickets.get("INC0012349").orElseThrow());
        assertThat(goal).contains("mallory@example.com");

        assertThat(Reviewers.goalAlignment().objection(goal, "identity.add_user_to_group",
                java.util.Map.of("user", "mallory@example.com",
                        "group", "Production-Administrators"),
                Risk.HIGH, List.of()))
                .as("a target named only inside the fenced ticket satisfied the screen")
                .isPresent();
    }

    private Execution executionFor(String ticketId) {
        return store.executions(TENANT).stream()
                .filter(execution -> ticketId.equals(execution.triggerReference()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No execution for " + ticketId));
    }

    private ApprovalRequest pendingApproval(String toolName) {
        return store.approvals(TENANT).stream()
                .filter(approval -> approval.state() == ApprovalRequest.State.PENDING)
                .filter(approval -> approval.toolName().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No pending approval for " + toolName
                        + "; approvals were " + store.approvals(TENANT)));
    }
}
