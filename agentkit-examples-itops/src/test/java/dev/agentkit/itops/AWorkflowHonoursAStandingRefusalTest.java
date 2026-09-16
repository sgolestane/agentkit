package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.memory.InMemoryMemoryStore;
import dev.agentkit.core.reflect.CorrectionBook;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.ServiceNowConnector;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.workflow.Workflow;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.workflow.WorkflowRunner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The second execution path honours a standing refusal too (#329).
 *
 * <p>{@code WorkflowRunner} has no model in its loop, so the other rules in
 * {@code RunRules} — do not route around a refusal, declare before you write — reasonably do
 * not apply to it. A standing refusal is not about the model: it is a person saying "not in
 * this capability until somebody lifts it".
 *
 * <p>Measured before the wiring: a one-node workflow naming
 * {@code identity.remove_user_from_group} ran to {@code COMPLETED}, unparked and undenied,
 * and the member was removed, while a standing refusal on exactly that capability was in
 * force. The shipped {@code employee-offboarding} workflow contains that node. A control
 * whose stated scope is a capability, and which a second path in the same process ignores,
 * is worse than none — it reads as covered.
 */
class AWorkflowHonoursAStandingRefusalTest {

    private static final String TENANT = "acme";
    private static final String CAPABILITY = "identity.group_membership.write";

    private static Workflow removingAMember() {
        return new Workflow("remove-one", 1, "Remove one member", "A probe.",
                List.of(Workflow.Node.start(),
                        Workflow.Node.tool("remove", "Remove", "identity.remove_user_from_group",
                                Map.of("user", "bob@example.com", "group", "Employees-All")),
                        Workflow.Node.end("done", "Done")),
                List.of(Workflow.Edge.of("start", "remove"),
                        Workflow.Edge.of("remove", "done")), true);
    }

    private static WorkflowRunner runner(OpsStore store, IdentityConnector identity,
            CorrectionBook corrections) {
        return new WorkflowRunner(store, new ServiceNowConnector(), new DirectoryConnector(),
                identity, "agentkit-integration", Risk.HIGH, null, corrections);
    }

    @Test
    @DisplayName("a workflow does not perform what a person refused and meant it about")
    void aWorkflowHonoursAStandingRefusal() {
        CorrectionBook corrections = new CorrectionBook(new InMemoryMemoryStore());
        corrections.record(TENANT, CAPABILITY, "sam@example.com",
                "never remove group members from a workflow", true);

        IdentityConnector identity = new IdentityConnector();
        runner(new OpsStore(), identity, corrections)
                .run(TENANT, removingAMember(), Map.of());

        assertThat(identity.groupMembers("Employees-All"))
                .as("a standing refusal was in force and the workflow removed the member anyway")
                .contains("bob@example.com");
    }

    /**
     * The negative that makes the one above mean something.
     *
     * <p>Without the book the same workflow performs the same removal, so the assertion
     * above is measuring the gate and not some other reason the step did not happen.
     */
    @Test
    @DisplayName("with no standing refusal the same workflow performs the removal")
    void withoutARefusalTheWorkflowStillActs() {
        IdentityConnector identity = new IdentityConnector();
        runner(new OpsStore(), identity, null).run(TENANT, removingAMember(), Map.of());

        assertThat(identity.groupMembers("Employees-All"))
                .doesNotContain("bob@example.com");
    }

    /** And an advisory refusal is not a gate: the workflow still acts. */
    @Test
    @DisplayName("an advisory refusal does not stop a workflow")
    void anAdvisoryRefusalDoesNotStopAWorkflow() {
        CorrectionBook corrections = new CorrectionBook(new InMemoryMemoryStore());
        corrections.record(TENANT, CAPABILITY, "sam@example.com", "wrong person that time");

        IdentityConnector identity = new IdentityConnector();
        runner(new OpsStore(), identity, corrections)
                .run(TENANT, removingAMember(), Map.of());

        assertThat(identity.groupMembers("Employees-All"))
                .as("a routine rejection silently became policy for workflows")
                .doesNotContain("bob@example.com");
    }
}
