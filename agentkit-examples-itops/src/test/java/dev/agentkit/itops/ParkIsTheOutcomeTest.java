package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.ServiceNowConnector;
import dev.agentkit.itops.domain.ApprovalRequest;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.runtime.ExecutionRunner;
import dev.agentkit.itops.runtime.OpsContext;
import dev.agentkit.itops.runtime.Reviewers;
import dev.agentkit.itops.runtime.Supervisor;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.tools.ToolCatalog;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The itops supervisor parks in the framework's own words, and the run stops (#157).
 *
 * <h2>What was measured, and on what</h2>
 *
 * <p>This module solved human-in-the-loop approval before AgentKit had an answer, and
 * {@code Supervisor}'s javadoc used to explain the workaround: a gate could not suspend a
 * run, so it raised the approval, set a flag, and denied <em>every</em> later call. The
 * guarantee held — nothing consequential ran without a person — but by refusing everything
 * rather than stopping, so the run kept being asked what to do next.
 *
 * <p>What held the demo together was that the stand-in model took the hint. Measured on the
 * unmigrated supervisor with a stand-in that does not, re-proposing the privileged call
 * after each refusal:
 *
 * <pre>
 *                                deny-everything    needsAPerson
 * model turns                    24                 2
 * privileged calls proposed      23                 1
 * audit rows on the execution    53                 9
 * approval rows raised           1                  1
 * execution status               WAITING_FOR_APPROVAL (both)
 * </pre>
 *
 * <p>The last row is why this was a workaround and not a hole, and the rest is what it cost:
 * a whole step budget spent, and the one row a reviewer must act on buried under 44 rows of
 * a model being told no.
 *
 * <h2>Why the guarantee is tested at the gate as well as through a runner</h2>
 *
 * <p>{@code needsAPerson} makes both runners in this repository end the run on the first
 * park, so nothing here reaches the supervisor's "already parked" branch any more. It is
 * still load-bearing for a runner that reads only {@link GateResult#allowed()} — such a
 * runner keeps looping, and a second privileged proposal would otherwise queue a second
 * {@link ApprovalRequest} for one run. So that branch is driven by calling
 * {@code evaluate} twice directly rather than through a loop that no longer takes it.
 */
class ParkIsTheOutcomeTest {

    private static final String TENANT = "acme";

    /** The gate, and the store it writes to, wired the way {@code ExecutionRunner} wires them. */
    private record Rig(Supervisor supervisor, OpsStore store, String executionId) {

        static Rig create() {
            OpsStore store = new OpsStore();
            IdentityConnector identity = new IdentityConnector();
            Execution execution = store.createExecution(TENANT, "it-ops-agent",
                    Execution.Trigger.CHAT, null, "Grant Alice production administrator access");
            OpsContext context = new OpsContext(TENANT, execution.id(), store);
            return new Rig(new Supervisor(Risk.HIGH, identity, context, store, execution.goal(),
                    null, null), store, execution.id());
        }

        GateResult evaluate(String toolName, Map<String, Object> arguments) {
            Tool tool = ToolCatalog.forExecution(new ServiceNowConnector(), "agentkit-integration",
                            new DirectoryConnector(), new IdentityConnector(),
                            new OpsContext(TENANT, executionId, store), store)
                    .find(toolName).orElseThrow();
            return supervisor.evaluate(tool,
                    new ToolInvocation("call-1", toolName, arguments));
        }

        List<Execution.Event> events() {
            return store.events(executionId);
        }
    }

    private static Map<String, Object> intoProductionAdministrators() {
        return Map.of("user", "alice@example.com", "group", "Production-Administrators");
    }

    @Test
    void aPrivilegedCallAsksForAPersonRatherThanBeingDenied() {
        // The migration in one assertion. Both answers report allowed() == false and both
        // carry the same sentence -- needsAPerson copies the reason into reason() so a
        // runner that predates it still says something true -- and only one of them tells
        // the runner that somebody still owes an answer.
        Rig rig = Rig.create();

        GateResult verdict = rig.evaluate("identity.add_user_to_group",
                intoProductionAdministrators());

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.awaiting())
                .as("the gate denied the call instead of saying a person must decide, so the"
                        + " run was refused rather than stopped")
                .isPresent();
        ApprovalNeeded why = verdict.awaiting().orElseThrow();
        assertThat(why.reason())
                .contains("requires human approval")
                .isEqualTo(verdict.reason());
        assertThat(why.reason())
                .as("the reason reaches the person deciding as well as the model, and telling"
                        + " the reviewer not to look for another route is addressed to the"
                        + " wrong reader")
                .doesNotContain("alternative route");
        assertThat(why.effect())
                .as("an approval card that says only 'run add_user_to_group?' is how"
                        + " rubber-stamping starts")
                .isEqualTo("alice@example.com gains every permission granted by"
                        + " Production-Administrators.");
        assertThat(why.reversible())
                .as("identity.add_user_to_group is a reversible policy row")
                .isTrue();
        assertThat(verdict.replacement())
                .as("this gate narrows nothing, so a park from it carries no replacement")
                .isEmpty();
    }

    @Test
    void anIrreversibleCallIsNotClaimedReversible() {
        // The other side of the conditional, and the direction that matters: reversible
        // defaults to false because that is the answer that makes a reviewer look harder,
        // so a park that flipped it would quietly relax the card for the one operation in
        // the catalogue with no way back.
        Rig rig = Rig.create();

        GateResult verdict = rig.evaluate("identity.delete_user",
                Map.of("email", "bob@example.com"));

        assertThat(verdict.awaiting().orElseThrow().reversible()).isFalse();
        assertThat(verdict.awaiting().orElseThrow().effect())
                .contains("permanently removed");
    }

    @Test
    void theApprovalRowAndItsRichEventStillGetWritten() {
        // The half of the old design that was right, and must not be lost in migrating the
        // half that was not. ApprovalNeeded carries a reason, an effect and a reversibility
        // claim; the row a reviewer opens carries a tenant, an execution, a graded risk and
        // the evidence, and nothing on the framework's side would hold those.
        Rig rig = Rig.create();

        GateResult verdict =
                rig.evaluate("identity.add_user_to_group", intoProductionAdministrators());

        ApprovalRequest request = rig.store().pendingApprovalFor(rig.executionId()).orElseThrow();
        assertThat(verdict.awaiting().orElseThrow().reason())
                .as("the reason is the only thread from the framework's awaiting record back"
                        + " to the row queued here: PendingApproval.ticket() is empty on the"
                        + " in-process runner, so a caller holding an AgentResult and nothing"
                        + " else has this sentence and no other way to name the approval")
                .contains(request.id());
        assertThat(request.risk()).isEqualTo(Risk.HIGH);
        assertThat(request.toolName()).isEqualTo("identity.add_user_to_group");
        assertThat(request.arguments()).containsEntry("group", "Production-Administrators");
        assertThat(request.state()).isEqualTo(ApprovalRequest.State.PENDING);

        List<Execution.Event> requested = rig.events().stream()
                .filter(event -> event.type() == Execution.Event.Type.HUMAN_APPROVAL_REQUESTED)
                .toList();
        assertThat(requested).hasSize(1);
        assertThat(requested.get(0).detail())
                .containsEntry("approvalId", request.id())
                .containsEntry("tool", "identity.add_user_to_group")
                .containsEntry("risk", "HIGH")
                .containsEntry("arguments", request.arguments())
                .containsKey("effect");
    }

    @Test
    void aSecondCallAfterAParkIsRefusedAndQueuesNoSecondApproval() {
        // The backstop, driven directly because no runner in this repository reaches it any
        // more. A runner that reads only allowed() keeps looping after a park, and without
        // this branch the second privileged proposal would put a second row in a reviewer's
        // queue -- two approvals for one run, each authorising a call.
        Rig rig = Rig.create();
        rig.evaluate("identity.add_user_to_group", intoProductionAdministrators());

        GateResult second = rig.evaluate("identity.delete_user",
                Map.of("email", "bob@example.com"));

        assertThat(second.allowed()).isFalse();
        assertThat(second.awaiting())
                .as("asking again would queue a second approval for one decision")
                .isEmpty();
        assertThat(rig.store().approvals(TENANT)).hasSize(1);
        assertThat(rig.events())
                .filteredOn(event -> event.type() == Execution.Event.Type.HUMAN_APPROVAL_REQUESTED)
                .hasSize(1);
    }

    @Test
    void anUncooperativeModelIsStoppedByTheLoopAndNotByItsOwnManners() {
        // The measurement in the class note, as a test. The stand-in ignores every refusal
        // and re-proposes the privileged call; the loop has to be what stops it.
        OpsStore store = new OpsStore();
        Stubborn llm = new Stubborn();
        ExecutionRunner runner = new ExecutionRunner(store, llm, "scripted",
                new ServiceNowConnector(), new DirectoryConnector(), new IdentityConnector(),
                "agentkit-integration", Reviewers.goalAlignment(), Risk.HIGH);
        Execution execution = store.createExecution(TENANT, "it-ops-agent",
                Execution.Trigger.CHAT, null,
                "Add alice@example.com to Production-Administrators");

        ExecutionRunner.Outcome outcome = runner.run(execution, null);

        assertThat(outcome.parked()).isTrue();
        assertThat(outcome.execution().status())
                .isEqualTo(Execution.Status.WAITING_FOR_APPROVAL);
        assertThat(store.events(outcome.execution().id()))
                .filteredOn(event -> event.type() == Execution.Event.Type.TOOL_STARTED
                        && "identity.add_user_to_group".equals(event.detail().get("tool")))
                .as("the run went on proposing the parked action after it had been parked:"
                        + " 23 times before this, once now")
                .hasSize(1)
                // And the row names who proposed it. A park is the case that puts a person
                // in the loop, and the person deciding is owed the name of who asked — which
                // no row carried until #311. One agent per execution here, so it is
                // "executor"; the key is what stops being constant the day one delegates.
                .allSatisfy(event -> assertThat(event.detail())
                        .containsEntry("agent", "executor"));
        assertThat(llm.turns.get())
                .as("the model was asked what to do next after a decision only a person can"
                        + " make: 24 turns before #157, 2 after it, and 3 now — the extra one"
                        + " is the report_capability RunRules requires before a change, paid"
                        + " once per run rather than once per refusal")
                .isEqualTo(3);
        assertThat(store.approvals(TENANT)).hasSize(1);
    }

    @Test
    void aParkedRunNamesItsOwnApprovalWhileAnotherWorkerIsParkingToo() {
        // The lookup is keyed on the execution, and this is the difference that makes.
        // Keying on the tenant and taking the newest row survives every other test here,
        // because runs in a test go one at a time and the newest approval in the tenant is
        // always the one this run just raised. The example is not built that way: OpsStore's
        // claimTicket exists precisely so several IntakeWorkers can compete for one tenant's
        // tickets, so two runs can be between their park and their read at the same moment.
        //
        // Reproduced without threads, by having a second worker park inside the event this
        // run's own park emits -- store.append notifies subscribers synchronously, so the
        // interleaving is deterministic rather than raced.
        OpsStore store = new OpsStore();
        Execution other = store.createExecution(TENANT, "it-ops-agent",
                Execution.Trigger.SCHEDULE, null, "Some other ticket in the same tenant");
        AtomicBoolean intruded = new AtomicBoolean();

        try (AutoCloseable ignored = store.subscribe(event -> {
            if (event.type() == Execution.Event.Type.HUMAN_APPROVAL_REQUESTED
                    && intruded.compareAndSet(false, true)) {
                store.save(new ApprovalRequest(OpsStore.Ids.next("apr"), TENANT, other.id(),
                        new ToolInvocation("call-x", "identity.delete_user",
                                Map.of("email", "carol@example.com")),
                        Risk.DESTRUCTIVE, "Another worker's decision.", "Deletes an account.",
                        false, List.of(), ApprovalRequest.State.PENDING,
                        Instant.now().plusSeconds(1), null, null, null));
            }
        })) {
            ExecutionRunner runner = new ExecutionRunner(store, new Stubborn(), "scripted",
                    new ServiceNowConnector(), new DirectoryConnector(), new IdentityConnector(),
                    "agentkit-integration", Reviewers.goalAlignment(), Risk.HIGH);
            Execution execution = store.createExecution(TENANT, "it-ops-agent",
                    Execution.Trigger.CHAT, null,
                    "Add alice@example.com to Production-Administrators");

            ExecutionRunner.Outcome outcome = runner.run(execution, null);

            assertThat(intruded).as("the interleaving never happened, so this measured nothing")
                    .isTrue();
            ApprovalRequest mine =
                    store.pendingApprovalFor(outcome.execution().id()).orElseThrow();
            assertThat(outcome.execution().summary())
                    .as("the parked row named another execution's approval")
                    .contains(mine.id())
                    .doesNotContain("identity.delete_user");
            assertThat(store.events(outcome.execution().id()))
                    .filteredOn(event -> event.type() == Execution.Event.Type.EXECUTION_PARKED)
                    .singleElement()
                    .satisfies(event -> assertThat(event.detail())
                            .containsEntry("approvalId", mine.id())
                            .containsEntry("tool", "identity.add_user_to_group"));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void aRunThatMerelyRanOutOfStepsIsNotFiledAsWaitingForAPerson() {
        // AWAITING_APPROVAL and nothing else. The branch reads a stop reason now, so which
        // reasons mean "a person owes an answer" is a decision this runner makes, and a
        // looser test -- anything that did not complete -- files a run that simply ran long
        // as one a reviewer must act on, then fails looking for the approval row it has not
        // got.
        OpsStore store = new OpsStore();
        ExecutionRunner runner = new ExecutionRunner(store, new Tireless(), "scripted",
                new ServiceNowConnector(), new DirectoryConnector(), new IdentityConnector(),
                "agentkit-integration", Reviewers.goalAlignment(), Risk.HIGH);
        Execution execution = store.createExecution(TENANT, "it-ops-agent",
                Execution.Trigger.CHAT, null, "Look at INC0012345 for a while");

        ExecutionRunner.Outcome outcome = runner.run(execution, null);

        assertThat(outcome.parked()).isFalse();
        // FAILED, and this pinned COMPLETED until #265. The correction is worth stating
        // rather than quietly editing: what this test is for is that a long run is not filed
        // as one a reviewer must act on, and COMPLETED was never that claim — it was the
        // value the runner happened to write, because it branched on AWAITING_APPROVAL and
        // filed every other stop reason as finished work. MAX_STEPS means the run stopped
        // before the work was done, so the row that says so is FAILED; ExecutionRunner's
        // statusFor argues that choice and what it costs. The park assertions below are the
        // ones that carry this test's own claim, and they are unchanged.
        assertThat(outcome.execution().status())
                .as("a run that exhausted its step budget was filed as waiting for a person,"
                        + " or as work that finished")
                .isEqualTo(Execution.Status.FAILED);
        assertThat(store.approvals(TENANT)).isEmpty();
        assertThat(store.events(outcome.execution().id()))
                .filteredOn(event -> event.type() == Execution.Event.Type.EXECUTION_PARKED)
                .isEmpty();
    }

    /**
     * A stand-in that reads the same ticket for ever: it never proposes anything the
     * supervisor cares about, so the run ends on {@code MAX_STEPS} and on nothing else.
     */
    private static final class Tireless implements LlmClient {

        private final AtomicInteger turns = new AtomicInteger();

        @Override
        public LlmResponse generate(LlmRequest request) {
            int turn = turns.incrementAndGet();
            return LlmResponse.of(
                    Message.of(Role.ASSISTANT, ProposedCall.of("call-" + turn,
                            "ticketing.get_ticket", Map.of("ticket_id", "INC0012345"))),
                    LlmStopReason.TOOL_USE, TokenUsage.ZERO);
        }
    }

    /**
     * A stand-in that does not take the hint: it discovers the identity tools, declares a
     * capability, then proposes the privileged call again after every refusal.
     *
     * <p>Deliberately not {@code ScriptedOpsLlm}. That one is the demo's model and it stops
     * on an error result, which is exactly the cooperation this test must not rely on — the
     * platform's claim is that none of the safety properties are enforced by the model.
     *
     * <p>The {@code report_capability} turn is not politeness either: {@code RunRules}
     * refuses anything that changes something until it has run, so without it this stand-in
     * would be stopped one gate earlier and would never reach the park this test is about.
     * Uncooperative about the refusal it is given, compliant about the precondition it
     * cannot get past — which is the same thing a real model does.
     */
    private static final class Stubborn implements LlmClient {

        private final AtomicInteger turns = new AtomicInteger();

        @Override
        public LlmResponse generate(LlmRequest request) {
            int turn = turns.incrementAndGet();
            String tool = switch (turn) {
                case 1 -> "search_tools";
                case 2 -> "report_capability";
                default -> "identity.add_user_to_group";
            };
            Map<String, Object> arguments = switch (turn) {
                case 1 -> Map.of("query", "identity group membership write");
                case 2 -> Map.of("verdict", "SUPPORTED", "reason", "Membership is mine to change.");
                default -> intoProductionAdministrators();
            };
            return LlmResponse.of(
                    Message.of(Role.ASSISTANT, ProposedCall.of("call-" + turn, tool, arguments)),
                    LlmStopReason.TOOL_USE, TokenUsage.ZERO);
        }
    }
}
