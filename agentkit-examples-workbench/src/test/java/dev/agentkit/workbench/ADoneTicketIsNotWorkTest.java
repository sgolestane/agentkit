package dev.agentkit.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.Workbench;
import dev.agentkit.workbench.store.WorkbenchStore;
import org.junit.jupiter.api.Test;

/**
 * A done ticket is not work: executing it is refused with a sentence — at the runtime, so
 * every door (console, bulk, HTTP) gets the same answer — while a preview stays allowed,
 * because asking what the agent would have done changes nothing.
 */
class ADoneTicketIsNotWorkTest {

    @Test
    void executeIsRefusedAndPreviewIsNot() {
        FakeAlm alm = new FakeAlm(FakeAlm.doneTicket("IT-7", "Guest wifi details",
                "Please reply with the wifi details and close the ticket."));
        WorkbenchStore store = new WorkbenchStore();
        ScriptedLlm llm = new ScriptedLlm().says("I would have replied with the details.");
        Workbench workbench = new Workbench(store, llm, "scripted", alm,
                new Learnings(MemoryStore.inMemory(), "default"), "default");

        assertThatThrownBy(() -> workbench.execute("IT-7", Run.Trigger.OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already done");
        assertThat(store.runs("default")).isEmpty();
        assertThat(alm.commentsWritten).isEmpty();

        Workbench.Outcome preview = workbench.preview("IT-7");
        assertThat(preview.run().status()).isEqualTo(Run.Status.COMPLETED);
        assertThat(alm.commentsWritten).isEmpty();
    }

    /**
     * Nor is a ticket already being worked: a second run would propose the same writes
     * twice and cost the operator the same decision twice. A resume is not this path, so
     * the parked run's own continuation still works.
     */
    @Test
    void aTicketAlreadyBeingWorkedIsRefusedAndItsOwnResumeIsNot() {
        FakeAlm alm = new FakeAlm(FakeAlm.ticket("IT-8", "Grant access", "Please add dana."));
        WorkbenchStore store = new WorkbenchStore();
        ScriptedLlm llm = new ScriptedLlm()
                .proposes("jira.add_comment",
                        ScriptedLlm.args("ticket_key", "IT-8", "body", "On it."))
                .proposes("jira.add_comment",
                        ScriptedLlm.args("ticket_key", "IT-8", "body", "On it."))
                .says("Done.");
        Workbench workbench = new Workbench(store, llm, "scripted", alm,
                new Learnings(MemoryStore.inMemory(), "default"), "default");
        Workbench.Outcome parked = workbench.execute("IT-8", Run.Trigger.OPERATOR);
        assertThat(parked.parked()).isTrue();

        assertThatThrownBy(() -> workbench.execute("IT-8", Run.Trigger.OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already working");

        // The parked run's own continuation is untouched by the guard.
        assertThat(workbench.resume(parked.pending().orElseThrow().id(), "sid", true, ""))
                .isPresent();
        assertThat(alm.commentsWritten).hasSize(1);
    }
}
