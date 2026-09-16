package dev.agentkit.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * An observer can tell a call that never ran from one that ran and failed (#181).
 *
 * <h2>The defect, measured</h2>
 *
 * <p>{@code onToolResult} fires for every proposed call and carried four values. Printing
 * all of them, for the seven ways a proposed call can end:
 *
 * <pre>
 * 1 unknown tool          isError=true  proposed==effective  provenance=FIRST_PARTY
 * 2 gate denied           isError=true  proposed==effective  provenance=FIRST_PARTY
 * 3 gate parked           isError=true  proposed==effective  provenance=FIRST_PARTY
 * 4 sibling parked        isError=true  proposed==effective  provenance=FIRST_PARTY
 * 5 gate threw            isError=true  proposed==effective  provenance=UNKNOWN
 * 6 tool returned error   isError=true  proposed==effective  provenance=UNKNOWN
 * 7 tool threw            isError=true  proposed==effective  provenance=UNKNOWN
 * </pre>
 *
 * <p>One bit for seven states, and the four never-ran states sit on the same side of it as
 * the two that may have landed a side effect. {@code agentkit-examples-itops}'s
 * {@code AuditObserver} derived its whole verdict from that bit, so a parked call and a
 * tool that blew up mid-write were the same audit row.
 *
 * <p><strong>{@code provenance} is not a proxy</strong>, which is why the table above is
 * printed in full rather than summarised. It does split the seven, along the wrong line: a
 * gate that threw (nothing ran) shares a class with a tool that ran and threw, and a
 * terminal denial shares one with a call waiting on a person. {@code content} is not a
 * proxy either — it is a gate author's reason and a tool author's message, neither held by
 * any contract.
 *
 * <h2>What is asserted here</h2>
 *
 * <p>One test per state, each pinning the disposition <em>and</em> the independently
 * observed fact it claims: whether the tool was actually entered. Asserting the enum alone
 * would pass for a runner that labelled everything {@code RAN} and ran nothing, which is
 * the shape this repository has shipped before — so every case compares
 * {@link Disposition#reachedTool()} against a list the tool itself appends to.
 */
class ObserverCanTellWhatRanTest {

    /** Records every invocation it is actually entered with, then does what it was told. */
    private static Tool tool(List<ToolInvocation> entered,
                            Function<ToolInvocation, ToolResult> body) {
        return FunctionTool.builder("act", "does a thing")
                .schema(Map.of("type", "object"))
                .handler(invocation -> {
                    entered.add(invocation);
                    return body.apply(invocation);
                })
                .build();
    }

    /** What the observer was told about each call, in order. */
    private static final class Seen implements AgentObserver {
        private final List<Disposition> dispositions = new ArrayList<>();
        private final List<Boolean> errors = new ArrayList<>();

        @Override
        public void onToolResult(AgentRun run, int step,
                                 ToolInvocation proposed, ToolInvocation effective,
                                 ToolResult result, Disposition disposition) {
            dispositions.add(disposition);
            errors.add(result.isError());
        }
    }

    private static final LlmResponse ONE_CALL =
            FakeLlmClient.toolUse("c1", "act", Map.of("k", "one"));

    /** A turn asking for two calls, so a park on the first can strand the second. */
    private static final LlmResponse TWO_CALLS = LlmResponse.of(
            Message.of(Role.ASSISTANT, List.<ContentBlock>of(
                    ProposedCall.of("c1", "act", Map.of("k", "one")),
                    ProposedCall.of("c2", "act", Map.of("k", "two")))),
            LlmStopReason.TOOL_USE, TokenUsage.ZERO);

    private static Seen run(LlmResponse turn, ToolGate gate, Tool registered) {
        Seen seen = new Seen();
        SimpleToolRegistry registry = new SimpleToolRegistry();
        if (registered != null) {
            registry.register(registered);
        }
        Agent.builder(new FakeLlmClient(turn, FakeLlmClient.text("done")), registry,
                        AgentConfig.builder("m").maxSteps(3).build())
                .toolGate(gate)
                .observer(seen)
                .build()
                .run(Goal.of("do the thing"));
        return seen;
    }

    private static final ToolGate ALLOW = (tool, invocation) -> GateResult.allow();

    // --- the five that never reached a tool --------------------------------------

    @Test
    void anUnknownToolIsNotARefusal() {
        // Distinct from REFUSED on purpose: no policy decided anything here, the name was
        // simply wrong. Recording it as a denial would put a governance decision in an
        // audit trail that nobody made.
        Seen seen = run(FakeLlmClient.toolUse("c1", "nosuch", Map.of()), ALLOW,
                tool(new ArrayList<>(), inv -> ToolResult.ok("done")));

        assertThat(seen.dispositions).containsExactly(Disposition.UNKNOWN_TOOL);
        assertThat(seen.dispositions.get(0).reachedTool()).isFalse();
        assertThat(seen.errors).containsExactly(true);
    }

    @Test
    void aDeniedCallIsRefusedAndDidNotRun() {
        List<ToolInvocation> entered = new ArrayList<>();

        Seen seen = run(ONE_CALL, (tool, invocation) -> GateResult.deny("policy says no"),
                tool(entered, inv -> ToolResult.ok("done")));

        assertThat(entered).as("a denied call must not run").isEmpty();
        assertThat(seen.dispositions).containsExactly(Disposition.REFUSED);
        assertThat(seen.dispositions.get(0).reachedTool()).isFalse();
    }

    @Test
    void aParkedCallIsNotARefusalBecauseItIsNotOver() {
        // The pair the issue was opened about, half of it: REFUSED is terminal, PARKED is
        // "somebody still owes an answer". Both arrive as an error result and before this
        // they were the same row.
        List<ToolInvocation> entered = new ArrayList<>();

        Seen seen = run(ONE_CALL, (tool, invocation) -> GateResult.needsAPerson(
                        ApprovalNeeded.because("a person must look"), invocation),
                tool(entered, inv -> ToolResult.ok("done")));

        assertThat(entered).as("a parked call must not run").isEmpty();
        assertThat(seen.dispositions).containsExactly(Disposition.PARKED);
        assertThat(seen.dispositions.get(0).reachedTool()).isFalse();
    }

    @Test
    void aSiblingStrandedByAParkWasNeverOfferedToAGate() {
        // Two calls in one turn: the first parks, so the run is ending and the second is
        // not started on the way out. Nothing decided anything about the second — which is
        // why it is NOT_ATTEMPTED and not REFUSED.
        List<ToolInvocation> entered = new ArrayList<>();

        Seen seen = run(TWO_CALLS, (tool, invocation) -> GateResult.needsAPerson(
                        ApprovalNeeded.because("a person must look"), invocation),
                tool(entered, inv -> ToolResult.ok("done")));

        assertThat(entered).isEmpty();
        assertThat(seen.dispositions)
                .as("the stranded sibling was reported as the same state as the park that"
                        + " stranded it")
                .containsExactly(Disposition.PARKED, Disposition.NOT_ATTEMPTED);
        assertThat(seen.errors).containsExactly(true, true);
    }

    @Test
    void aGateThatThrewRanNothing() {
        // The state most easily confused with a tool that threw: both end in the
        // framework's "Tool 'act' failed." with the thrower's message fenced inside, so
        // from the outside they were one row. One is a broken policy; the other may have
        // landed half a side effect.
        List<ToolInvocation> entered = new ArrayList<>();

        Seen seen = run(ONE_CALL, (tool, invocation) -> {
            throw new IllegalStateException("the policy service is down");
        }, tool(entered, inv -> ToolResult.ok("done")));

        assertThat(entered).isEmpty();
        assertThat(seen.dispositions).containsExactly(Disposition.GATE_FAILED);
        assertThat(seen.dispositions.get(0).reachedTool()).isFalse();
    }

    @Test
    void aReplacementTheGateWasNotAllowedToMakeIsAGateFailure() {
        // effectiveFor throws when a replacement renames the tool (#104) — a gate author's
        // mistake at an authorization boundary. It throws from the statement that assigns
        // `effective`, one line before the marker that says the tool was entered, so the
        // catch reports a broken gate and not a tool that may have acted. The durable
        // runner asserts the same thing, in DurableDispositionTest.
        List<ToolInvocation> entered = new ArrayList<>();

        Seen seen = run(ONE_CALL, (tool, invocation) -> GateResult.allowWith(
                        new ToolInvocation(invocation.id(), "something_else",
                                invocation.arguments())),
                tool(entered, inv -> ToolResult.ok("done")));

        assertThat(entered).isEmpty();
        assertThat(seen.dispositions).containsExactly(Disposition.GATE_FAILED);
    }

    // --- the two that did reach a tool -------------------------------------------

    @Test
    void aToolThatReturnedAnErrorStillRan() {
        // The state isError() was being asked to distinguish from the five above and
        // cannot. It ran, it decided, and it may have changed something before deciding.
        List<ToolInvocation> entered = new ArrayList<>();

        Seen seen = run(ONE_CALL, ALLOW, tool(entered, inv -> ToolResult.error("disk full")));

        assertThat(entered).hasSize(1);
        assertThat(seen.dispositions).containsExactly(Disposition.RAN);
        assertThat(seen.dispositions.get(0).reachedTool()).isTrue();
        assertThat(seen.errors)
                .as("the whole point: an error result from a tool that ran")
                .containsExactly(true);
    }

    @Test
    void aToolThatThrewIsNotAToolThatReturnedAnError() {
        // Kept apart from RAN because a reviewer can conclude different things: a tool that
        // returned an error chose to report one; a tool that threw part-way may have
        // completed half its work and said nothing about which half.
        List<ToolInvocation> entered = new ArrayList<>();

        Seen seen = run(ONE_CALL, ALLOW, tool(entered, inv -> {
            throw new IllegalStateException("the remote end hung up");
        }));

        assertThat(entered).as("the tool did not run, so this test measured nothing")
                .hasSize(1);
        assertThat(seen.dispositions).containsExactly(Disposition.THREW);
        assertThat(seen.dispositions.get(0).reachedTool()).isTrue();
    }

    @Test
    void aSucceedingCallRan() {
        // The positive control. A runner that answered THREW or GATE_FAILED for everything
        // would pass six of the tests above.
        List<ToolInvocation> entered = new ArrayList<>();

        Seen seen = run(ONE_CALL, ALLOW, tool(entered, inv -> ToolResult.ok("done")));

        assertThat(entered).hasSize(1);
        assertThat(seen.dispositions).containsExactly(Disposition.RAN);
        assertThat(seen.errors).containsExactly(false);
    }

    // --- the whole point ---------------------------------------------------------

    @Test
    void theSevenStatesAreSevenValues() {
        // The assertion the issue asks for, stated as one thing rather than inferred from
        // the file: run every path and count the distinct answers. Before this there was
        // one — isError() — and it was true on all seven.
        List<ToolInvocation> ignored = new ArrayList<>();
        List<Disposition> all = new ArrayList<>();
        all.addAll(run(FakeLlmClient.toolUse("c1", "nosuch", Map.of()), ALLOW,
                tool(ignored, inv -> ToolResult.ok("x"))).dispositions);
        all.addAll(run(ONE_CALL, (t, i) -> GateResult.deny("no"),
                tool(ignored, inv -> ToolResult.ok("x"))).dispositions);
        all.addAll(run(TWO_CALLS, (t, i) -> GateResult.needsAPerson(
                        ApprovalNeeded.because("look"), i),
                tool(ignored, inv -> ToolResult.ok("x"))).dispositions);
        all.addAll(run(ONE_CALL, (t, i) -> {
            throw new IllegalStateException("down");
        }, tool(ignored, inv -> ToolResult.ok("x"))).dispositions);
        all.addAll(run(ONE_CALL, ALLOW, tool(ignored, inv -> ToolResult.error("e"))).dispositions);
        all.addAll(run(ONE_CALL, ALLOW, tool(ignored, inv -> {
            throw new IllegalStateException("boom");
        })).dispositions);

        assertThat(all)
                .as("the seven states an observer could not tell apart")
                .containsExactly(Disposition.UNKNOWN_TOOL, Disposition.REFUSED,
                        Disposition.PARKED, Disposition.NOT_ATTEMPTED,
                        Disposition.GATE_FAILED, Disposition.RAN, Disposition.THREW);
        assertThat(all.stream().filter(Disposition::reachedTool).toList())
                .as("exactly two of the seven may have landed a side effect")
                .containsExactly(Disposition.RAN, Disposition.THREW);
    }

    @Test
    void aTurnThatReusedACallIdAttemptedNothing() {
        // The loop's own refusal path, which also fires onToolResult for every call in the
        // turn so a trajectory does not silently show none. No gate saw any of them.
        List<ToolInvocation> entered = new ArrayList<>();
        LlmResponse duplicated = LlmResponse.of(
                Message.of(Role.ASSISTANT, List.<ContentBlock>of(
                        ProposedCall.of("c1", "act", Map.of("k", "one")),
                        ProposedCall.of("c1", "act", Map.of("k", "two")))),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO);

        Seen seen = run(duplicated, ALLOW, tool(entered, inv -> ToolResult.ok("done")));

        assertThat(entered).isEmpty();
        assertThat(seen.dispositions)
                .containsExactly(Disposition.NOT_ATTEMPTED, Disposition.NOT_ATTEMPTED);
    }
}
