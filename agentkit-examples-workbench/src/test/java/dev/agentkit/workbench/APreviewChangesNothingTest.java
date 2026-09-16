package dev.agentkit.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.Workbench;
import dev.agentkit.workbench.store.WorkbenchStore;
import org.junit.jupiter.api.Test;

/**
 * "What would the agent do?" is a rehearsal: the model can try to write, and the world does not
 * change — the attempt is refused with a reason that tells it to describe the step in the
 * plan, and the run still completes with that plan.
 */
class APreviewChangesNothingTest {

    @Test
    void aWriteProposedInPreviewIsRefusedAndNothingReachesTheAlm() {
        FakeAlm alm = new FakeAlm(FakeAlm.ticket("IT-1", "Reset a password",
                "Please reset the password for pat@example.com"));
        WorkbenchStore store = new WorkbenchStore();
        ScriptedLlm llm = new ScriptedLlm()
                .proposes("jira.add_comment",
                        ScriptedLlm.args("ticket_key", "IT-1", "body", "On it."))
                .says("Plan: I would assign the ticket, reset the password, comment, and "
                        + "close. The comment and transition would wait for your approval.");
        Workbench workbench = new Workbench(store, llm, "scripted", alm,
                new Learnings(MemoryStore.inMemory(), "default"), "default");

        Workbench.Outcome outcome = workbench.preview("IT-1");

        assertThat(alm.commentsWritten).isEmpty();
        assertThat(alm.transitionsRun).isEmpty();
        assertThat(outcome.parked()).isFalse();
        assertThat(outcome.run().status()).isEqualTo(Run.Status.COMPLETED);
        assertThat(outcome.output()).contains("Plan:");
        // The refusal is on the audit trail, where an operator reviewing the rehearsal
        // sees what would have needed approval.
        assertThat(store.events(outcome.run().id()))
                .anyMatch(event -> event.type() == Run.Event.Type.ACTION_REJECTED);
        // And no approval row was raised: a preview asks nobody anything.
        assertThat(store.approvals("default")).isEmpty();
    }
}
