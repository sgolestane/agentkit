package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.workbench.domain.Approval;
import dev.agentkit.workbench.domain.Run;
import org.junit.jupiter.api.Test;

/**
 * A run that stops to ask is the normal case, not the failure case — so the conversation has
 * to be able to finish it.
 *
 * <p>An operator who says "go ahead" in a chat window and is told the run is still waiting has
 * a console that can start work and cannot finish it. This is the half of {@code workbench.execute}
 * that makes it worth having.
 */
class AParkedRunIsDecidedInTheConversationTest {

    private static final java.util.Map<String, Object> COMMENT =
            ScriptedLlm.args("ticket_key", "IT-2", "body", "Access granted per the request.");

    @Test
    void approvingInTheConversationLetsTheRunFinishAndTheCommentLands() {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-2", "Grant access",
                "Please add dana@example.com to the reporting group."));
        Console console = new Console(alm);
        console.llm.proposes("jira.add_comment", COMMENT)
                .proposes("jira.add_comment", COMMENT)
                .says("Commented and finished.");

        String started = console.say("workbench.execute", "ticket_key", "IT-2");
        assertThat(started).contains("stopped to ask");
        assertThat(alm.commentsWritten).isEmpty();

        Approval pending = console.store.approvals(Console.TENANT).stream()
                .filter(one -> one.state() == Approval.State.PENDING)
                .findFirst().orElseThrow();
        assertThat(console.say("approvals.list")).contains(pending.id());

        String resumed = console.say("approvals.decide",
                "approval_id", pending.id(), "approved", true, "note", "Looks right.");

        assertThat(resumed).contains("COMPLETED");
        assertThat(alm.commentsWritten).hasSize(1);
        Approval decided = console.store.approval(Console.TENANT, pending.id()).orElseThrow();
        assertThat(decided.state()).isEqualTo(Approval.State.CONSUMED);
        // Who decided is the question the record exists to answer. "operator" answers it for
        // exactly as long as there is one of them.
        assertThat(decided.decidedBy()).isEqualTo(Console.OPERATOR);
        assertThat(decided.note()).isEqualTo("Looks right.");
    }

    @Test
    void aRehearsalDoesNotPark_BecauseThereIsNothingToApprove() {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-2", "Grant access", "Add someone."));
        Console console = new Console(alm);
        console.llm.proposes("jira.add_comment", COMMENT).says("I would have commented.");

        String said = console.say("workbench.preview", "ticket_key", "IT-2");

        // The distinction the operator is relying on when they ask "what would you do". A
        // rehearsal refuses the writers outright, so the model is told no and carries on; an
        // execute stops and asks. A preview that quietly executed would look identical in the
        // transcript right up to the point where somebody's ticket had changed.
        assertThat(console.store.runs(Console.TENANT)).singleElement()
                .satisfies(run -> {
                    assertThat(run.mode()).isEqualTo(Run.Mode.PREVIEW);
                    assertThat(run.status()).isEqualTo(Run.Status.COMPLETED);
                });
        assertThat(console.store.approvals(Console.TENANT)).isEmpty();
        assertThat(said).doesNotContain("stopped to ask");
        assertThat(alm.commentsWritten).isEmpty();
    }

    @Test
    void refusingCancelsTheRunAndNothingReachesJira() {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-2", "Grant access", "Add someone."));
        Console console = new Console(alm);
        console.llm.proposes("jira.add_comment", COMMENT);

        console.say("workbench.execute", "ticket_key", "IT-2");
        Approval pending = console.store.approvals(Console.TENANT).getFirst();

        String refused = console.say("approvals.decide",
                "approval_id", pending.id(), "approved", false, "note", "Not this one.");

        assertThat(refused).contains("CANCELLED");
        assertThat(alm.commentsWritten).isEmpty();
    }

    @Test
    void approvedMustBeSaidRatherThanAssumed() {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-2", "Grant access", "Add someone."));
        Console console = new Console(alm);
        console.llm.proposes("jira.add_comment", COMMENT);
        console.say("workbench.execute", "ticket_key", "IT-2");
        String id = console.store.approvals(Console.TENANT).getFirst().id();

        // A missing 'approved' is the model not having asked, and a default either way is the
        // console deciding on the operator's behalf. Both directions are wrong, so neither is
        // taken: it refuses and says to go and ask.
        ToolResult refused = console.call("approvals.decide", "approval_id", id);

        assertThat(refused.isError()).isTrue();
        assertThat(refused.content()).contains("do not decide it for them");
        assertThat(console.store.approval(Console.TENANT, id).orElseThrow().state())
                .isEqualTo(Approval.State.PENDING);
        assertThat(console.store.run(Console.TENANT,
                        console.store.runs(Console.TENANT).getFirst().id()).orElseThrow()
                .status()).isEqualTo(Run.Status.WAITING_FOR_HUMAN);
    }

    @Test
    void anApprovalThatIsNotWaitingIsAnAnswerNotAnException() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-2", "Grant access", "Add someone.")));

        ToolResult missing = console.call("approvals.decide",
                "approval_id", "ap-nope", "approved", true);

        assertThat(missing.isError()).isTrue();
        assertThat(missing.content()).contains("approvals.list");
    }
}
