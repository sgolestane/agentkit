package dev.agentkit.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.core.reflect.CorrectionBook;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.Workbench;
import dev.agentkit.workbench.store.WorkbenchStore;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A person's refusal teaches the next run (#331): the reason travels as advice into later
 * goals; a refusal the person meant to keep binds as a gate until an operator lifts it —
 * and lifting restores exactly the parking behaviour the refusal replaced.
 */
class ARejectionTeachesTheNextRunTest {

    private static final Map<String, Object> COMMENT =
            ScriptedLlm.args("ticket_key", "IT-2", "body", "Access granted.");

    private record Rig(WorkbenchStore store, FakeAlm alm, CorrectionBook corrections,
                       Workbench workbench) {}

    private Rig rig(ScriptedLlm llm) {
        FakeAlm alm = new FakeAlm(FakeAlm.ticket("IT-2", "Grant access",
                "Please add dana@example.com to the reporting group."));
        WorkbenchStore store = new WorkbenchStore();
        CorrectionBook corrections = new CorrectionBook(MemoryStore.inMemory());
        return new Rig(store, alm, corrections, new Workbench(store, llm, "scripted", alm,
                new Learnings(MemoryStore.inMemory(), "default"), "default", corrections));
    }

    @Test
    void anAdvisoryRejectionIsRecalledIntoTheNextRunsGoal() {
        Rig rig = rig(new ScriptedLlm()
                .proposes("jira.add_comment", COMMENT)   // run 1: parks
                .proposes("jira.add_comment", COMMENT)); // run 2: still parks — advice, not law
        Workbench.Outcome parked = rig.workbench().execute("IT-2", Run.Trigger.OPERATOR);
        rig.workbench().resume(parked.pending().orElseThrow().id(), "sid@example.com", false,
                "Dana's access request needs her manager's sign-off first.", false);

        // The reason is durably in the book, keyed by the capability, marked advisory.
        assertThat(rig.corrections().recall("default", java.util.List.of("ticketing.write")))
                .singleElement()
                .satisfies(correction -> {
                    assertThat(correction.note()).contains("manager's sign-off");
                    assertThat(correction.standing()).isFalse();
                });

        Workbench.Outcome next = rig.workbench().execute("IT-2", Run.Trigger.OPERATOR);

        // The next run was told — on the audit trail — and advice does not block: the
        // proposal still parks for a person rather than being refused outright.
        assertThat(rig.store().events(next.run().id()))
                .anyMatch(event -> event.type() == Run.Event.Type.CORRECTIONS_RECALLED);
        assertThat(next.parked()).isTrue();
        assertThat(rig.alm().commentsWritten).isEmpty();
    }

    @Test
    void aStandingRefusalBindsUntilAnOperatorLiftsIt() {
        Rig rig = rig(new ScriptedLlm()
                .proposes("jira.add_comment", COMMENT)   // run 1: parks
                .proposes("jira.add_comment", COMMENT)   // run 2: refused by the gate
                .says("Refused by standing policy; leaving the ticket for a person.")
                .proposes("jira.add_comment", COMMENT)); // run 3, after lift: parks again
        Workbench.Outcome parked = rig.workbench().execute("IT-2", Run.Trigger.OPERATOR);
        rig.workbench().resume(parked.pending().orElseThrow().id(), "sid@example.com", false,
                "Never comment access grants without the manager's sign-off.", true);

        Workbench.Outcome refused = rig.workbench().execute("IT-2", Run.Trigger.OPERATOR);

        // The gate answered, not the supervisor: no second approval row was raised for a
        // call that can never run, nothing reached the ALM, and the run finished on the
        // model's own report rather than parking.
        assertThat(refused.parked()).isFalse();
        assertThat(rig.store().approvals("default").stream()
                .filter(a -> a.runId().equals(refused.run().id())))
                .isEmpty();
        assertThat(rig.alm().commentsWritten).isEmpty();

        // Lifting is the way back: the refusal remains as advice, and supervision — not
        // silence — is what returns.
        assertThat(rig.workbench().liftStandingRefusal("ticketing.write")).contains(1);
        assertThat(rig.workbench().standingRefusals()).isEmpty();
        Workbench.Outcome again = rig.workbench().execute("IT-2", Run.Trigger.OPERATOR);
        assertThat(again.parked()).isTrue();
    }
}
