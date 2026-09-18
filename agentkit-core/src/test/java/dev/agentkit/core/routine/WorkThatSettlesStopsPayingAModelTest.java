package dev.agentkit.core.routine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The loop this package exists for: a job the model has worked out the same way three times is done a fourth time
 * without it. The work still happens — the same tools, the same order, the same gate — and only the deliberation is
 * gone.
 */
class WorkThatSettlesStopsPayingAModelTest {

    private static final String KIND = "Give a new hire their accounts";

    private final List<String> calls = new ArrayList<>();
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicReference<ToolGate> gate = new AtomicReference<>(ToolGate.ALLOW_ALL);
    private final RoutineBook book = new RoutineBook();

    private Tool tool(String name) {
        return FunctionTool.builder(name, name)
                .schema(Map.of("type", "object", "properties", Map.of("email", Map.of("type", "string"))))
                .handler(invocation -> {
                    calls.add(name + "(" + invocation.stringArgument("email") + ")");
                    return ToolResult.ok(name + " done for " + invocation.stringArgument("email"));
                })
                .build();
    }

    private ToolRegistry tools() {
        return new SimpleToolRegistry(List.of(tool("create_account"), tool("add_to_group"), tool("send_welcome")));
    }

    /**
     * The model working the job out: three tool calls and a sentence. One per run, because a model call's answer
     * depends on how far the run has got, and counted so that a replay can be told from deliberation.
     */
    private final class Deliberating implements LlmClient {

        private final boolean skipWelcome;
        private int calls;

        Deliberating(boolean skipWelcome) {
            this.skipWelcome = skipWelcome;
        }

        @Override
        public LlmResponse generate(LlmRequest request) {
            modelCalls.incrementAndGet();
            String email = emailIn(request);
            return switch (++calls) {
                case 1 -> FakeLlmClient.toolUse("1", "create_account", Map.of("email", email));
                case 2 -> FakeLlmClient.toolUse("2", "add_to_group", Map.of("email", email, "group", "all-staff"));
                case 3 -> skipWelcome ? FakeLlmClient.text("Account only for " + email + ".")
                        : FakeLlmClient.toolUse("3", "send_welcome", Map.of("email", email));
                default -> FakeLlmClient.text("Set " + email + " up.");
            };
        }
    }

    private static String emailIn(LlmRequest request) {
        String text = request.messages().toString();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[\\w.]+@[\\w.]+").matcher(text);
        return matcher.find() ? matcher.group() : "unknown@acme.example";
    }

    private RoutineAgent agent() {
        return agent(() -> new Deliberating(false));
    }

    private RoutineAgent agent(java.util.function.Supplier<LlmClient> llm) {
        return agent(llm, 1_000);
    }

    private RoutineAgent agent(java.util.function.Supplier<LlmClient> llm, int recheckEvery) {
        return new RoutineAgent(
                observer -> Agent.builder(llm.get(), tools(), AgentConfig.builder("m").maxSteps(8).build())
                        .observer(observer).toolGate(gate.get()).build(),
                book, TaskShape.ofGoalParameters(), this::tools, gate.get(), RoutineAgent.STEPS_TAKEN, recheckEvery);
    }

    private static Goal hire(String email) {
        return new Goal(KIND, Map.of("work_email", email));
    }

    @Test
    void theFourthRunDoesTheSameWorkWithNoModelCall() {
        RoutineAgent agent = agent();

        agent.run(hire("ana@acme.example"));
        agent.run(hire("ben@acme.example"));
        agent.run(hire("cara@acme.example"));
        int deliberated = modelCalls.get();
        calls.clear();

        AgentResult fourth = agent.run(hire("dev@acme.example"));

        assertThat(modelCalls.get()).as("no model call for a settled job").isEqualTo(deliberated);
        assertThat(calls).containsExactly("create_account(dev@acme.example)", "add_to_group(dev@acme.example)",
                "send_welcome(dev@acme.example)");
        assertThat(fourth.isSuccess()).isTrue();
        assertThat(fourth.steps()).isZero();
        assertThat(fourth.output()).contains("create_account").contains("without a model");
        assertThat(agent.routineFor(hire("eve@acme.example"))).hasValueSatisfying(routine ->
                assertThat(routine.describe()).isEqualTo(
                        KIND + ": create_account → add_to_group → send_welcome (seen 3 times)"));
    }

    @Test
    void theValuesAreTheOnlyThingThatChangesBetweenRuns() {
        RoutineAgent agent = agent();
        agent.run(hire("ana@acme.example"));
        agent.run(hire("ben@acme.example"));
        agent.run(hire("cara@acme.example"));

        Routine routine = agent.routineFor(hire("dev@acme.example")).orElseThrow();

        // The hire's email became a placeholder; the group name, which came from the model rather than the task,
        // stayed exactly as it was recorded.
        assertThat(routine.parameters()).containsExactly("work_email");
        assertThat(routine.steps().get(1).arguments())
                .containsEntry("email", "{work_email}")
                .containsEntry("group", "all-staff");
    }

    @Test
    void arunThatDoesSomethingElseUnsettlesTheJob() {
        RoutineAgent agent = agent();
        agent.run(hire("ana@acme.example"));
        agent.run(hire("ben@acme.example"));
        agent.run(hire("cara@acme.example"));
        assertThat(agent.routineFor(hire("dev@acme.example"))).isPresent();

        // The recheck run goes to the model even though the job is settled, and this time the model skips the
        // welcome message: the usual way is no longer the usual way.
        RoutineAgent odd = agent(() -> new Deliberating(true), 1);
        odd.run(hire("dev@acme.example"));

        assertThat(odd.routineFor(hire("eve@acme.example"))).isEmpty();
        assertThat(calls).endsWith("add_to_group(dev@acme.example)");
    }

    @Test
    void oneRunInEveryFewGoesToTheModelSoASettledJobCanBeFoundWrong() {
        RoutineAgent agent = agent(() -> new Deliberating(false), 4);
        agent.run(hire("ana@acme.example"));
        agent.run(hire("ben@acme.example"));
        agent.run(hire("cara@acme.example"));
        int deliberated = modelCalls.get();

        agent.run(hire("dev@acme.example"));      // the fourth run of this kind: a recheck
        int afterRecheck = modelCalls.get();
        agent.run(hire("eve@acme.example"));      // replayed
        agent.run(hire("fin@acme.example"));      // replayed

        assertThat(afterRecheck).as("the recheck asked the model").isGreaterThan(deliberated);
        assertThat(modelCalls.get()).as("the runs after it did not").isEqualTo(afterRecheck);
    }

    @Test
    void aRunThatNeverFinishesTeachesNothing() {
        // maxSteps of 1 stops every run short, so none of them ever succeeds.
        RoutineAgent capped = new RoutineAgent(
                observer -> Agent.builder(new Deliberating(false), tools(), AgentConfig.builder("m").maxSteps(1).build())
                        .observer(observer).build(),
                book, TaskShape.ofGoalParameters(), this::tools, ToolGate.ALLOW_ALL);

        capped.run(hire("ana@acme.example"));
        capped.run(hire("ben@acme.example"));
        capped.run(hire("cara@acme.example"));

        assertThat(capped.routineFor(hire("dev@acme.example"))).isEmpty();
        assertThat(book.settled()).isEmpty();
    }

    @Test
    void aReplayIsHeldToTheSameGateAsAModelAndHandsBackWhatItAlreadyDid() {
        RoutineAgent agent = agent();
        agent.run(hire("ana@acme.example"));
        agent.run(hire("ben@acme.example"));
        agent.run(hire("cara@acme.example"));
        Routine routine = agent.routineFor(hire("dev@acme.example")).orElseThrow();

        ToolGate noWelcome = (tool, invocation) -> "send_welcome".equals(invocation.name())
                ? GateResult.deny("welcome messages are paused") : GateResult.allow();
        calls.clear();

        Replay replay = Routines.replay(routine, new Task(KIND, Map.of("work_email", "dev@acme.example")),
                tools(), noWelcome);

        assertThat(calls).containsExactly("create_account(dev@acme.example)", "add_to_group(dev@acme.example)");
        assertThat(replay.finished()).isFalse();
        assertThat(replay.stoppedAt()).isEqualTo(2);
        assertThat(replay.reason()).isEqualTo("welcome messages are paused");
        assertThat(replay.changedAnything()).isTrue();
        assertThat(replay.describe()).contains("create_account: create_account done for dev@acme.example")
                .contains("Stopped before step 3 of 3 (send_welcome): welcome messages are paused");
    }

    @Test
    void whenAReplayStopsTheModelIsToldWhatAlreadyHappenedInsteadOfStartingOver() {
        RoutineAgent agent = agent();
        agent.run(hire("ana@acme.example"));
        agent.run(hire("ben@acme.example"));
        agent.run(hire("cara@acme.example"));

        // The welcome step now fails, so the replay stops there and the model picks the job up.
        List<Goal> goalsSeen = new ArrayList<>();
        ToolRegistry breaking = new SimpleToolRegistry(List.of(tool("create_account"), tool("add_to_group"),
                FunctionTool.builder("send_welcome", "send_welcome")
                        .handler(invocation -> ToolResult.error("mail server is down")).build()));
        LlmClient watching = request -> {
            modelCalls.incrementAndGet();
            goalsSeen.add(Goal.of(request.messages().getFirst().content().toString()));
            return FakeLlmClient.text("Picked it up.");
        };
        RoutineAgent continuing = new RoutineAgent(
                observer -> Agent.builder(watching, breaking, AgentConfig.builder("m").maxSteps(4).build())
                        .observer(observer).build(),
                book, TaskShape.ofGoalParameters(), () -> breaking, ToolGate.ALLOW_ALL);
        calls.clear();

        AgentResult result = continuing.run(hire("dev@acme.example"));

        assertThat(calls).containsExactly("create_account(dev@acme.example)", "add_to_group(dev@acme.example)");
        assertThat(result.isSuccess()).isTrue();
        String goalGiven = goalsSeen.getFirst().description();
        assertThat(goalGiven).contains("already been done").contains("do not do them again")
                .contains("kind=\"evidence\"").contains("create_account done for dev@acme.example")
                .contains("mail server is down");
    }

    @Test
    void anUnrecognisedGoalIsJustRun() {
        AtomicInteger built = new AtomicInteger();
        RoutineAgent agent = new RoutineAgent(
                observer -> {
                    built.incrementAndGet();
                    assertThat(observer).isSameAs(AgentObserver.NONE);
                    return Agent.builder(new Deliberating(false), tools(), AgentConfig.builder("m").maxSteps(8).build())
                            .observer(observer).build();
                },
                book, goal -> java.util.Optional.empty(), this::tools, ToolGate.ALLOW_ALL);

        AgentResult result = agent.run(Goal.of("something nobody has a shape for"));

        assertThat(built.get()).isEqualTo(1);
        assertThat(result.isSuccess()).isTrue();
        assertThat(book.settled()).isEmpty();
    }

    @Test
    void anAnswerCanBeWrittenByTheDeploymentInsteadOfListingSteps() {
        RoutineAgent agent = new RoutineAgent(
                observer -> Agent.builder(new Deliberating(false), tools(), AgentConfig.builder("m").maxSteps(8).build())
                        .observer(observer).build(),
                book, TaskShape.ofGoalParameters(), this::tools, ToolGate.ALLOW_ALL,
                (task, replay) -> task.parameters().get("work_email") + " is set up.");
        agent.run(hire("ana@acme.example"));
        agent.run(hire("ben@acme.example"));
        agent.run(hire("cara@acme.example"));

        AgentResult fourth = agent.run(hire("dev@acme.example"));

        assertThat(fourth.output()).isEqualTo("dev@acme.example is set up.");
    }
}
