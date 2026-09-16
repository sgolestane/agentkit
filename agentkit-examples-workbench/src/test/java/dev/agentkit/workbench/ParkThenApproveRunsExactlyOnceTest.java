package dev.agentkit.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.workbench.domain.Approval;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.Workbench;
import dev.agentkit.workbench.store.WorkbenchStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Supervised execution: a write parks the run with the call carried whole; approving starts
 * a fresh run in which exactly that call is allowed exactly once; rejecting cancels.
 */
class ParkThenApproveRunsExactlyOnceTest {

    private static final java.util.Map<String, Object> COMMENT =
            ScriptedLlm.args("ticket_key", "IT-2", "body", "Access granted per the request.");

    @Test
    void theWriteParksAndAnApprovalResumesItOnce() {
        FakeAlm alm = new FakeAlm(FakeAlm.ticket("IT-2", "Grant access",
                "Please add dana@example.com to the reporting group."));
        WorkbenchStore store = new WorkbenchStore();
        ScriptedLlm llm = new ScriptedLlm()
                // first run: proposes the comment, which parks
                .proposes("jira.add_comment", COMMENT)
                // resumed run: proposes it again (allowed once), then again (refused), then closes
                .proposes("jira.add_comment", COMMENT)
                .proposes("jira.add_comment", COMMENT)
                .says("Commented and finished.");
        Workbench workbench = new Workbench(store, llm, "scripted", alm,
                new Learnings(MemoryStore.inMemory(), "default"), "default");

        Workbench.Outcome parked = workbench.execute("IT-2", Run.Trigger.OPERATOR);

        assertThat(parked.parked()).isTrue();
        assertThat(parked.run().status()).isEqualTo(Run.Status.WAITING_FOR_HUMAN);
        assertThat(alm.commentsWritten).isEmpty();
        Approval pending = parked.pending().orElseThrow();
        assertThat(pending.kind()).isEqualTo(Approval.Kind.ACTION);
        assertThat(pending.toolName()).isEqualTo("jira.add_comment");
        assertThat(pending.arguments()).containsEntry("ticket_key", "IT-2");

        Optional<Workbench.Outcome> resumed =
                workbench.resume(pending.id(), "sid@example.com", true, "Looks right.");

        assertThat(resumed).isPresent();
        assertThat(resumed.get().run().status()).isEqualTo(Run.Status.COMPLETED);
        // The approval authorised one execution: the second identical proposal was refused,
        // and the spent approval is filed CONSUMED so no later resume can replay it.
        assertThat(alm.commentsWritten).hasSize(1);
        Approval decided = store.approval("default", pending.id()).orElseThrow();
        assertThat(decided.state()).isEqualTo(Approval.State.CONSUMED);
        assertThat(decided.decidedBy()).isEqualTo("sid@example.com");
        assertThat(resumed.get().run().trigger()).isEqualTo(Run.Trigger.RESUME);
        assertThat(store.run("default", parked.run().id()).orElseThrow().status())
                .isEqualTo(Run.Status.COMPLETED);
    }

    @Test
    void aRejectionCancelsTheParkedRunAndNothingRuns() {
        FakeAlm alm = new FakeAlm(FakeAlm.ticket("IT-2", "Grant access", "Please add someone."));
        WorkbenchStore store = new WorkbenchStore();
        ScriptedLlm llm = new ScriptedLlm().proposes("jira.add_comment", COMMENT);
        Workbench workbench = new Workbench(store, llm, "scripted", alm,
                new Learnings(MemoryStore.inMemory(), "default"), "default");

        Workbench.Outcome parked = workbench.execute("IT-2", Run.Trigger.OPERATOR);
        Optional<Workbench.Outcome> rejected = workbench.resume(
                parked.pending().orElseThrow().id(), "sid@example.com", false, "Not this one.");

        assertThat(rejected).isPresent();
        assertThat(rejected.get().run().status()).isEqualTo(Run.Status.CANCELLED);
        assertThat(alm.commentsWritten).isEmpty();
    }
}
