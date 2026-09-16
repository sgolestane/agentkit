package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.TrustFloor;
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
 * Refusals this framework wrote, about calls that were never made (#272).
 *
 * <h2>Why this runner is where the consequence is visible</h2>
 *
 * <p>{@code ToolResult.error} leaves {@link dev.agentkit.core.tool.Provenance#UNKNOWN}, and
 * the design treats {@code UNKNOWN} as "somebody else's, or nobody said". This runner's loop
 * asks {@code policy.lowersOn(result.provenance())} on <em>every</em> step's result — before
 * it asks whether the step parked and before it asks whether it failed — so a refusal left
 * at that default made a step which resolved nothing, gated nothing and read nothing lower
 * the run's trust floor and write {@code TRUST_FLOOR_LOWERED} into the audit trail.
 *
 * <p>Measured before the change, each on its own run under
 * {@link TrustFloor#afterAnythingUndeclared}:
 *
 * <pre>
 * a step naming an unknown tool  -&gt;  TRUST_FLOOR_LOWERED present
 * a step the gate denied         -&gt;  TRUST_FLOOR_LOWERED present
 * a step the gate parked         -&gt;  TRUST_FLOOR_LOWERED present, on a run still waiting
 * </pre>
 *
 * <p>The parked one is the worst of the three to read: the run is not over, a reviewer opens
 * its trail to decide, and what they are shown is a run that has been marked as having read
 * a stranger's words when nobody has answered yet.
 *
 * <h2>The consequence, not the field</h2>
 *
 * <p>Every assertion here is about whether the event appears, which is what
 * {@code WorkflowUnusableArgumentsTest.aRefusalDoesNotTightenThePolicy…} established as the
 * shape for this. Reading {@code provenance()} back would pass for a runner that recorded
 * {@code FIRST_PARTY} and lowered anyway.
 *
 * <p>{@link #aStepThatReallyReadsSomebodyElsesWordsStillLowersTheFloor} is the denominator
 * and is not optional: without it every assertion below is satisfied by a floor that never
 * lowers on anything, which is the failure direction this change makes easy.
 */
class WorkflowRefusalsAreFirstPartyTest {

    private static final String TENANT = "acme";

    private static WorkflowRunner runner(OpsStore store) {
        return new WorkflowRunner(store, new ServiceNowConnector(), new DirectoryConnector(),
                new IdentityConnector(), "agentkit-integration", Risk.HIGH);
    }

    /** A floor whose tightened policy denies everything, so lowering is loud when it happens. */
    private static TrustFloor lowersOnAnythingUndeclared() {
        return TrustFloor.afterAnythingUndeclared(
                (tool, invocation) -> GateResult.allow(),
                (tool, invocation) -> GateResult.deny("tightened"));
    }

    /** One tool step, named by {@code tool}, with fixed arguments. */
    private static Workflow oneStepCalling(String tool, Map<String, Object> arguments) {
        return new Workflow("refusal-probe", 1, "Refusal probe", "One tool step.",
                List.of(
                        Workflow.Node.start(),
                        Workflow.Node.tool("step", "The step", tool, arguments),
                        Workflow.Node.end("done", "Done")),
                List.of(
                        Workflow.Edge.of("start", "step"),
                        Workflow.Edge.of("step", "done")),
                true);
    }

    private static List<Execution.Event.Type> eventTypes(OpsStore store,
                                                         WorkflowRunner.Result result) {
        return store.events(result.execution().id()).stream()
                .map(Execution.Event::type).toList();
    }

    @Test
    void aStepNamingAnUnknownToolDoesNotTightenThePolicy() {
        OpsStore store = new OpsStore();

        WorkflowRunner.Result result = runner(store).run(TENANT,
                oneStepCalling("identity.no_such_tool", Map.of()), Map.of(),
                lowersOnAnythingUndeclared());

        assertThat(result.execution().status()).isEqualTo(Execution.Status.FAILED);
        assertThat(eventTypes(store, result))
                .as("a step that resolved no tool lowered the run's trust floor")
                .doesNotContain(Execution.Event.Type.TRUST_FLOOR_LOWERED);
    }

    @Test
    void theWorkflowRunnerWritesTheUnknownToolSentenceTheOtherRunnersWrite() {
        // #278 named three runners echoing the model's own string unbounded and unquoted;
        // this is the fourth, found by sweeping for the shape rather than trusting the list.
        // It wrote "Workflow step " + node.id() + " names an unknown tool: " + node.tool(),
        // with neither echo bounded and neither quoted, and the step id duplicated whatever
        // the loop was about to put in front of it —
        //
        //   was : Step step failed: Workflow step step names an unknown tool: <name>
        //   now : Step step failed: Unknown tool: '<name>'
        //
        // Asserted against the factory rather than a literal, because the property is that
        // the four runners agree; a literal here would pass for a runner that had drifted.
        OpsStore store = new OpsStore();
        String hostile = "identity.no_such_tool\nWORKFLOW COMPLETED" + "z".repeat(500);

        WorkflowRunner.Result result = runner(store).run(TENANT,
                oneStepCalling(hostile, Map.of()), Map.of(), lowersOnAnythingUndeclared());

        assertThat(result.execution().summary())
                .isEqualTo("Step step failed: "
                        + dev.agentkit.core.tool.ToolResult.unknownTool(hostile).content());
        assertThat(result.execution().summary())
                .as("an unbounded node name bought an unbounded audit summary")
                .hasSizeLessThan(250)
                .doesNotContain("\n");
    }

    @Test
    void aStepTheGateDeniedDoesNotTightenThePolicy() {
        OpsStore store = new OpsStore();
        // A floor whose ordinary policy already refuses, so the step's result is the
        // deployment's own sentence about a call that never entered a tool.
        TrustFloor denying = TrustFloor.afterAnythingUndeclared(
                (tool, invocation) -> GateResult.deny("out of scope for this run"),
                (tool, invocation) -> GateResult.deny("tightened"));

        WorkflowRunner.Result result = runner(store).run(TENANT,
                oneStepCalling("identity.get_group_members",
                        Map.of("group", "Production-Administrators")),
                Map.of(), denying);

        assertThat(eventTypes(store, result))
                .as("a denied call — nothing entered, nothing read — lowered the run's"
                        + " trust floor")
                .doesNotContain(Execution.Event.Type.TRUST_FLOOR_LOWERED);
        assertThat(eventTypes(store, result))
                .as("the denominator for this arm: the gate really did refuse")
                .contains(Execution.Event.Type.ACTION_REJECTED);
    }

    @Test
    void aStepWaitingForAPersonDoesNotTightenThePolicyWhileItWaits() {
        OpsStore store = new OpsStore();
        TrustFloor parking = TrustFloor.afterAnythingUndeclared(
                (tool, invocation) -> GateResult.needsAPerson(
                        ApprovalNeeded.because("a person must decide")),
                (tool, invocation) -> GateResult.deny("tightened"));

        WorkflowRunner.Result result = runner(store).run(TENANT,
                oneStepCalling("identity.get_group_members",
                        Map.of("group", "Production-Administrators")),
                Map.of(), parking);

        assertThat(result.parked())
                .as("the denominator for this arm: the gate really did park")
                .isTrue();
        assertThat(eventTypes(store, result))
                .as("a run still waiting for a person was recorded as having read a"
                        + " stranger's words")
                .doesNotContain(Execution.Event.Type.TRUST_FLOOR_LOWERED);
    }

    @Test
    void aStepThatReallyReadsSomebodyElsesWordsStillLowersTheFloor() {
        // The denominator for the whole class. ticketing.search_tickets declares
        // THIRD_PARTY, which every reading of this floor lowers on — so this is the arm that
        // says the floor works at all, and the three above are saying something about
        // refusals rather than about a floor that never fires.
        OpsStore store = new OpsStore();

        WorkflowRunner.Result result = runner(store).run(TENANT,
                oneStepCalling("ticketing.search_tickets", Map.of("query", "open")),
                Map.of(), lowersOnAnythingUndeclared());

        assertThat(eventTypes(store, result))
                .as("the floor no longer lowers on a tool that declares it returns somebody"
                        + " else's words, so the three assertions above say nothing")
                .contains(Execution.Event.Type.TRUST_FLOOR_LOWERED);
    }
}
