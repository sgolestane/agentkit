package dev.agentkit.core.verify;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * {@code SelfVerifyingAgent.run} reports the whole run's steps and tokens, not the last
 * attempt's (#222).
 *
 * <h2>The measurement</h2>
 *
 * <p>A real {@link Agent} that answers in two turns and fails verification, then a second
 * attempt that parks on a gate after one turn:
 *
 * <pre>
 * attempts run : 2
 * reported     : stopReason=AWAITING_APPROVAL steps=1 usage=input 1 / output 1
 * actually ran :                              steps=3 usage=input 81 / output 21
 * </pre>
 *
 * <p>Attempt 1's work is real — it made model calls and ran tools — and none of it appeared
 * in what the caller was handed. Two of the three exits from {@code run} were already
 * aggregating and the class javadoc had always promised aggregation; the early return was
 * the one that disagreed.
 *
 * <h2>Why it is not tidiness</h2>
 *
 * <p>Budgets are enforced against the returned number, and the under-report grows with the
 * number of attempts — the direction that makes a runaway look cheap. {@code TokenUsage} is
 * also what a deployment bills and rate-limits against.
 */
class SelfVerifyingAgentTotalsTest {

    private static SimpleToolRegistry tools() {
        return new SimpleToolRegistry().register(
                FunctionTool.builder("work", "does the work")
                        .handler(invocation -> ToolResult.ok("did it"))
                        .build());
    }

    private static AgentConfig config() {
        return AgentConfig.builder("m").maxSteps(5).build();
    }

    /** Attempt 1: two turns, 40+10 tokens each, ending in an answer verification rejects. */
    private static Agent answersInTwoTurns(SimpleToolRegistry tools) {
        return new Agent(new FakeLlmClient(
                FakeLlmClient.toolUseWithUsage("t1", "work", Map.of(), new TokenUsage(40, 10)),
                FakeLlmClient.textWithUsage("first answer", new TokenUsage(40, 10))),
                tools, config());
    }

    /** Attempt 2: one turn, 1+1 tokens, parked by a gate before the tool runs. */
    private static Agent parksAfterOneTurn(SimpleToolRegistry tools) {
        return Agent.builder(new FakeLlmClient(
                FakeLlmClient.toolUseWithUsage("t2", "work", Map.of(), new TokenUsage(1, 1))),
                tools, config())
                .toolGate((tool, invocation) ->
                        GateResult.needsAPerson(ApprovalNeeded.because("a person must decide")))
                .build();
    }

    private static Supplier<Agent> twoAttempts(SimpleToolRegistry tools, AtomicInteger built) {
        return () -> built.incrementAndGet() == 1
                ? answersInTwoTurns(tools) : parksAfterOneTurn(tools);
    }

    @Test
    void aParkedSecondAttemptStillReportsTheFirstAttemptsWork() {
        AtomicInteger built = new AtomicInteger();
        SimpleToolRegistry tools = tools();

        AgentResult result = new SelfVerifyingAgent(twoAttempts(tools, built),
                (goal, output) -> Verdict.fail("not good enough"), 2)
                .run(Goal.of("do it"));

        assertThat(built.get()).as("both attempts must actually have run").isEqualTo(2);
        assertThat(result.steps())
                .as("the steps of every attempt before the last were dropped, so a caller"
                        + " capping a run by steps decides against an under-report")
                .isEqualTo(3);
        assertThat(result.usage())
                .as("the tokens of every attempt before the last were dropped, so a"
                        + " deployment bills and rate-limits against an under-report")
                .isEqualTo(new TokenUsage(81, 21));
    }

    @Test
    void theParkTravelsUnaltered() {
        // The reason the repair is withTotals and not a rebuild by case. AgentResult.stopped
        // refuses to build an AWAITING_APPROVAL result at all, so enumerating the outcomes
        // here — failed for one, stopped for the rest — crashes on exactly the outcome #159
        // made first-class at this level. It is what broke ReflectiveAgent and PlanningAgent
        // when AWAITING_APPROVAL was added (#101).
        AtomicInteger built = new AtomicInteger();
        SimpleToolRegistry tools = tools();

        AgentResult result = new SelfVerifyingAgent(twoAttempts(tools, built),
                (goal, output) -> Verdict.fail("not good enough"), 2)
                .run(Goal.of("do it"));

        assertThat(result.stopReason()).isEqualTo(StopReason.AWAITING_APPROVAL);
        assertThat(result.isAwaitingApproval())
                .as("a composing runner asking the one spelling of the question got 'no'")
                .isTrue();
        assertThat(result.awaiting())
                .as("the question a person still owes an answer on was lost in the rebuild")
                .hasSize(1);
        assertThat(result.awaiting().get(0).why().reason())
                .isEqualTo("a person must decide");
    }

    @Test
    void anErroredAttemptCarriesItsCauseAndTheRunsTotals() {
        // The other early-return outcome, and the one whose invariant a careless rebuild
        // trips: AgentResult refuses an ERROR without an error and an error without ERROR.
        AtomicInteger built = new AtomicInteger();
        SimpleToolRegistry tools = tools();
        Supplier<Agent> factory = () -> built.incrementAndGet() == 1
                ? answersInTwoTurns(tools)
                // One turn of its own, then a model call that throws because the script is
                // spent — an ERROR result after real, paid-for work.
                : new Agent(new FakeLlmClient(
                        FakeLlmClient.toolUseWithUsage("t2", "work", Map.of(),
                                new TokenUsage(1, 1))),
                        tools, config());

        AgentResult result = new SelfVerifyingAgent(factory,
                (goal, output) -> Verdict.fail("not good enough"), 2).run(Goal.of("do it"));

        assertThat(built.get()).isEqualTo(2);
        assertThat(result.stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(result.error())
                .as("an ERROR result must name its error, and a rebuild that dropped it"
                        + " would fail AgentResult's own biconditional")
                .isPresent();
        assertThat(result.steps()).isEqualTo(3);
        assertThat(result.usage()).isEqualTo(new TokenUsage(81, 21));
    }

    @Test
    void aSingleFailingAttemptIsUnchanged() {
        // The regression guard for the repair itself: with one attempt the totals are that
        // attempt's, so withTotals must be a no-op rather than a second addition. A mutant
        // that adds the attempt in twice passes the parked test above by accident if the
        // arithmetic is wrong in both places; it does not pass this one.
        SimpleToolRegistry tools = tools();

        AgentResult result = new SelfVerifyingAgent(() -> parksAfterOneTurn(tools),
                (goal, output) -> Verdict.fail("not good enough"), 1)
                .run(Goal.of("do it"));

        assertThat(result.stopReason()).isEqualTo(StopReason.AWAITING_APPROVAL);
        assertThat(result.steps()).isEqualTo(1);
        assertThat(result.usage()).isEqualTo(new TokenUsage(1, 1));
    }

    @Test
    void theOutputOfTheStoppedAttemptIsKept() {
        // withTotals rebuilds by parts, so everything that is not a total travels unaltered.
        // A rebuild by case would have had to remember to carry this and the previous test's
        // components by hand.
        SimpleToolRegistry tools = tools();
        Supplier<Agent> factory = () -> new Agent(new FakeLlmClient(
                FakeLlmClient.refusal("I will not do that")), tools, config());

        AgentResult result = new SelfVerifyingAgent(factory,
                (goal, output) -> Verdict.fail("unused"), 3).run(Goal.of("do it"));

        assertThat(result.stopReason()).isEqualTo(StopReason.REFUSED);
        assertThat(result.output()).isEqualTo("I will not do that");
        assertThat(result.awaiting()).isEqualTo(List.of());
    }
}
