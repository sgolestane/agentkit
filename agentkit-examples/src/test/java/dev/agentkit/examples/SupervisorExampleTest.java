package dev.agentkit.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.supervisor.DelegatedTask;
import dev.agentkit.core.supervisor.SupervisionResult;
import dev.agentkit.core.supervisor.Supervisor;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Drives {@link SupervisorExample} against a scripted fake model. */
class SupervisorExampleTest {

    private static final String MODEL = "fake-model";

    @Test
    void fansOutToBothSubagentsAndSynthesizes() {
        // Two subagents each take one turn (order across threads is unspecified, but
        // both complete before the synthesis call, which is the third response).
        FakeLlm llm = new FakeLlm()
                .then(FakeLlm.text("research notes"))
                .then(FakeLlm.text("draft brief"))
                .then(FakeLlm.text("Final synthesized brief."));

        Supervisor supervisor = SupervisorExample.build(llm, MODEL);
        SupervisionResult result = supervisor.fanOut(
                Goal.of("Brief me on durable execution."),
                List.of(DelegatedTask.of("researcher", "gather facts"),
                        DelegatedTask.of("writer", "draft the brief")));

        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.outcomes()).hasSize(2);
        assertThat(result.output()).isEqualTo("Final synthesized brief.");
        // NB: the two subagent texts are interchangeable — they're consumed FIFO in
        // nondeterministic thread order, so do NOT assert which subagent got which.
        // Only the synthesis (always the 3rd, awaited-after-both) call is pinned.
    }
}
