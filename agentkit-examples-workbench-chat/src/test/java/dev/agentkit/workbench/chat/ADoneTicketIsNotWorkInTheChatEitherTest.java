package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.domain.Ticket;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * A ticket somebody already closed is not work, and a ticket already being worked is not work
 * twice.
 *
 * <p>{@code ADoneTicketIsNotWorkTest} is the ancestor and it says why the check lives in
 * {@code Workbench} rather than in a console: "at the runtime, so every door (console, bulk,
 * HTTP) gets the same answer". This is the test that the chat is one of those doors and did not
 * quietly become an exception to it — the runtime <em>throws</em>, and a console that let the
 * throw escape would end the operator's turn with a stack trace instead of a sentence.
 */
class ADoneTicketIsNotWorkInTheChatEitherTest {

    private static Ticket done(String key) {
        return new Ticket(key, "jira", "Guest wifi details",
                "Please reply with the wifi details and close the ticket.", "Done",
                Ticket.Category.DONE, "Medium", "Someone Else", "Riley Chen",
                Instant.parse("2026-08-27T09:00:00Z"), Instant.parse("2026-08-27T10:00:00Z"));
    }

    @Test
    void executingAClosedTicketIsAnAnswerAndARehearsalIsStillAllowed() {
        ConsoleAlm alm = new ConsoleAlm(done("IT-7"));
        Console console = new Console(alm);
        console.llm.says("I would have replied with the details.");

        ToolResult refused = console.call("workbench.execute", "ticket_key", "IT-7");

        // A sentence the model can act on, not a thrown exception. The runtime raises
        // IllegalArgumentException here, and this is the seam where that becomes an answer.
        assertThat(refused.isError()).isTrue();
        assertThat(refused.content()).containsIgnoringCase("already done");
        assertThat(console.store.runs(Console.TENANT)).isEmpty();
        assertThat(alm.commentsWritten).isEmpty();

        // Asking what the agent would have done changes nothing, so it stays allowed — which is
        // the distinction the ancestor draws and the one somebody would lose by refusing the
        // ticket outright at the console.
        assertThat(console.call("workbench.preview", "ticket_key", "IT-7").isError()).isFalse();
        assertThat(console.store.runs(Console.TENANT)).singleElement()
                .satisfies(run -> assertThat(run.mode()).isEqualTo(Run.Mode.PREVIEW));
        assertThat(alm.commentsWritten).isEmpty();
    }

    @Test
    void aTicketAlreadyBeingWorkedIsNotStartedASecondTime() {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-8", "Grant access", "Add dana."));
        Console console = new Console(alm);
        console.llm.proposes("jira.add_comment",
                        ScriptedLlm.args("ticket_key", "IT-8", "body", "On it."))
                .proposes("jira.add_comment",
                        ScriptedLlm.args("ticket_key", "IT-8", "body", "On it."))
                .says("Done.");
        assertThat(console.say("workbench.execute", "ticket_key", "IT-8")).contains("stopped to ask");

        ToolResult again = console.call("workbench.execute", "ticket_key", "IT-8");

        // A second run would propose the same write twice and cost the operator the same
        // decision twice. In a conversation this is the likelier slip of the two: an operator
        // says "go on then" and the model, not sure whether the first one took, tries again.
        assertThat(again.isError()).isTrue();
        assertThat(console.store.runs(Console.TENANT)).hasSize(1);

        // And the parked run's own continuation is not this path, so it still works.
        String approvalId = console.store.approvals(Console.TENANT).getFirst().id();
        console.say("approvals.decide", "approval_id", approvalId, "approved", true);
        assertThat(alm.commentsWritten).containsExactly("IT-8: On it.");
    }

    @Test
    void theInboxIsWhatIsOpen() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me."),
                done("IT-2")));

        String said = console.say("tickets.inbox");

        assertThat(said).contains("IT-1").doesNotContain("IT-2");
        assertThat(said).contains("1 open ticket(s)");
    }

    @Test
    void aClosedTicketCanStillBeReadBecauseSomebodyMayAskAboutIt() {
        Console console = new Console(new ConsoleAlm(done("IT-2")));

        // Absent from the inbox is not the same as unreachable. "What happened with IT-2" is
        // an ordinary question, and a console that could not answer it would be worse rather
        // than safer — the safety is in not offering it as work, not in hiding it.
        assertThat(console.say("tickets.get", "ticket_key", "IT-2"))
                .contains("IT-2").contains("Done");
        assertThat(console.say("tickets.inbox")).isEqualTo("Nothing is open.");
    }
}
