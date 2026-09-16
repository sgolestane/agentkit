package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.workbench.domain.Run;
import org.junit.jupiter.api.Test;

/**
 * "What would the agent do?" is answered by running it with every writing tool refused.
 *
 * <p>{@code APreviewChangesNothingTest} is the ancestor. The claim is the same and it is worth
 * restating over the console's own path, because the console is where the distinction is
 * easiest to lose: {@code workbench.preview} and {@code workbench.execute} differ by one word in a tool
 * name, and a wiring slip between them is a rehearsal that quietly worked the ticket.
 */
class APreviewFromTheChatChangesNothingTest {

    @Test
    void everyWriterInsideARehearsalIsRefusedAndTheAlmNeverHears() {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-1", "Grant access", "Add me."));
        Console console = new Console(alm);
        // A model that tries everything it has. None of it may reach Jira.
        console.llm.proposes("jira.assign_to_me", ScriptedLlm.args("ticket_key", "IT-1"))
                .proposes("jira.add_comment",
                        ScriptedLlm.args("ticket_key", "IT-1", "body", "Done."))
                .proposes("jira.transition_ticket",
                        ScriptedLlm.args("ticket_key", "IT-1", "transition", "Done"))
                .says("I would assign it, comment, and resolve it.");

        String said = console.say("workbench.preview", "ticket_key", "IT-1");

        assertThat(alm.commentsWritten).isEmpty();
        assertThat(alm.transitionsRun).isEmpty();
        assertThat(alm.assignments).isEmpty();
        assertThat(console.store.runs(Console.TENANT)).singleElement()
                .satisfies(run -> assertThat(run.mode()).isEqualTo(Run.Mode.PREVIEW));
        assertThat(said).contains("PREVIEW");
    }

    @Test
    void aRehearsalDoesNotStopToAskBecauseThereIsNothingToApprove() {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-1", "Grant access", "Add me."));
        Console console = new Console(alm);
        console.llm.proposes("jira.add_comment",
                        ScriptedLlm.args("ticket_key", "IT-1", "body", "Done."))
                .says("I would have commented.");

        console.say("workbench.preview", "ticket_key", "IT-1");

        // The difference between a rehearsal and a supervised run: one is told no and carries
        // on, the other stops and waits. A person asking "what would you do" and being handed
        // an approval to sign has been asked a different question than the one they asked.
        assertThat(console.store.approvals(Console.TENANT)).isEmpty();
        assertThat(console.store.runs(Console.TENANT).getFirst().status())
                .isEqualTo(Run.Status.COMPLETED);
    }
}
