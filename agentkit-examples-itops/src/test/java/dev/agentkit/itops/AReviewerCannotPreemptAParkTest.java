package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.ServiceNowConnector;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.runtime.OpsContext;
import dev.agentkit.itops.runtime.Reviewers;
import dev.agentkit.itops.runtime.Supervisor;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.tools.ToolCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code Reviewers.exceptWhereAPersonDecides}: the model reviewer is consulted only where no
 * person will be.
 *
 * <p>The defect this closes was measured live (claude-sonnet-4.5, the privileged eval case).
 * A privileged grant grades {@code HIGH}, and {@code HIGH} parks — a person judges the call
 * from the approval card, which carries the arguments, the risk and the evidence. But the
 * supervisor consults its reviewer <em>before</em> the threshold check, so the reviewing
 * model was asked first, rejected — <em>"such access changes require approval authority that
 * has not been established"</em> — and the rejection is what prevented the person from ever
 * being asked. The reviewer prompt already said approval is not its question; a control that
 * must hold is not a sentence in a prompt, so the guarantee is now wiring.
 */
class AReviewerCannotPreemptAParkTest {

    private static final String ADD = "identity.add_user_to_group";

    /** A reviewer that rejects everything it is asked about, and counts the askings. */
    private static Supervisor.Reviewer rejectingEverything(List<String> consultedOn) {
        return new Supervisor.Reviewer() {
            @Override
            public Optional<String> objection(String goal, String toolName,
                    Map<String, Object> arguments, Risk risk, List<String> evidence) {
                return objection(goal, toolName, arguments, risk, evidence, List.of());
            }

            @Override
            public Optional<String> objection(String goal, String toolName,
                    Map<String, Object> arguments, Risk risk, List<String> evidence,
                    List<String> readings) {
                consultedOn.add(toolName);
                return Optional.of("rejected by the inner reviewer");
            }
        };
    }

    @Test
    void belowTheThresholdTheInnerReviewerDecides() {
        List<String> consultedOn = new ArrayList<>();

        Optional<String> objection = Reviewers
                .exceptWhereAPersonDecides(rejectingEverything(consultedOn), Risk.HIGH)
                .objection("Work the ticket.", "ticketing.assign_ticket",
                        Map.of("ticket_id", "INC1"), Risk.MEDIUM, List.of(),
                        List.of("a reading"));

        assertThat(consultedOn)
                .as("below the threshold nobody else will look, so the model must")
                .containsExactly("ticketing.assign_ticket");
        assertThat(objection).contains("rejected by the inner reviewer");
    }

    @Test
    void atTheThresholdTheInnerReviewerIsNotAsked() {
        List<String> consultedOn = new ArrayList<>();

        Optional<String> objection = Reviewers
                .exceptWhereAPersonDecides(rejectingEverything(consultedOn), Risk.HIGH)
                .objection("Work the ticket.", ADD,
                        Map.of("user", "alice@example.com", "group", "Production-Administrators"),
                        Risk.HIGH, List.of(), List.of());

        assertThat(consultedOn)
                .as("a person will judge this call from the approval card; asking the model"
                        + " first only creates the chance to preempt them")
                .isEmpty();
        assertThat(objection).isEmpty();
    }

    @Test
    void aPrivilegedGrantParksForAPersonEvenWhenTheModelReviewerWouldReject() {
        // The whole path, wired as ItOpsApp wires it: rules always, the model only below
        // the line. The target is established as evidence so the goal-alignment screen
        // passes for the right reason rather than being skipped.
        OpsStore store = new OpsStore();
        Execution execution = store.createExecution("acme", "it-ops-agent",
                Execution.Trigger.CHAT, null,
                "Add alice@example.com to Production-Administrators.");
        OpsContext context = new OpsContext("acme", execution.id(), store);
        context.evidence("Identity provider: account alice@example.com exists.");
        context.evidence("Identity provider: group Production-Administrators exists.");
        List<String> consultedOn = new ArrayList<>();
        Supervisor supervisor = new Supervisor(Risk.HIGH, new IdentityConnector(), context,
                store, execution.goal(),
                Reviewers.allOf(Reviewers.goalAlignment(),
                        Reviewers.exceptWhereAPersonDecides(
                                rejectingEverything(consultedOn), Risk.HIGH)),
                null);
        Tool add = ToolCatalog.forExecution(new ServiceNowConnector(), "agentkit-integration",
                        new DirectoryConnector(), new IdentityConnector(), context, store)
                .find(ADD).orElseThrow();

        GateResult result = supervisor.evaluate(add, new ToolInvocation("c1", ADD,
                Map.of("user", "alice@example.com", "group", "Production-Administrators")));

        assertThat(result.allowed()).isFalse();
        assertThat(result.awaiting())
                .as("the call must wait for a person, not die on a model's verdict about a"
                        + " question the person exists to answer")
                .isPresent();
        assertThat(consultedOn)
                .as("the model reviewer was asked about a call a person was always going to"
                        + " judge")
                .isEmpty();
    }
}
