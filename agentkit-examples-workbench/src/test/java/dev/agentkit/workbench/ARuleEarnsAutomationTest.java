package dev.agentkit.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.workbench.domain.AutomationRule;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.domain.TriageVerdict;
import dev.agentkit.workbench.runtime.AnswerBox;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.RunContext;
import dev.agentkit.workbench.runtime.Supervisor;
import dev.agentkit.workbench.runtime.Workbench;
import dev.agentkit.workbench.store.WorkbenchStore;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Progressive automation, earned per category: with a person's rule enabled for this
 * ticket's triage category, the run starts in AUTO and its ordinary writes proceed without
 * parking — while anything graded HIGH, including every tool nobody wrote a policy for,
 * still stops for a person. Automation never widens what a human would have been asked
 * about.
 */
class ARuleEarnsAutomationTest {

    @Test
    void aCoveredCategoryRunsItsMediumWritesWithoutParking() {
        FakeAlm alm = new FakeAlm(FakeAlm.ticket("IT-4", "Access request",
                "Please add robin@example.com to the analytics viewers group."));
        WorkbenchStore store = new WorkbenchStore();
        store.saveTriage("default", new WorkbenchStore.TriageEntry("IT-4",
                Instant.parse("2026-08-27T10:00:00Z"),
                new TriageVerdict(true, "access-request", 0.92, "Grant and comment.", ""),
                Instant.now(), 0));
        store.save(new AutomationRule("rule-1", "default", "access-request", true,
                "sid@example.com", Instant.now()));
        ScriptedLlm llm = new ScriptedLlm()
                .proposes("jira.assign_to_me", ScriptedLlm.args("ticket_key", "IT-4"))
                .proposes("jira.add_comment",
                        ScriptedLlm.args("ticket_key", "IT-4", "body", "Granted."))
                .proposes("jira.transition_ticket",
                        ScriptedLlm.args("ticket_key", "IT-4", "transition", "Done"))
                .says("Handled automatically.");
        Workbench workbench = new Workbench(store, llm, "scripted", alm,
                new Learnings(MemoryStore.inMemory(), "default"), "default");

        Workbench.Outcome outcome = workbench.execute("IT-4", Run.Trigger.RULE);

        assertThat(outcome.run().mode()).isEqualTo(Run.Mode.AUTO);
        assertThat(outcome.parked()).isFalse();
        assertThat(outcome.run().status()).isEqualTo(Run.Status.COMPLETED);
        assertThat(alm.assignments).containsExactly("IT-4");
        assertThat(alm.commentsWritten).hasSize(1);
        assertThat(alm.transitionsRun).containsExactly("IT-4 -> Done");
        assertThat(store.approvals("default")).isEmpty();
    }

    @Test
    void anUndeclaredToolStillParksEvenUnderAutomation() {
        WorkbenchStore store = new WorkbenchStore();
        RunContext context = new RunContext("default", "run-x", store);
        Supervisor supervisor = new Supervisor(Run.Mode.AUTO, context, store, "IT-4",
                AnswerBox.EMPTY, java.util.List.of());
        var tool = FunctionTool.builder("identity.delete_user", "no policy exists for this")
                .handler(invocation -> ToolResult.ok("never reached"))
                .build();

        GateResult result = supervisor.evaluate(tool,
                new ToolInvocation("call-1", "identity.delete_user",
                        Map.of("email", "robin@example.com")));

        assertThat(result.allowed()).isFalse();
        assertThat(result.awaiting()).isPresent();
        assertThat(store.approvals("default")).hasSize(1);
    }

    @Test
    void withoutARuleTheSameTicketStaysSupervised() {
        FakeAlm alm = new FakeAlm(FakeAlm.ticket("IT-4", "Access request", "Please add someone."));
        WorkbenchStore store = new WorkbenchStore();
        store.saveTriage("default", new WorkbenchStore.TriageEntry("IT-4",
                Instant.parse("2026-08-27T10:00:00Z"),
                new TriageVerdict(true, "access-request", 0.92, "Grant and comment.", ""),
                Instant.now(), 0));
        ScriptedLlm llm = new ScriptedLlm()
                .proposes("jira.assign_to_me", ScriptedLlm.args("ticket_key", "IT-4"));
        Workbench workbench = new Workbench(store, llm, "scripted", alm,
                new Learnings(MemoryStore.inMemory(), "default"), "default");

        Workbench.Outcome outcome = workbench.execute("IT-4", Run.Trigger.OPERATOR);

        assertThat(outcome.run().mode()).isEqualTo(Run.Mode.SUPERVISED);
        assertThat(outcome.parked()).isTrue();
        assertThat(alm.assignments).isEmpty();
    }
}
