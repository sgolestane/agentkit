package dev.agentkit.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What the in-process loop does when a gate says a person must decide (#101).
 *
 * <p>It has nobody to ask. A blocking {@code Approver} is still available and still the
 * right answer for an interactive harness that can prompt; this is the unattended case,
 * where the decision will arrive later through some other door and holding a thread until
 * then is not a plan. So the run <em>ends</em>, and says what it is waiting on.
 *
 * <p><strong>Every test here counts what the tool did.</strong> Asking the loop whether it
 * stopped is asking the control whether it worked — it passes for a loop that reports
 * {@code AWAITING_APPROVAL} and publishes anyway, which is the exact class of bug this
 * repository has shipped twice.
 */
class AgentParkedApprovalTest {

    private static final AgentConfig CONFIG = AgentConfig.builder("m").maxSteps(5).build();

    /** A tool that records every invocation it was actually entered with. */
    private static Tool publisher(List<ToolInvocation> entered) {
        return FunctionTool.builder("publish", "Publishes text somewhere public")
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> {
                    entered.add(invocation);
                    return ToolResult.ok("published");
                })
                .build();
    }

    private static Tool reader(List<ToolInvocation> entered) {
        return FunctionTool.builder("read", "Reads something harmless")
                .sideEffects(SideEffects.NONE)
                .handler(invocation -> {
                    entered.add(invocation);
                    return ToolResult.ok("read it");
                })
                .build();
    }

    /** One assistant turn requesting a publish and then a read, in that order. */
    private static dev.agentkit.core.llm.LlmResponse twoCalls() {
        return dev.agentkit.core.llm.LlmResponse.of(
                dev.agentkit.core.message.Message.of(dev.agentkit.core.message.Role.ASSISTANT,
                        List.of(dev.agentkit.core.message.ProposedCall.of("t1", "publish",
                                        Map.of("text", "x")),
                                dev.agentkit.core.message.ProposedCall.of("t2", "read",
                                        Map.of()))),
                dev.agentkit.core.llm.LlmStopReason.TOOL_USE, TokenUsage.ZERO);
    }

    private static Agent agent(FakeLlmClient llm, List<Tool> tools) {
        return Agent.builder(llm, new SimpleToolRegistry(tools), CONFIG)
                .toolGate(ToolGates.parkForApproval(inv -> inv.name().equals("publish"),
                        ApprovalNeeded.because("publishing needs a person")
                                .withEffect("The text becomes publicly visible.")))
                .build();
    }

    @Test
    void aParkedCallEndsTheRunAndTheToolNeverRuns() {
        List<ToolInvocation> entered = new ArrayList<>();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "publish", Map.of("text", "the payload")),
                FakeLlmClient.text("should never be reached"));

        AgentResult result = agent(llm, List.of(publisher(entered))).run(Goal.of("publish"));

        assertThat(entered)
                .as("the loop reported a stop and ran the tool anyway is the failure this"
                        + " asserts against; the stop reason alone would not have caught it")
                .isEmpty();
        assertThat(result.stopReason()).isEqualTo(StopReason.AWAITING_APPROVAL);
    }

    @Test
    void theRunSaysWhichCallItIsWaitingOnAndWhy() {
        List<ToolInvocation> entered = new ArrayList<>();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "publish", Map.of("text", "the payload")),
                FakeLlmClient.text("unreached"));

        AgentResult result = agent(llm, List.of(publisher(entered))).run(Goal.of("publish"));

        assertThat(result.awaiting()).hasSize(1);
        assertThat(result.awaiting().get(0).invocationId()).isEqualTo("t1");
        assertThat(result.awaiting().get(0).toolName()).isEqualTo("publish");
        assertThat(result.awaiting().get(0).invocation().arguments())
                .as("a reviewer decides on the arguments, so they travel verbatim")
                .containsEntry("text", "the payload");
        assertThat(result.awaiting().get(0).why().effect()).isNotBlank();
    }

    @Test
    void aRunThatWasNotParkedIsWaitingOnNobody() {
        List<ToolInvocation> entered = new ArrayList<>();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "read", Map.of()),
                FakeLlmClient.text("done"));

        AgentResult result = agent(llm, List.of(reader(entered))).run(Goal.of("read"));

        assertThat(entered).hasSize(1);
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(result.awaiting()).isEmpty();
    }

    @Test
    void theRestOfTheTurnDoesNotRunOnTheWayOut() {
        // The reader is harmless and would have been allowed. It still does not run: the
        // run is ending, and starting side effects on the way out is the surprising
        // direction — and "harmless" is the gate's judgement to make, not this loop's.
        List<ToolInvocation> entered = new ArrayList<>();
        FakeLlmClient llm = new FakeLlmClient(
                twoCalls(),
                FakeLlmClient.text("unreached"));

        AgentResult result = agent(llm, List.of(publisher(entered), reader(entered)))
                .run(Goal.of("both"));

        assertThat(entered).isEmpty();
        assertThat(result.stopReason()).isEqualTo(StopReason.AWAITING_APPROVAL);
        assertThat(result.awaiting())
                .as("one question, not two — the second call was never put to anybody")
                .hasSize(1);
    }

    @Test
    void theSkippedCallIsToldItDidNotRunRatherThanLookingLikeItAnswered() {
        // This used to count observer callbacks and claim it proved the transcript was
        // appended before the run ended. executeTools notifies the observer once per
        // tool_use unconditionally, so the count held by construction whatever the loop
        // did — a tautology, and it hid a live mutant: moving the append after the return
        // left all 953 core tests green.
        //
        // What is actually observable is the content the skipped call came back with, and
        // it has to say the call did not happen. A result that merely looked ordinary would
        // have the model believe the read succeeded.
        List<ToolInvocation> entered = new ArrayList<>();
        List<ToolResult> results = new ArrayList<>();
        FakeLlmClient llm = new FakeLlmClient(twoCalls(), FakeLlmClient.text("unreached"));
        AgentObserver recording = new AgentObserver() {
            @Override
            public void onToolResult(AgentRun run, int step,
                                     ToolInvocation proposed, ToolInvocation invocation,
                                     ToolResult result,
                                     dev.agentkit.core.tool.Disposition disposition) {
                results.add(result);
            }
        };

        Agent.builder(llm, new SimpleToolRegistry(List.of(publisher(entered), reader(entered))),
                        CONFIG)
                .toolGate(ToolGates.parkForApproval(inv -> inv.name().equals("publish"),
                        ApprovalNeeded.because("needs a person")))
                .observer(recording)
                .build()
                .run(Goal.of("both"));

        assertThat(entered).isEmpty();
        assertThat(results).hasSize(2);
        assertThat(results.get(1).isError()).isTrue();
        assertThat(results.get(1).content()).contains("did not run");
    }

    // --- the result type's own invariant ------------------------------------------

    @Test
    void aRunCannotStopOnAQuestionItCannotName() {
        assertThatThrownBy(() -> AgentResult.awaitingApproval("out", 1, TokenUsage.ZERO, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("awaiting");
    }

    @Test
    void aQuestionCannotBeFiledUnderAnyOtherStopReason() {
        assertThatThrownBy(() -> new AgentResult(StopReason.COMPLETED, "out", 1, TokenUsage.ZERO,
                Optional.empty(),
                List.of(new dev.agentkit.core.reliability.PendingApproval(
                        new ToolInvocation("t1", "publish", Map.of()),
                        ApprovalNeeded.because("sign off")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("awaiting");
    }

    @Test
    void theFiveArgumentResultStillMeansWhatItMeant() {
        AgentResult old = new AgentResult(StopReason.COMPLETED, "out", 1, TokenUsage.ZERO,
                Optional.empty());

        assertThat(old.awaiting()).isEmpty();
    }

    @Test
    void aParkedResultSurvivesTheAgentsThatWrapALoopAndRunItTwice() {
        // Found by review, not by mutation: ReflectiveAgent and PlanningAgent rebuilt an
        // inner result as `error().isPresent() ? failed(...) : stopped(...)`, which
        // enumerates the outcomes by exclusion — so every stop reason that stopped()
        // refuses had to be remembered at both sites, and neither had reason to know about
        // a new one. Measured before the fix: a real Agent with a parking gate, run inside
        // a ReflectiveAgent, threw IllegalArgumentException. A crash, in a composed runner,
        // with no compile-time signal.
        List<ToolInvocation> entered = new ArrayList<>();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "publish", Map.of("text", "x")),
                FakeLlmClient.text("unreached"));
        AgentResult inner = agent(llm, List.of(publisher(entered))).run(Goal.of("publish"));

        AgentResult rebuilt = inner.withTotals(7, TokenUsage.ZERO);

        assertThat(rebuilt.stopReason()).isEqualTo(StopReason.AWAITING_APPROVAL);
        assertThat(rebuilt.awaiting())
                .as("rebuilding by parts rather than by case keeps the question attached")
                .hasSize(1);
        assertThat(rebuilt.steps()).isEqualTo(7);
    }

    @Test
    void theStoppedFactoryRefusesTheOutcomeItCannotDescribe() {
        // The guard that sends a caller to the right factory. A mutant deleting it survived
        // the whole core suite, because everything that could reach it had already been
        // moved onto withTotals.
        assertThatThrownBy(() -> AgentResult.stopped(StopReason.AWAITING_APPROVAL, "out", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("awaitingApproval");
    }

    @Test
    void theCallReportedAsPendingIsTheOneAnEarlierGateEdited() {
        // The in-process half of the composition fix, which the durable tests covered and
        // this one did not: a mutant reverting it to the proposed invocation survived all
        // 959 core tests. A composite may narrow the arguments and then park, and what a
        // caller shows a reviewer has to be the call that would actually run.
        List<ToolInvocation> entered = new ArrayList<>();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "publish", Map.of("text", "the payload")),
                FakeLlmClient.text("unreached"));

        AgentResult result = Agent.builder(llm,
                        new SimpleToolRegistry(List.of(publisher(entered))), CONFIG)
                .toolGate(ToolGates.allOf(
                        (tool, invocation) -> dev.agentkit.core.reliability.GateResult.allowWith(
                                new ToolInvocation(invocation.id(), invocation.name(),
                                        Map.of("text", "narrowed"))),
                        ToolGates.parkForApproval(inv -> true,
                                ApprovalNeeded.because("needs a person"))))
                .build()
                .run(Goal.of("publish"));

        assertThat(entered).isEmpty();
        assertThat(result.awaiting().get(0).invocation().arguments())
                .as("showing the proposal while the narrowed call is what would run is"
                        + " asking somebody to approve something else")
                .containsEntry("text", "narrowed");
    }
}
