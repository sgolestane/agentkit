package dev.agentkit.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.core.reflect.CorrectionBook;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.runtime.AnswerBox;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.RunContext;
import dev.agentkit.workbench.runtime.StandingApprovals;
import dev.agentkit.workbench.runtime.Supervisor;
import dev.agentkit.workbench.runtime.Workbench;
import dev.agentkit.workbench.store.WorkbenchStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * "Stop asking me about this": a person clears a capability from the card where they are
 * approving it, and supervised runs stop parking on it — with the two limits that make it
 * safe to offer at all.
 */
class StopAskingMeAboutThisTest {

    private static final Map<String, Object> ASSIGN = ScriptedLlm.args("ticket_key", "IT-3");

    private record Rig(WorkbenchStore store, FakeAlm alm, MemoryStore memory,
                       StandingApprovals trusted, Workbench workbench) {}

    private Rig rig(ScriptedLlm llm) {
        FakeAlm alm = new FakeAlm(FakeAlm.ticket("IT-3", "Access request", "Please add dana."));
        WorkbenchStore store = new WorkbenchStore();
        MemoryStore memory = MemoryStore.inMemory();
        StandingApprovals trusted = new StandingApprovals(memory);
        Workbench workbench = new Workbench(store, llm, "scripted", alm,
                new Learnings(MemoryStore.inMemory(), "default"), "default",
                new CorrectionBook(memory), trusted);
        return new Rig(store, alm, memory, trusted, workbench);
    }

    @Test
    void approvingWithStopAskingClearsTheCapabilityForLaterRuns() {
        Rig rig = rig(new ScriptedLlm()
                .proposes("jira.assign_to_me", ASSIGN)   // run 1: parks
                .proposes("jira.assign_to_me", ASSIGN)   // resumed run: the approved call
                .says("Assigned.")
                .proposes("jira.assign_to_me", ASSIGN)   // a later ticket: no park at all
                .says("Assigned without asking."));

        Workbench.Outcome parked = rig.workbench().execute("IT-3", Run.Trigger.OPERATOR);
        assertThat(parked.parked()).isTrue();

        // Approve, and say to stop asking about this kind of action.
        rig.workbench().resume(parked.pending().orElseThrow().id(), "sid@example.com", true,
                "Fine — taking ownership is not a decision I need to make.", true);

        assertThat(rig.trusted().covers("default", "ticketing.write")).isTrue();
        assertThat(rig.workbench().trustedCapabilities())
                .singleElement()
                .satisfies(granted -> {
                    assertThat(granted.capability()).isEqualTo("ticketing.write");
                    assertThat(granted.by()).isEqualTo("sid@example.com");
                });

        // The next supervised run over the same capability does not stop.
        Workbench.Outcome later = rig.workbench().execute("IT-3", Run.Trigger.OPERATOR);
        assertThat(later.parked()).isFalse();
        assertThat(later.run().status()).isEqualTo(Run.Status.COMPLETED);
        assertThat(rig.alm().assignments).contains("IT-3");

        // And the way back restores exactly the asking it replaced.
        assertThat(rig.workbench().untrust("ticketing.write")).isTrue();
        assertThat(rig.workbench().trustedCapabilities()).isEmpty();
    }

    @Test
    void aClearedCapabilityStillStopsForAConsequentialCall() {
        WorkbenchStore store = new WorkbenchStore();
        RunContext context = new RunContext("default", "run-x", store);
        StandingApprovals trusted = new StandingApprovals(MemoryStore.inMemory());
        // Cleared under the fallback capability, which is where an undeclared tool lands.
        trusted.grant("default", "unknown", "sid@example.com");
        Supervisor supervisor = new Supervisor(Run.Mode.SUPERVISED, context, store, "IT-3",
                AnswerBox.EMPTY, List.of(), trusted, "default");
        var undeclared = FunctionTool.builder("identity.delete_user", "no policy for this")
                .handler(invocation -> ToolResult.ok("never reached"))
                .build();

        GateResult result = supervisor.evaluate(undeclared,
                new ToolInvocation("call-1", "identity.delete_user", Map.of()));

        // The catalog grades an undeclared tool HIGH, and trust does not reach that far:
        // the clearance was given to the ordinary case, and this call is not one.
        assertThat(result.allowed()).isFalse();
        assertThat(result.awaiting()).isPresent();
    }

    @Test
    void aStandingRefusalOutranksTrust() {
        Rig rig = rig(new ScriptedLlm()
                .proposes("jira.assign_to_me", ASSIGN)
                .says("Refused; leaving it for a person."));
        // The same capability both cleared and refused: no with a reason beats yes by habit.
        rig.workbench().trust("ticketing.write", "sid@example.com");
        new CorrectionBook(rig.memory()).record("default", "ticketing.write",
                "sid@example.com", "Never take ownership without the requester asking.", true);

        Workbench.Outcome outcome = rig.workbench().execute("IT-3", Run.Trigger.OPERATOR);

        assertThat(outcome.parked()).isFalse();
        assertThat(rig.alm().assignments).isEmpty();
        assertThat(rig.store().approvals("default")).isEmpty();
    }
}
