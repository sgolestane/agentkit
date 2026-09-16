package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.ServiceNowConnector;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.workflow.Workflow;
import dev.agentkit.itops.workflow.WorkflowRunner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What {@code WorkflowRunner} does with each answer a gate can give (#182).
 *
 * <h2>Why these tests could not be written before</h2>
 *
 * <p>{@code WorkflowRunner.run} built its own {@link dev.agentkit.itops.runtime.Supervisor},
 * and that supervisor returns only {@code allow} and {@code deny}. So three of
 * {@link GateResult}'s answers were unreachable from this module, and the completion event's
 * {@code effectiveFor} unwrap — the {@code #104} idiom the line exists to demonstrate — was
 * the identity on every path a test could drive. Measured on {@code main} before the
 * overload, with the completion event's {@code effective.arguments()} replaced by the
 * proposal:
 *
 * <pre>
 * mvn -o -pl agentkit-examples-itops -am test   Tests run: 48, Failures: 0   SURVIVES
 * </pre>
 *
 * <p>The mutant survived by being <em>unobservable</em>, not by being untested. No test
 * written against the three-argument signature could have killed it.
 *
 * <h2>What supplying a gate found</h2>
 *
 * <p>A blind spot and a defect, and the defect is the reason this file is not just a
 * mutation-coverage exercise. This runner turned {@link GateResult#needsAPerson} into a
 * rejection — it refused the call, surfaced the reason, and never asked anyone. Measured
 * with a parking gate, before the fix:
 *
 * <pre>
 * events = [.., ACTION_REJECTED, EXECUTION_FAILED]      status = FAILED
 * </pre>
 *
 * <p>A run waiting for a person was filed as a run that failed, so the one thing a reviewer
 * had to act on did not appear in the queue of things to act on.
 *
 * <h2>What #157 then removed</h2>
 *
 * <p>#182 fixed the supplied-gate path and left the default one alone, because this module's
 * own {@code Supervisor} parked by a private convention instead. So this runner read two
 * signals — a verdict's {@code awaiting()}, and, through an {@code instanceof Supervisor}
 * check, a flag that gate set on itself — and the default path was still recorded as an
 * {@code ACTION_REJECTED}. The supervisor now returns {@code needsAPerson}, the check is
 * gone, and {@code theDefaultPathParksThroughTheSameBranchAsAnySupplierGate} below is what
 * that test became.
 */
class WorkflowGateTest {

    private static final String TENANT = "acme";

    /** One tool call and an end, so the audit rows this is about are unambiguous. */
    private static Workflow oneStep(String tool, Map<String, Object> arguments) {
        return new Workflow("gate-probe", 1, "Gate probe", "One gated step.",
                List.of(
                        Workflow.Node.start(),
                        Workflow.Node.tool("step", "The gated step", tool, arguments),
                        Workflow.Node.end("done", "Done")),
                List.of(
                        Workflow.Edge.of("start", "step"),
                        Workflow.Edge.of("step", "done")),
                true);
    }

    private static Workflow readsProductionAdministrators() {
        return oneStep("identity.get_group_members", Map.of("group", "Production-Administrators"));
    }

    private static WorkflowRunner runner(OpsStore store) {
        return new WorkflowRunner(store, new ServiceNowConnector(), new DirectoryConnector(),
                new IdentityConnector(), "agentkit-integration", Risk.HIGH);
    }

    private static List<Execution.Event.Type> typesOf(List<Execution.Event> events) {
        return events.stream().map(Execution.Event::type).toList();
    }

    private static Map<String, Object> only(List<Execution.Event> events,
                                            Execution.Event.Type type) {
        List<Execution.Event> matching = events.stream().filter(e -> e.type() == type).toList();
        assertThat(matching).as("expected exactly one %s event", type).hasSize(1);
        return matching.get(0).detail();
    }

    /** The documented, supported feature: a gate that narrows the arguments. */
    private static ToolGate narrowingTo(String group) {
        // id and name preserved: effectiveFor refuses a replacement that renames or
        // renumbers, which is the substitution defect it exists to catch.
        return (tool, invocation) -> GateResult.allowWith(
                new ToolInvocation(invocation.id(), invocation.name(), Map.of("group", group)));
    }

    @Test
    void theCompletionEventRecordsTheGatesNarrowingAndNotTheProposal() {
        // The whole of #182's blind spot in one assertion, and the mutant that survived
        // 48 tests dies here: the two rows disagree, and each says the true thing about
        // the moment it was written.
        OpsStore store = new OpsStore();

        WorkflowRunner.Result result = runner(store).run(TENANT,
                readsProductionAdministrators(), Map.of(), narrowingTo("Employees-All"));

        assertThat(result.execution().status())
                .as("the workflow did not complete, so this test measured nothing")
                .isEqualTo(Execution.Status.COMPLETED);
        List<Execution.Event> events = store.events(result.execution().id());
        assertThat(only(events, Execution.Event.Type.TOOL_STARTED))
                .as("the start row is written before anything has gated the call, so it"
                        + " reports the proposal")
                .containsEntry("proposedArguments",
                        Map.of("group", "Production-Administrators"));
        assertThat(only(events, Execution.Event.Type.TOOL_COMPLETED))
                .as("the completion row named the group the gate overruled, not the one it"
                        + " narrowed to -- the audit trail reporting the proposal as the"
                        + " execution")
                .containsEntry("arguments", Map.of("group", "Employees-All"));
    }

    @Test
    void aNarrowedCallIsWhatTheToolActuallyReceives() {
        // The row above is only worth anything if it describes what happened, so this
        // pins the effect rather than the record: Employees-All and
        // Production-Administrators have different members, and the result carries the
        // membership that was actually read.
        OpsStore store = new OpsStore();

        WorkflowRunner.Result result = runner(store).run(TENANT,
                readsProductionAdministrators(), Map.of(), narrowingTo("Employees-All"));

        assertThat(String.valueOf(result.outputs().get("step")))
                .as("the tool read the group the gate overruled")
                .doesNotContain("Production-Administrators");
    }

    @Test
    void aGateThatAsksForAPersonParksTheRunRatherThanFailingIt() {
        // #182's defect rather than its blind spot. GateResult.needsAPerson's own javadoc
        // predicts this of "a runner that predates this": it refuses the call and surfaces
        // the reason, both true, and does not do the second half, which is asking.
        OpsStore store = new OpsStore();

        WorkflowRunner.Result result = runner(store).run(TENANT,
                readsProductionAdministrators(), Map.of(),
                (tool, invocation) -> GateResult.needsAPerson(
                        ApprovalNeeded.because("a person must decide")));

        assertThat(result.parked()).isTrue();
        assertThat(result.execution().status())
                .as("a run waiting for a person was filed as a run that failed")
                .isEqualTo(Execution.Status.WAITING_FOR_APPROVAL);
        List<Execution.Event.Type> types = typesOf(store.events(result.execution().id()));
        assertThat(types)
                .contains(Execution.Event.Type.HUMAN_APPROVAL_REQUESTED,
                        Execution.Event.Type.EXECUTION_PARKED)
                .as("asking a person was recorded as refusing them")
                .doesNotContain(Execution.Event.Type.ACTION_REJECTED,
                        Execution.Event.Type.EXECUTION_FAILED);
    }

    @Test
    void aPlainDenialIsStillRecordedAsARejection() {
        // The other side of the branch, and the one that must not move. "A person must
        // decide" and "no" are different answers, and a change that made every refusal
        // look like a parked run would be the same defect pointing the other way.
        OpsStore store = new OpsStore();

        WorkflowRunner.Result result = runner(store).run(TENANT,
                readsProductionAdministrators(), Map.of(),
                (tool, invocation) -> GateResult.deny("policy says no"));

        assertThat(result.parked()).isFalse();
        assertThat(result.execution().status()).isEqualTo(Execution.Status.FAILED);
        List<Execution.Event.Type> types = typesOf(store.events(result.execution().id()));
        assertThat(types)
                .contains(Execution.Event.Type.ACTION_REJECTED,
                        Execution.Event.Type.EXECUTION_FAILED)
                .doesNotContain(Execution.Event.Type.HUMAN_APPROVAL_REQUESTED,
                        Execution.Event.Type.EXECUTION_PARKED);
    }

    @Test
    void theDefaultPathParksThroughTheSameBranchAsAnySupplierGate() {
        // What replaced the regression guard on parkedOutOfBand (#157). This module's
        // Supervisor used to park by a private convention -- create the approval request,
        // record HUMAN_APPROVAL_REQUESTED itself, set a flag, return a plain denial -- so
        // this runner read the flag as well as the verdict, through an
        // `instanceof Supervisor` check, and filed a real approval as an ACTION_REJECTED on
        // the way past. It now returns needsAPerson like the supplied gate two tests up,
        // and the flag and the check are both gone.
        OpsStore store = new OpsStore();

        WorkflowRunner.Result result = runner(store).run(TENANT,
                oneStep("identity.add_user_to_group",
                        Map.of("user", "alice@example.com",
                                "group", "Production-Administrators")),
                Map.of());

        assertThat(result.parked())
                .as("the supervisor parked and the runner did not notice")
                .isTrue();
        assertThat(result.execution().status())
                .isEqualTo(Execution.Status.WAITING_FOR_APPROVAL);
        assertThat(typesOf(store.events(result.execution().id())))
                .contains(Execution.Event.Type.HUMAN_APPROVAL_REQUESTED,
                        Execution.Event.Type.EXECUTION_PARKED)
                .as("asking a person was recorded as refusing them, on the one path in this"
                        + " module where a real approval is queued")
                .doesNotContain(Execution.Event.Type.ACTION_REJECTED);

        // And exactly one of them. The supervisor writes a rich row -- approvalId, risk,
        // effect, arguments, against an ApprovalRequest a reviewer opens -- and the runner's
        // own thin row would be a second entry in the queue for one decision, the one with
        // nothing to act on.
        Map<String, Object> requested =
                only(store.events(result.execution().id()),
                        Execution.Event.Type.HUMAN_APPROVAL_REQUESTED);
        assertThat(requested)
                .as("the runner appended its thin row beside the supervisor's rich one")
                .containsKey("approvalId")
                .containsEntry("risk", "HIGH")
                .containsEntry("tool", "identity.add_user_to_group");
        assertThat(store.pendingApprovalFor(result.execution().id()))
                .as("a workflow park must still queue the row a reviewer decides from")
                .isPresent();
    }

    @Test
    void anUnknownToolIsStillAStepFailureAndNotAPark() {
        // invoke's early return grew a second field in this change, and "false" is the
        // easiest thing to get wrong in a record constructor nobody reads twice.
        OpsStore store = new OpsStore();

        WorkflowRunner.Result result = runner(store).run(TENANT,
                oneStep("identity.no_such_tool", Map.of()), Map.of());

        assertThat(result.parked()).isFalse();
        assertThat(result.execution().status()).isEqualTo(Execution.Status.FAILED);
    }
}
