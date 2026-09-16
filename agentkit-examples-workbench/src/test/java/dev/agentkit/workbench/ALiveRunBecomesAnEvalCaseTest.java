package dev.agentkit.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.workbench.capture.EvalCaptures;
import dev.agentkit.workbench.capture.EvalCaptures.CapturedCase;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.Workbench;
import dev.agentkit.workbench.store.WorkbenchStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Capturing a live run as an eval case, deterministically: the parked supervised run —
 * the shape a person most naturally captures — round-trips through the JSON file with the
 * facts a replay derives its checks from, and a run that teaches nothing is refused.
 */
class ALiveRunBecomesAnEvalCaseTest {

    @TempDir
    Path dir;

    @Test
    void aParkedRunCapturesItsFactsAndRoundTrips() {
        FakeAlm alm = new FakeAlm(FakeAlm.ticket("IT-2", "Grant access",
                "Please add dana@example.com to the reporting group."));
        WorkbenchStore store = new WorkbenchStore();
        Learnings learnings = new Learnings(MemoryStore.inMemory(), "default");
        learnings.record("Reporting access is granted through the Analytics Viewers group.");
        ScriptedLlm llm = new ScriptedLlm().proposes("jira.add_comment",
                ScriptedLlm.args("ticket_key", "IT-2", "body", "On it."));
        Workbench workbench = new Workbench(store, llm, "scripted", alm, learnings, "default");
        Workbench.Outcome parked = workbench.execute("IT-2", Run.Trigger.OPERATOR);

        EvalCaptures captures = new EvalCaptures(dir, store, alm, learnings, "default");
        Path file = captures.capture(parked.run().id());

        List<CapturedCase> loaded = EvalCaptures.load(dir);
        assertThat(file).exists();
        assertThat(loaded).hasSize(1);
        CapturedCase captured = loaded.get(0);
        assertThat(captured.runId()).isEqualTo(parked.run().id());
        assertThat(captured.ticketKey()).isEqualTo("IT-2");
        assertThat(captured.mode()).isEqualTo(Run.Mode.SUPERVISED);
        assertThat(captured.finalStatus()).isEqualTo(Run.Status.WAITING_FOR_HUMAN);
        assertThat(captured.parkedKind()).isEqualTo("ACTION");
        assertThat(captured.parkedTool()).isEqualTo("jira.add_comment");
        // The park held, so no write ran, and the knowledge travels with the case.
        assertThat(captured.writesThatRan()).isEmpty();
        assertThat(captured.learnings())
                .anyMatch(lesson -> lesson.contains("Analytics Viewers"));
        assertThat(captured.description()).contains("dana@example.com");
    }

    @Test
    void aRunThatTeachesNothingIsRefused() {
        FakeAlm alm = new FakeAlm(FakeAlm.ticket("IT-2", "Grant access", "Please."));
        WorkbenchStore store = new WorkbenchStore();
        Learnings learnings = new Learnings(MemoryStore.inMemory(), "default");
        Run failed = store.save(store
                .createRun("default", "IT-2", Run.Mode.SUPERVISED, Run.Trigger.OPERATOR, "go")
                .withStatus(Run.Status.RUNNING)
                .concluded(Run.Status.FAILED, "the model call threw"));

        EvalCaptures captures = new EvalCaptures(dir, store, alm, learnings, "default");

        assertThatThrownBy(() -> captures.capture(failed.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teaches a replay nothing");
        assertThat(EvalCaptures.load(dir)).isEmpty();
    }
}
