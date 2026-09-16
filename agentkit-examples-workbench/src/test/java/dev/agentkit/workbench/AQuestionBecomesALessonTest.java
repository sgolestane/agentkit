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
 * The workbench's learning loop, end to end: the agent hits ambiguity and asks, the run
 * parks as a question, the human answers, the resumed run receives the answer through
 * {@code ask_human}, the answer becomes a durable lesson, and the next run's goal already
 * carries it — so the same question need never be asked again.
 */
class AQuestionBecomesALessonTest {

    private static final String QUESTION =
            "Which system should I use to create the mailbox?";

    @Test
    void askParkAnswerResumeAndTheNextRunAlreadyKnows() {
        FakeAlm alm = new FakeAlm(FakeAlm.ticket("IT-3", "Create a mailbox",
                "Create a mailbox for John Smith."));
        WorkbenchStore store = new WorkbenchStore();
        Learnings learnings = new Learnings(MemoryStore.inMemory(), "default");
        ScriptedLlm llm = new ScriptedLlm()
                // first run: asks, which parks
                .proposes(dev.agentkit.workbench.tools.WorkbenchTools.ASK_HUMAN,
                        ScriptedLlm.args("question", QUESTION))
                // resumed run: asks again — this time the answer is waiting — then closes
                .proposes(dev.agentkit.workbench.tools.WorkbenchTools.ASK_HUMAN,
                        ScriptedLlm.args("question", QUESTION))
                .says("Mailbox created in Microsoft 365.")
                // the later preview run just answers
                .says("Plan: create the mailbox in Microsoft 365.");
        Workbench workbench = new Workbench(store, llm, "scripted", alm, learnings, "default");

        Workbench.Outcome parked = workbench.execute("IT-3", Run.Trigger.OPERATOR);

        assertThat(parked.parked()).isTrue();
        Approval pending = parked.pending().orElseThrow();
        assertThat(pending.kind()).isEqualTo(Approval.Kind.QUESTION);
        assertThat(pending.question()).isEqualTo(QUESTION);

        Optional<Workbench.Outcome> resumed = workbench.answer(pending.id(),
                "sid@example.com", "We use Microsoft 365 for employee mailboxes.");

        assertThat(resumed).isPresent();
        assertThat(resumed.get().run().status()).isEqualTo(Run.Status.COMPLETED);
        // The answer reached the resumed run through the tool, on the audit trail.
        assertThat(store.events(resumed.get().run().id()))
                .anyMatch(event -> event.type() == Run.Event.Type.HUMAN_ANSWERED);
        // And it is now durable knowledge…
        assertThat(learnings.recall())
                .anyMatch(lesson -> lesson.contains("Microsoft 365"));
        // …that the next run is handed before it starts.
        Workbench.Outcome next = workbench.preview("IT-3");
        assertThat(next.run().goal()).contains("Microsoft 365");
    }
}
