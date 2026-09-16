package dev.agentkit.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class EvalHarnessTest {

    private static final AgentConfig CONFIG = AgentConfig.builder("m").maxSteps(4).build();

    private static Tool weatherTool() {
        return FunctionTool.builder("get_weather", "weather for a city")
                .handler(i -> ToolResult.ok("sunny")).build();
    }

    private static Function<AgentObserver, Agent> factory(ScriptedLlm llm, AtomicInteger builds) {
        return obs -> {
            builds.incrementAndGet();
            return Agent.builder(llm, new SimpleToolRegistry(List.of(weatherTool())), CONFIG)
                    .observer(obs).build();
        };
    }

    @Test
    void scoresACaseAcrossOutcomeTrajectoryAndOutput() {
        ScriptedLlm llm = new ScriptedLlm(
                ScriptedLlm.toolUse("t1", "get_weather", Map.of("city", "Seattle")),
                ScriptedLlm.text("It is sunny in Seattle"));
        EvalHarness harness = new EvalHarness(factory(llm, new AtomicInteger()));

        CaseReport report = harness.runCase(EvalCase.of("weather", Goal.of("weather in Seattle?"),
                Checks.completed(), Checks.usedTool("get_weather"), Checks.outputContains("Seattle")));

        assertThat(report.passed()).isTrue();
        assertThat(report.run().toolNames()).containsExactly("get_weather");
    }

    @Test
    void aFailedCheckFailsTheCaseAndIsReported() {
        ScriptedLlm llm = new ScriptedLlm(ScriptedLlm.text("done, no tools"));
        EvalHarness harness = new EvalHarness(factory(llm, new AtomicInteger()));

        CaseReport report = harness.runCase(EvalCase.of("c", Goal.of("g"),
                Checks.completed(), Checks.usedTool("get_weather")));

        assertThat(report.passed()).isFalse();
        assertThat(report.failures()).extracting(CheckOutcome::name).containsExactly("usedTool:get_weather");
    }

    @Test
    void aGateDeniedToolCountsAsAttemptedButNotUsed() {
        Tool danger = FunctionTool.builder("danger", "dangerous action")
                .handler(i -> ToolResult.ok("did it")).build();
        ScriptedLlm llm = new ScriptedLlm(
                ScriptedLlm.toolUse("t1", "danger", Map.of()), // requested, but will be blocked
                ScriptedLlm.text("stopped"));
        Function<AgentObserver, Agent> factory = obs -> Agent.builder(
                        llm, new SimpleToolRegistry(List.of(danger)), CONFIG)
                .observer(obs)
                .toolGate(ToolGates.denyTools(Set.of("danger")))
                .build();

        CaseReport report = new EvalHarness(factory).runCase(EvalCase.of("safety", Goal.of("try danger"),
                Checks.didNotUseTool("danger"), Checks.attemptedTool("danger")));

        // The gate blocked execution, so nothing dangerous ran, but the attempt is recorded.
        assertThat(report.passed()).isTrue();
        assertThat(report.run().succeededToolNames()).doesNotContain("danger");
        assertThat(report.run().toolNames()).containsExactly("danger");
        assertThat(Checks.usedTool("danger").check(report.run()).passed()).isFalse();
    }

    @Test
    void aTrajectoryScoresWhatTheModelAskedForNotWhatTheGateAllowed() {
        // The one place in the repository where the PROPOSAL is the right answer, and the
        // reason #131 deleted the three-argument callback instead of leaving a default to
        // choose for this class. An eval scores the model: "did it ask for the dangerous
        // thing" is the question, and a harness gate narrowing the arguments afterwards is
        // the harness protecting itself, not the model choosing better. Scoring the
        // effective call would mark a model that asked for /etc/shadow as having asked for
        // whatever policy substituted — silently, and in the flattering direction.
        //
        // ToolCall's javadoc says invocation is "what the model requested". This is what
        // keeps that sentence true.
        Tool reader = FunctionTool.builder("read_file", "reads a file")
                .handler(i -> ToolResult.ok("contents")).build();
        ScriptedLlm llm = new ScriptedLlm(
                ScriptedLlm.toolUse("t1", "read_file", Map.of("path", "/etc/shadow")),
                ScriptedLlm.text("done"));
        Function<AgentObserver, Agent> factory = obs -> Agent.builder(
                        llm, new SimpleToolRegistry(List.of(reader)), CONFIG)
                .observer(obs)
                .toolGate((tool, invocation) -> GateResult.allowWith(new ToolInvocation(
                        invocation.id(), invocation.name(),
                        Map.of("path", "/tmp/harmless.txt"))))
                .build();

        CaseReport report = new EvalHarness(factory)
                .runCase(EvalCase.of("scope", Goal.of("read something"), Checks.completed()));

        assertThat(report.run().toolCalls()).singleElement()
                .satisfies(call -> assertThat(call.invocation().arguments())
                        .as("the trajectory credited the model with the gate's narrowing,"
                                + " so a model that asked for /etc/shadow scored as if it"
                                + " had not")
                        .isEqualTo(Map.of("path", "/etc/shadow")));
    }

    @Test
    void aTrajectorySaysHowFarEachCallGotAndNotOnlyThatItFailed() {
        // The plumbing behind Checks.refused and Checks.parkedOn, pinned at the harness
        // rather than on a hand-built ToolCall. Three calls, three ways of not succeeding,
        // one isError() bit between them: a gate refusing, a gate parking, and a tool that
        // ran and reported a failure. The dispositions are the runner's own, so a harness
        // that dropped them -- which this one did -- fails here.
        Tool moody = FunctionTool.builder("moody", "returns an error when asked to")
                .handler(i -> ToolResult.error("the far side said no")).build();
        Tool danger = FunctionTool.builder("danger", "dangerous action")
                .handler(i -> ToolResult.ok("did it")).build();
        Tool sensitive = FunctionTool.builder("sensitive", "needs a person")
                .handler(i -> ToolResult.ok("did it")).build();
        ScriptedLlm llm = new ScriptedLlm(
                ScriptedLlm.toolUse("t1", "moody", Map.of()),
                ScriptedLlm.toolUse("t2", "danger", Map.of()),
                ScriptedLlm.toolUse("t3", "sensitive", Map.of()),
                ScriptedLlm.text("stopped"));
        Function<AgentObserver, Agent> factory = obs -> Agent.builder(
                        llm, new SimpleToolRegistry(List.of(moody, danger, sensitive)), CONFIG)
                .observer(obs)
                .toolGate(ToolGates.allOf(
                        ToolGates.denyTools(Set.of("danger")),
                        ToolGates.parkForApproval(i -> i.name().equals("sensitive"),
                                ApprovalNeeded.because("a person must decide"))))
                .build();

        CaseReport report = new EvalHarness(factory)
                .runCase(EvalCase.of("dispositions", Goal.of("try everything")));

        assertThat(report.run().toolCalls())
                .extracting(ToolCall::name, ToolCall::error, ToolCall::disposition)
                .containsExactly(
                        tuple("moody", true, Disposition.RAN),
                        tuple("danger", true, Disposition.REFUSED),
                        tuple("sensitive", true, Disposition.PARKED));
        assertThat(Checks.refused("danger").check(report.run()).passed()).isTrue();
        assertThat(Checks.refused("moody").check(report.run()).passed())
                .as("a tool that ran and reported a failure is not a refusal, and isError()"
                        + " cannot tell the two apart")
                .isFalse();
        assertThat(Checks.parkedOn("sensitive").check(report.run()).passed()).isTrue();
    }

    @Test
    void aThrowingCheckBecomesAFailedOutcomeNotAnAbortedRun() {
        ScriptedLlm llm = new ScriptedLlm(ScriptedLlm.text("done"));
        EvalHarness harness = new EvalHarness(factory(llm, new AtomicInteger()));
        Check boom = run -> {
            throw new RuntimeException("kaboom");
        };

        CaseReport report = harness.runCase(EvalCase.of("c", Goal.of("g"), Checks.completed(), boom));

        assertThat(report.passed()).isFalse();
        assertThat(report.failures()).singleElement()
                .satisfies(o -> assertThat(o.detail()).contains("kaboom"));
    }

    @Test
    void runAggregatesADatasetAndBuildsAFreshAgentPerCase() {
        ScriptedLlm llm = new ScriptedLlm(
                ScriptedLlm.text("Seattle is sunny"), // case 1 passes outputContains("Seattle")
                ScriptedLlm.text("no city here"));     // case 2 fails outputContains("Seattle")
        AtomicInteger builds = new AtomicInteger();
        EvalHarness harness = new EvalHarness(factory(llm, builds));

        EvalReport report = harness.run(List.of(
                EvalCase.of("c1", Goal.of("g1"), Checks.outputContains("Seattle")),
                EvalCase.of("c2", Goal.of("g2"), Checks.outputContains("Seattle"))));

        assertThat(report.total()).isEqualTo(2);
        assertThat(report.passedCount()).isEqualTo(1);
        assertThat(report.passRate()).isEqualTo(0.5);
        assertThat(report.failures()).extracting(CaseReport::caseId).containsExactly("c2");
        assertThat(builds).hasValue(2); // one fresh agent per case
        assertThat(report.summary()).contains("PASS c1").contains("FAIL c2").contains("1/2 passed");
    }
}
