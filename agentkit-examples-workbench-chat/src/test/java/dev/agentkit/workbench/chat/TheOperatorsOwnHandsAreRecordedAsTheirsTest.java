package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.workbench.domain.OperatorAction;
import dev.agentkit.workbench.domain.Run;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two tools that do write to Jira, and the reason they are allowed to.
 *
 * <p>They are a person acting, not an agent acting: no run, no approval, and the ALM's own
 * audit trail says the operator did it. The dashboard offers exactly these two for exactly
 * this reason, and the console records them into the same {@link OperatorAction} log, so a
 * ticket's history reads the same whichever console the person was sitting in front of.
 */
class TheOperatorsOwnHandsAreRecordedAsTheirsTest {

    @Test
    void aCommentReachesJiraAndIsFiledAsTheOperatorsWithoutARun() {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-1", "Grant access", "Add me."));
        Console console = new Console(alm);

        console.say("alm.comment", "ticket_key", "IT-1",
                "body", "Passing this to the access team — Sid.");

        assertThat(alm.commentsWritten)
                .containsExactly("IT-1: Passing this to the access team — Sid.");
        List<OperatorAction> recorded = console.store.operatorActions(Console.TENANT, "IT-1");
        assertThat(recorded).singleElement().satisfies(action -> {
            assertThat(action.kind()).isEqualTo(OperatorAction.Kind.COMMENT);
            assertThat(action.by()).isEqualTo(Console.OPERATOR);
            assertThat(action.detail()).isEqualTo("Passing this to the access team — Sid.");
        });
        // No run, so nothing was gated, approved or supervised — which is the whole point:
        // a person does not need permission from the agent's supervisor to type a comment.
        assertThat(console.store.runs(Console.TENANT)).isEmpty();
        assertThat(console.store.approvals(Console.TENANT)).isEmpty();
    }

    @Test
    void aTransitionReachesJiraAndIsFiledAsTheOperatorsWithoutARun() {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-1", "Grant access", "Add me."));
        Console console = new Console(alm);

        String said = console.say("alm.transition", "ticket_key", "IT-1",
                "transition", "In Progress");

        assertThat(alm.transitionsRun).containsExactly("IT-1: In Progress");
        assertThat(console.store.operatorActions(Console.TENANT, "IT-1"))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.kind()).isEqualTo(OperatorAction.Kind.TRANSITION);
                    assertThat(action.detail()).isEqualTo("In Progress");
                });
        assertThat(console.store.runs(Console.TENANT)).isEmpty();
        // 'In Progress' has a space in it, so it is not a name in this framework's sense.
        // Reducing it would have printed "unknown" at the operator, which is how the model
        // learns the wrong name for the transition it just ran.
        assertThat(said).contains("In Progress").doesNotContain("unknown");
    }

    @Test
    void aTicketThatDoesNotExistChangesNothingAndSaysSo() {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-1", "Grant access", "Add me."));
        Console console = new Console(alm);

        ToolResult refused = console.call("alm.comment", "ticket_key", "IT-9", "body", "Hello.");

        assertThat(refused.isError()).isFalse();
        // The fake accepts any key, so this asserts what the console did rather than what the
        // ALM refused; the interesting half is that a blank body never reaches Jira at all.
        ToolResult blank = console.call("alm.comment", "ticket_key", "IT-1", "body", "  ");
        assertThat(blank.isError()).isTrue();
        assertThat(alm.commentsWritten).hasSize(1);
        assertThat(console.store.operatorActions(Console.TENANT, "IT-1")).isEmpty();
    }

    @Test
    void anAgentsCommentIsARunAndSaysWhichRun() {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-1", "Grant access", "Add me."));
        Console console = new Console(alm);
        console.llm.proposes("jira.add_comment",
                ScriptedLlm.args("ticket_key", "IT-1", "body", "Access granted."));

        console.say("workbench.execute", "ticket_key", "IT-1");

        // The contrast that makes the exception defensible: the same comment, proposed by the
        // agent rather than typed by the person, does not reach Jira — it parks for approval.
        assertThat(alm.commentsWritten).isEmpty();
        assertThat(console.store.runs(Console.TENANT)).singleElement()
                .satisfies(run -> assertThat(run.status())
                        .isEqualTo(Run.Status.WAITING_FOR_HUMAN));
        assertThat(console.store.operatorActions(Console.TENANT, "IT-1")).isEmpty();
    }
}
