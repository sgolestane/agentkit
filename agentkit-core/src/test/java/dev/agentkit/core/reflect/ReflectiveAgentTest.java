package dev.agentkit.core.reflect;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.verify.Verdict;
import dev.agentkit.core.verify.Verifier;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ReflectiveAgentTest {

    private static final AgentConfig CONFIG = AgentConfig.builder("m").maxSteps(3).build();

    private static Supplier<Agent> factory(LlmClient llm) {
        return () -> new Agent(llm, new SimpleToolRegistry(), CONFIG);
    }

    /** Records the feedback it was given, so tests can pin what was reflected. */
    private static final Reflector echoingReflector = (goal, result, feedback) -> "lesson: " + feedback;

    @Test
    void aVerifiedSuccessOnTheFirstAttemptRecordsNoLesson() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("great answer"));
        LessonBook lessons = new LessonBook(MemoryStore.inMemory());
        ReflectiveAgent agent = new ReflectiveAgent(
                factory(llm), Verifier.ALWAYS_PASS, echoingReflector, lessons, 3);

        AgentResult result = agent.run(Goal.of("do it"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("great answer");
        assertThat(lessons.recall()).isEmpty();
    }

    @Test
    void reflectsOnAFailedAttemptAndInjectsTheLessonIntoTheRetry() {
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.text("first try"), FakeLlmClient.text("second try"));
        Verifier verifier = (goal, output) -> output.equals("second try")
                ? Verdict.pass() : Verdict.fail("too vague");
        LessonBook lessons = new LessonBook(MemoryStore.inMemory());
        ReflectiveAgent agent = new ReflectiveAgent(factory(llm), verifier, echoingReflector, lessons, 3);

        AgentResult result = agent.run(Goal.of("write a summary"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("second try");
        assertThat(result.steps()).isEqualTo(2); // both attempts aggregated
        assertThat(lessons.recall()).containsExactly("lesson: too vague");
        // The second attempt's goal carried the reflected lesson.
        assertThat(llm.received().get(1).messages().get(0).text()).contains("lesson: too vague");
    }

    @Test
    void exhaustingAttemptsReturnsVerificationFailedAndPersistsTheLesson() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("a"), FakeLlmClient.text("b"));
        LessonBook lessons = new LessonBook(MemoryStore.inMemory());
        ReflectiveAgent agent = new ReflectiveAgent(
                factory(llm), (g, o) -> Verdict.fail("still wrong"), echoingReflector, lessons, 2);

        AgentResult result = agent.run(Goal.of("g"));

        assertThat(result.stopReason()).isEqualTo(StopReason.VERIFICATION_FAILED);
        assertThat(lessons.recall()).containsExactly("lesson: still wrong"); // deduped, persisted
    }

    @Test
    void aRunThatStopsEarlyIsReturnedAndStillTeachesALesson() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.refusal("I won't"));
        LessonBook lessons = new LessonBook(MemoryStore.inMemory());
        ReflectiveAgent agent = new ReflectiveAgent(
                factory(llm), Verifier.ALWAYS_PASS, echoingReflector, lessons, 3);

        AgentResult result = agent.run(Goal.of("do something disallowed"));

        assertThat(result.stopReason()).isEqualTo(StopReason.REFUSED);
        assertThat(lessons.recall()).singleElement().satisfies(l -> assertThat(l).contains("REFUSED"));
    }

    @Test
    void anEarlyStopOnALaterAttemptCarriesTheAggregatedTotals() {
        // Attempt 1 fails verification (reflects); attempt 2 refuses — the returned
        // result must carry BOTH attempts' steps, not just the last one's.
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.text("attempt 1"), FakeLlmClient.refusal("I won't"));
        LessonBook lessons = new LessonBook(MemoryStore.inMemory());
        ReflectiveAgent agent = new ReflectiveAgent(
                factory(llm), (g, o) -> Verdict.fail("no"), echoingReflector, lessons, 3);

        AgentResult result = agent.run(Goal.of("g"));

        assertThat(result.stopReason()).isEqualTo(StopReason.REFUSED);
        assertThat(result.steps()).isEqualTo(2); // both attempts aggregated, not just the refusal
    }

    @Test
    void aHardErrorIsReturnedWithoutReflecting() {
        LlmClient failing = request -> {
            throw new LlmException("provider down");
        };
        LessonBook lessons = new LessonBook(MemoryStore.inMemory());
        Reflector mustNotRun = (g, r, f) -> {
            throw new AssertionError("should not reflect on a hard ERROR");
        };
        ReflectiveAgent agent = new ReflectiveAgent(
                factory(failing), Verifier.ALWAYS_PASS, mustNotRun, lessons, 3);

        AgentResult result = agent.run(Goal.of("g"));

        assertThat(result.stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(result.error()).isPresent();
        assertThat(lessons.recall()).isEmpty(); // a crash yields no lesson
    }

    @Test
    void aReflectionFailureIsSwallowedAndDoesNotMaskTheResult() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.refusal("I won't"));
        LessonBook lessons = new LessonBook(MemoryStore.inMemory());
        Reflector throwing = (g, r, f) -> {
            throw new RuntimeException("reflector unreachable");
        };
        ReflectiveAgent agent = new ReflectiveAgent(
                factory(llm), Verifier.ALWAYS_PASS, throwing, lessons, 3);

        AgentResult result = agent.run(Goal.of("do something disallowed"));

        assertThat(result.stopReason()).isEqualTo(StopReason.REFUSED); // result preserved
        assertThat(lessons.recall()).isEmpty(); // reflection failed, nothing recorded
    }

    @Test
    void goalParametersSurviveLessonInjection() {
        MemoryStore store = MemoryStore.inMemory();
        new LessonBook(store).record("cite a source");
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("done"));
        ReflectiveAgent agent = new ReflectiveAgent(
                factory(llm), Verifier.ALWAYS_PASS, echoingReflector, new LessonBook(store), 3);

        agent.run(new Goal("answer the question", Map.of("ticket", "ABC-123")));

        String prompt = llm.received().get(0).messages().get(0).text();
        assertThat(prompt).contains("cite a source").contains("ABC-123"); // both lesson and params
    }

    @Test
    void lessonsFromAPriorRunAreInjectedIntoALaterRun() {
        MemoryStore store = MemoryStore.inMemory();
        new LessonBook(store).record("always cite a source"); // as if learned earlier
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("done"));
        ReflectiveAgent agent = new ReflectiveAgent(
                factory(llm), Verifier.ALWAYS_PASS, echoingReflector, new LessonBook(store), 3);

        agent.run(Goal.of("answer the question"));

        assertThat(llm.received().get(0).messages().get(0).text()).contains("always cite a source");
    }
}
