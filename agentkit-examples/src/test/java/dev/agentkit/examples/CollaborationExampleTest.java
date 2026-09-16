package dev.agentkit.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.collab.Blackboard;
import dev.agentkit.core.collab.RefineLoop;
import dev.agentkit.core.collab.RefineResult;
import org.junit.jupiter.api.Test;

/** Drives {@link CollaborationExample}'s refine loop against a scripted model. */
class CollaborationExampleTest {

    private static final String MODEL = "fake-model";

    @Test
    void writerDraftsAndTheEditorApproves() {
        // The loop is synchronous, so a single FIFO script is deterministic: the
        // writer produces a draft (turn 1), then the editor critic approves (turn 2).
        FakeLlm llm = new FakeLlm()
                .then(FakeLlm.text("Durable execution lets agents survive crashes and resume "
                        + "exactly where they left off. It records each step so retries never "
                        + "double-act. That makes long-running agents reliable."))
                .then(FakeLlm.text("APPROVE"));

        Blackboard board = new Blackboard();
        RefineLoop loop = CollaborationExample.build(llm, MODEL, board);
        RefineResult result = loop.run(Goal.of("Brief on durable execution."));

        assertThat(result.approved()).isTrue();
        assertThat(result.rounds()).isEqualTo(1);
        assertThat(result.output()).contains("Durable execution");
        assertThat(llm.remaining()).isZero(); // writer + editor consumed exactly the script
    }
}
