package dev.agentkit.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.workbench.domain.Approval;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.Workbench;
import dev.agentkit.workbench.store.WorkbenchStore;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The approve/park ping-pong, pinned closed. A resumed run may re-walk its checklist in a
 * different order than the approvals arrived — lead with the assign while holding the
 * comment's approval. Carrying only the newest approval parked the run again on a step a
 * human had already cleared, so the same comment was approved three times before it ever
 * ran (measured in the browser). A resume now carries every approved-but-unconsumed action
 * for the ticket, each single-use, and the whole ladder settles in one approval per write.
 */
class ApprovalsSurviveAReorderedResumeTest {

    private static final Map<String, Object> COMMENT =
            ScriptedLlm.args("ticket_key", "IT-9", "body", "Done, details inside.");
    private static final Map<String, Object> ASSIGN = ScriptedLlm.args("ticket_key", "IT-9");

    @Test
    void anEarlierApprovalIsStillGoodWhenTheModelReordersItsSteps() {
        FakeAlm alm = new FakeAlm(FakeAlm.ticket("IT-9", "Two-step ticket",
                "Comment and take ownership."));
        WorkbenchStore store = new WorkbenchStore();
        ScriptedLlm llm = new ScriptedLlm()
                // run 1: proposes the comment → parks (apr A)
                .proposes("jira.add_comment", COMMENT)
                // run 2 (A approved): leads with ASSIGN instead → parks (apr B);
                // the comment approval is NOT consumed here
                .proposes("jira.assign_to_me", ASSIGN)
                // run 3 (B approved, A still unconsumed): assign runs on B, comment runs
                // on A — no third park
                .proposes("jira.assign_to_me", ASSIGN)
                .proposes("jira.add_comment", COMMENT)
                .says("Both steps done.");
        Workbench workbench = new Workbench(store, llm, "scripted", alm,
                new Learnings(MemoryStore.inMemory(), "default"), "default");

        Workbench.Outcome first = workbench.execute("IT-9", Run.Trigger.OPERATOR);
        assertThat(first.parked()).isTrue();
        Workbench.Outcome second = workbench
                .resume(first.pending().orElseThrow().id(), "sid", true, "").orElseThrow();
        assertThat(second.parked()).isTrue();
        assertThat(second.pending().orElseThrow().toolName()).isEqualTo("jira.assign_to_me");

        Workbench.Outcome third = workbench
                .resume(second.pending().orElseThrow().id(), "sid", true, "").orElseThrow();

        assertThat(third.parked()).isFalse();
        assertThat(third.run().status()).isEqualTo(Run.Status.COMPLETED);
        assertThat(alm.assignments).containsExactly("IT-9");
        assertThat(alm.commentsWritten).hasSize(1);
        // Two approvals for two writes — and both are spent, so nothing can replay them.
        assertThat(store.approvals("default"))
                .filteredOn(a -> a.kind() == Approval.Kind.ACTION)
                .hasSize(2)
                .allMatch(a -> a.state() == Approval.State.CONSUMED);
    }
}
