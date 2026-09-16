package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.reliability.ActionScreen;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A gate built for one run does not go on a worker that serves every run (#63).
 *
 * <p>The first test here is the measurement the refusal exists for, taken through the plain
 * lambda that {@code boundToOneRun()} cannot see — so it still measures the hazard rather
 * than the check, and it fails if the check is ever made to cover it by accident.
 */
class DurableObjectiveScreeningTest {

    private static final AtomicInteger RUNS = new AtomicInteger();

    private static final ActionScreen NAMES_A_KNOWN_TARGET = (objective, tool, invocation) -> {
        Object user = invocation.arguments().get("user");
        return user != null && !objective.toLowerCase(Locale.ROOT)
                .contains(user.toString().toLowerCase(Locale.ROOT))
                ? Optional.of("That target appears nowhere in what this run was asked to do.")
                : Optional.empty();
    };

    private static ToolRegistry tools() {
        return new SimpleToolRegistry().register(
                FunctionTool.builder("add_user_to_group", "Adds a user to a group.")
                        .schema(Map.of("type", "object", "properties", Map.of(),
                                "required", List.of()))
                        .sideEffects(SideEffects.EXTERNAL)
                        .handler(invocation -> {
                            RUNS.incrementAndGet();
                            return ToolResult.ok("added");
                        })
                        .build());
    }

    private static ToolInvocation call(String id, String user) {
        return new ToolInvocation(id, "add_user_to_group",
                Map.of("user", user, "group", "Employees-All"));
    }

    @Test
    void oneRunsObjectiveOnAWorkerJudgesEveryOtherRunsCalls() {
        // A hand-rolled screening gate: a lambda closing over one run's objective. It reports
        // boundToOneRun() == false because nothing asked it, which is exactly the shape the
        // constructor check below cannot catch — so this is the hazard, not the control.
        String objectiveOfRunA = "Onboard alice@example.com.";
        ToolGate gate = (tool, invocation) -> NAMES_A_KNOWN_TARGET
                .objection(objectiveOfRunA, tool, invocation)
                .map(GateResult::deny)
                .orElseGet(GateResult::allow);

        ToolActivitiesImpl worker = new ToolActivitiesImpl(tools(), gate);

        ToolOutcome runA = worker.executeTool(call("a1", "alice@example.com"));
        ToolOutcome runB = worker.executeTool(call("b1", "bob@example.com"));

        assertThat(runA.result().isError())
                .as("run A, judged against its own objective")
                .isFalse();
        assertThat(runB.result().isError())
                .as("run B, judged against run A's objective, on the same worker")
                .isTrue();
        assertThat(runB.result().content()).contains("appears nowhere");
        assertThat(runB.result().provenance()).isEqualTo(Provenance.FIRST_PARTY);
    }

    @Test
    void aScreeningGateIsRefusedWhereItIsWired() {
        assertThatThrownBy(() -> new ToolActivitiesImpl(tools(),
                        ToolGates.screeningAgainst("Onboard alice@example.com.",
                                NAMES_A_KNOWN_TARGET)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("built for one run")
                .hasMessageContaining("stranger's objective");
    }

    @Test
    void composingItWithAPolicyDoesNotLaunderItOntoTheWorker() {
        assertThatThrownBy(() -> new ToolActivitiesImpl(tools(),
                        ToolGates.allOf(ToolGates.readOnly(),
                                ToolGates.screeningAgainst("Onboard alice@example.com.",
                                        NAMES_A_KNOWN_TARGET))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("built for one run");
    }

    @Test
    void aFloorWhoseTightenedPolicyIsPerRunIsRefusedToo() {
        // The one that would otherwise engage only after something had read somebody else's
        // words -- so a worker would accept it, serve a hundred runs, and start screening
        // strangers' calls the first time a tool returned a web page.
        assertThatThrownBy(() -> new ToolActivitiesImpl(tools(),
                        TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL,
                                ToolGates.screeningAgainst("Onboard alice@example.com.",
                                        NAMES_A_KNOWN_TARGET))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("built for one run");
    }

    @Test
    void anOrdinaryPolicyIsStillAccepted() {
        // The refusal must not cost the durable path the gates it is meant to run: a policy
        // that decides from declared side effects is true of every run and stays wired.
        assertThat(new ToolActivitiesImpl(tools(), ToolGates.readOnly())
                .executeTool(call("c1", "carol@example.com")).result().isError())
                .isTrue();
    }
}
