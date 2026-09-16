package dev.agentkit.core.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.Frozen;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An argument one level past {@link Frozen#MAX_DEPTH} is refused where the model can read
 * the refusal (#246).
 *
 * <p>The cliff itself is the control arm here and stays. What changed is the row underneath
 * it — the run no longer dies, and the model is handed a sentence it can act on:
 *
 * <pre>
 *  99 levels  before and after -&gt;  gate consulted, denied, the run finished normally
 * 101 levels  before           -&gt;  no gate, no tool, run ended ERROR, model told nothing
 * 101 levels  after            -&gt;  no gate, no tool, run finished normally, model told why
 * </pre>
 *
 * <p>"Told why" is asserted against the request the client is handed on the <em>next</em>
 * turn rather than against anything this test computed, because that is the only place the
 * claim is actually true or false: a refusal a runner produces and never sends is the
 * defect this issue is about, one layer in.
 */
class ToolUseBlockUnusableArgumentsTest {

    /** A map nested {@code levels} deep — the shape a provider can send verbatim. */
    private static Map<String, Object> nested(int levels) {
        Map<String, Object> at = new LinkedHashMap<>();
        at.put("v", "x");
        for (int i = 1; i < levels; i++) {
            Map<String, Object> up = new LinkedHashMap<>();
            up.put("n", at);
            at = up;
        }
        return at;
    }

    /**
     * A client that builds the block inside the call, the way a real one parses a turn —
     * through {@link ProposedCall#of}, which is what {@code AnthropicLlmClient} and
     * {@code OpenRouterLlmClient} both call at their parse sites.
     *
     * <p>It answers a second time with plain text, so a run that survives the refusal has
     * somewhere to go; before this change nothing reached the second call. Every request it
     * is handed is kept, which is how the assertion about what the model saw is made.
     */
    private static final class Parsing implements LlmClient {
        private final int levels;
        private final List<LlmRequest> seen = new ArrayList<>();

        Parsing(int levels) {
            this.levels = levels;
        }

        @Override
        public LlmResponse generate(LlmRequest request) {
            seen.add(request);
            if (seen.size() > 1) {
                return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of("done")),
                        LlmStopReason.END_TURN, TokenUsage.ZERO);
            }
            return LlmResponse.of(
                    Message.of(Role.ASSISTANT, ProposedCall.of("t1", "probe", nested(levels))),
                    LlmStopReason.TOOL_USE, TokenUsage.ZERO);
        }

        /** Every tool result block the model was shown, across every request. */
        List<ToolResultBlock> resultsShownToTheModel() {
            List<ToolResultBlock> shown = new ArrayList<>();
            for (LlmRequest request : seen) {
                for (Message message : request.messages()) {
                    for (ContentBlock block : message.content()) {
                        if (block instanceof ToolResultBlock result) {
                            shown.add(result);
                        }
                    }
                }
            }
            return shown;
        }
    }

    @Test
    void argumentsPastTheDepthCapAreRefusedNamingTheCallAndTheTool() {
        Map<String, Object> tooDeep = nested(Frozen.MAX_DEPTH + 1);

        assertThatThrownBy(() -> new ToolUseBlock("t1", "publish", tooDeep))
                .isInstanceOf(ToolUseBlock.UnusableArguments.class)
                .hasMessageContaining("t1")
                .hasMessageContaining("publish")
                .hasMessageContaining(String.valueOf(Frozen.MAX_DEPTH));
    }

    @Test
    void theRefusalIsStillAnIllegalArgumentExceptionSoExistingCatchesHold() {
        assertThatThrownBy(() -> new ToolUseBlock("t1", "publish", nested(Frozen.MAX_DEPTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRefusalCarriesTheCallItRefusedAndASentenceAModelCouldActOn() {
        ToolUseBlock.UnusableArguments refused = catchRefusal("t7", "publish",
                nested(Frozen.MAX_DEPTH + 1));

        assertThat(refused.id()).isEqualTo("t7");
        assertThat(refused.name()).isEqualTo("publish");
        assertThat(refused.refusal())
                .contains("No tool ran, no gate was asked")
                .contains(String.valueOf(Frozen.MAX_DEPTH));
        // Neither the id nor the tool name: a model reads this as the result of the call it
        // just made, and the correlation has already said which one that is. Both are
        // asserted, because one of them was not and the sentence could grow a tool name
        // without anything noticing — refusal()'s own javadoc makes this claim in words.
        assertThat(refused.refusal()).doesNotContain("t7").doesNotContain("publish");
    }

    @Test
    void anArgumentThatIsNotAJsonShapeIsRefusedTheSameWayAndSaysSo() {
        ToolUseBlock.UnusableArguments refused = catchRefusal("t1", "publish",
                Map.of("paths", new String[] {"/etc/shadow"}));

        assertThat(refused.name()).isEqualTo("publish");
        // Frozen's own sentence, not a second copy of it written here.
        assertThat(refused.refusal()).contains("JSON").contains("String[]");
    }

    /**
     * The id and the tool name are the provider's turn, echoed into a message that reaches
     * a log line and an {@code AgentResult}, so both are bounded — one malformed turn must
     * not buy an unbounded log record. Five hundred characters each, because the bound is
     * what is being tested and a larger payload would buy nothing but build time.
     *
     * <p>Both sprawl, and they sprawl differently. Making only the id long asserted the
     * bound on one of the two things the sentence above says are bounded, which is the same
     * one-of-two shape as the assertion in
     * {@link #theRefusalCarriesTheCallItRefusedAndASentenceAModelCouldActOn} — a claim in
     * prose with half of it checked. Distinct fill characters so that a message echoing one
     * whole cannot be read as having echoed the other.
     */
    @Test
    void theEchoedIdAndToolNameAreBothBoundedLikeEveryOtherEchoOfTheModelsOwnText() {
        String sprawlingId = "a".repeat(500);
        String sprawlingName = "b".repeat(500);

        ToolUseBlock.UnusableArguments refused =
                catchRefusal(sprawlingId, sprawlingName, nested(Frozen.MAX_DEPTH + 1));

        assertThat(refused.id()).as("the id itself is kept whole").isEqualTo(sprawlingId);
        assertThat(refused.name()).as("the name itself is kept whole").isEqualTo(sprawlingName);
        assertThat(refused.getMessage())
                .doesNotContain(sprawlingId)
                .doesNotContain(sprawlingName);
        assertThat(refused.getMessage()).hasSizeLessThan(600);
    }

    @Test
    void oneLevelInsideTheCapStillReachesTheGateAndIsDeniedWithAReason() {
        Probe probe = new Probe();
        AgentResult result = probe.run(Frozen.MAX_DEPTH - 1);

        assertThat(probe.gateSaw).isNotEmpty().containsOnly("probe");
        assertThat(probe.ran).isEmpty();
        assertThat(result.stopReason()).isNotEqualTo(StopReason.ERROR);
    }

    @Test
    void oneLevelPastTheCapReachesTheModelAsARefusalAndTheRunCarriesOn() {
        Probe probe = new Probe();

        AgentResult result = probe.run(Frozen.MAX_DEPTH + 1);

        // Unchanged and load-bearing: nothing was gated and nothing ran. Admitting the tree
        // so a gate could judge it is the fail-open repair ProposedCall's javadoc rejects,
        // and this pair of assertions is what would catch it being taken.
        assertThat(probe.gateSaw)
                .as("a gate was asked about arguments the framework had already refused")
                .isEmpty();
        assertThat(probe.ran).as("a tool ran on arguments nothing froze").isEmpty();
        // Changed: the run used to end here with StopReason.ERROR carrying an
        // UnusableArguments, and the model was never told anything at all.
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(result.error()).isEmpty();
        assertThat(result.output()).isEqualTo("done");
    }

    @Test
    void theModelIsShownTheRefusalItCanActOnCorrelatedToTheCallItMade() {
        Probe probe = new Probe();

        probe.run(Frozen.MAX_DEPTH + 1);

        // The claim the whole issue is about, asserted where it is true or false: on the
        // request the client is handed next, not on a value this test built. A refusal a
        // runner produces and never sends is the same defect one layer in.
        assertThat(probe.client.resultsShownToTheModel())
                .as("the model was shown no tool result for the call it made")
                .hasSize(1);
        ToolResultBlock shown = probe.client.resultsShownToTheModel().get(0);
        assertThat(shown.toolUseId())
                .as("a result the model cannot match to its own call")
                .isEqualTo("t1");
        assertThat(shown.isError()).isTrue();
        assertThat(shown.content())
                .contains("refused before anything ran")
                .contains("No tool ran, no gate was asked")
                .contains(String.valueOf(Frozen.MAX_DEPTH));
        // FIRST_PARTY: this sentence is the framework's own and was written without reading
        // anything, so a TrustFloor must not treat a call that never happened as a reason to
        // tighten the policy for the rest of the run.
        assertThat(shown.provenance()).isEqualTo(Provenance.FIRST_PARTY);
    }

    @Test
    void theRefusedCallIsStillInTheTurnSoTheEchoedAssistantMessageStaysValid() {
        Probe probe = new Probe();

        probe.run(Frozen.MAX_DEPTH + 1);

        // A tool_result with no matching tool_use is rejected outright by the wire format,
        // so the refused call has to survive into the assistant turn that is echoed back.
        // Dropping it at the parse — the obvious small change — makes the very request that
        // carries the refusal invalid, which no fake client would have noticed.
        List<ContentBlock> assistant = probe.client.seen.get(1).messages().stream()
                .filter(m -> m.role() == Role.ASSISTANT)
                .flatMap(m -> m.content().stream())
                .toList();
        assertThat(assistant).anySatisfy(block -> {
            assertThat(block).isInstanceOf(UnusableToolUseBlock.class);
            assertThat(((UnusableToolUseBlock) block).id()).isEqualTo("t1");
            assertThat(((UnusableToolUseBlock) block).name()).isEqualTo("probe");
        });
    }

    @Test
    void aTurnMixingARefusedCallWithARunnableOneAnswersBothInOrder() {
        // The shape a fake client with one call per turn cannot show, and the one the wire
        // format is strictest about: every tool_use needs exactly one tool_result, and a
        // turn that answers only the calls it could run leaves the other orphaned. It also
        // pins that a refused call does not take its siblings down with it — the runnable
        // one is still gated and still runs.
        List<String> gateSaw = new ArrayList<>();
        List<String> ran = new ArrayList<>();
        MixedTurn client = new MixedTurn();
        Tool probe = FunctionTool.builder("probe", "a probe")
                .handler(invocation -> {
                    ran.add(invocation.id());
                    return ToolResult.ok("ok");
                })
                .build();

        AgentResult result = Agent.builder(client, new SimpleToolRegistry(List.of(probe)),
                        AgentConfig.builder("m").maxSteps(3).build())
                .toolGate((tool, invocation) -> {
                    gateSaw.add(invocation.id());
                    return GateResult.allow();
                })
                .build()
                .run(Goal.of("probe"));

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(gateSaw).as("the gate saw the refused call, or missed the good one")
                .containsExactly("good");
        assertThat(ran).containsExactly("good");
        List<ToolResultBlock> shown = client.resultsShownToTheModel();
        assertThat(shown)
                .as("a tool_use was left with no tool_result, which the wire format rejects")
                .hasSize(2);
        // In the order the provider sent the calls, which is the order the results have to
        // be readable in for a model to match them to what it asked for.
        assertThat(shown.stream().map(ToolResultBlock::toolUseId))
                .containsExactly("good", "deep");
        assertThat(shown.get(0).isError()).isFalse();
        assertThat(shown.get(1).isError()).isTrue();
        assertThat(shown.get(1).content()).contains("No tool ran, no gate was asked");
    }

    /** A turn carrying a call the framework can carry and one it cannot, in that order. */
    private static final class MixedTurn implements LlmClient {
        private final List<LlmRequest> seen = new ArrayList<>();

        @Override
        public LlmResponse generate(LlmRequest request) {
            seen.add(request);
            if (seen.size() > 1) {
                return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of("done")),
                        LlmStopReason.END_TURN, TokenUsage.ZERO);
            }
            return LlmResponse.of(Message.of(Role.ASSISTANT, List.of(
                            ProposedCall.of("good", "probe", Map.of("text", "fine")),
                            ProposedCall.of("deep", "probe", nested(Frozen.MAX_DEPTH + 1)))),
                    LlmStopReason.TOOL_USE, TokenUsage.ZERO);
        }

        List<ToolResultBlock> resultsShownToTheModel() {
            List<ToolResultBlock> shown = new ArrayList<>();
            for (LlmRequest request : seen) {
                for (Message message : request.messages()) {
                    for (ContentBlock block : message.content()) {
                        if (block instanceof ToolResultBlock result) {
                            shown.add(result);
                        }
                    }
                }
            }
            return shown;
        }
    }

    @Test
    void theOperatorIsToldWhichCallWasRefusedEvenThoughTheRunCompletes() {
        // The line matters more after this change than before it, which is the inversion
        // #260 names in the other direction. The run used to end with StopReason.ERROR
        // carrying an UnusableArguments, so a caller reading AgentResult learned what had
        // happened. It now completes, and AgentResult carries nothing — so for a deployment
        // with no observer wired, and AgentObserver.NONE is the shipped default, this line
        // is the entire record that the model's own output was refused.
        Probe probe = new Probe();

        String captured = errWhile(() -> {
            AgentResult result = probe.run(Frozen.MAX_DEPTH + 1);
            assertThat(result.stopReason())
                    .as("the precondition: this path no longer reports anything at run level")
                    .isEqualTo(StopReason.COMPLETED);
            assertThat(result.error()).isEmpty();
        });

        assertThat(captured)
                .contains("proposed call 't1' on tool 'probe'")
                .contains("no gate was asked and nothing ran")
                // The reason, not only the fact. An operator seeing this repeatedly needs
                // to know which of Frozen's three bounds a deployment keeps hitting.
                .contains(String.valueOf(Frozen.MAX_DEPTH));

        // The other side of the branch, because a line that fires either way reports
        // nothing and an unguarded log passes every assertion above.
        assertThat(errWhile(() -> new Probe().run(Frozen.MAX_DEPTH - 1)))
                .as("a call the framework carried fine was reported as refused")
                .doesNotContain("no gate was asked and nothing ran");
    }

    /**
     * Everything {@code body} writes to {@code System.err}, with the stream put back.
     *
     * <p>{@code slf4j-simple} resolves {@code System.err} on each write — {@code
     * cacheOutputStream} is off by default — so a logger initialised long before this call
     * still lands in the buffer. Restored in a {@code finally}, because a test that leaves
     * {@code System.err} replaced takes the rest of the suite's output with it.
     */
    private static String errWhile(Runnable body) {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true,
                java.nio.charset.StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void theObserverHearsAboutACallThatWasNeverOfferedToAnything() {
        Probe probe = new Probe();
        Recorder recorder = new Recorder();

        probe.run(Frozen.MAX_DEPTH + 1, recorder);

        // Reported at all, for the duplicate-id branch's reason: a trajectory showing no
        // calls for a turn that requested one is the "wiring looked right and did nothing"
        // shape. NOT_ATTEMPTED because no policy saw it and no decision exists to point at
        // — REFUSED would file a governance event nobody authored.
        assertThat(recorder.dispositions).containsExactly(Disposition.NOT_ATTEMPTED);
        assertThat(recorder.proposed).hasSize(1);
        assertThat(recorder.proposed.get(0).name()).isEqualTo("probe");
        // Empty, and it means "not carried" rather than "the model sent none": the block
        // does not hold the tree, because holding it is what was refused. Pinned because it
        // is a contract and not an accident — an audit row that invented plausible
        // arguments would be #131's own defect.
        assertThat(recorder.proposed.get(0).arguments()).isEmpty();
        assertThat(recorder.contents).hasSize(1);
        assertThat(recorder.contents.get(0)).contains("No tool ran, no gate was asked");
    }

    private static ToolUseBlock.UnusableArguments catchRefusal(String id, String name,
                                                               Map<String, Object> input) {
        try {
            new ToolUseBlock(id, name, input);
        } catch (ToolUseBlock.UnusableArguments refused) {
            return refused;
        }
        throw new AssertionError("expected the arguments to be refused");
    }

    /** One run against a gate that denies everything with a reason — the tripwire. */
    private static final class Probe {
        private final List<String> gateSaw = new ArrayList<>();
        private final List<String> ran = new ArrayList<>();
        private Parsing client;

        AgentResult run(int levels) {
            return run(levels, AgentObserver.NONE);
        }

        AgentResult run(int levels, AgentObserver observer) {
            ToolGate denying = (tool, invocation) -> {
                gateSaw.add(invocation.name());
                return GateResult.deny("denied by policy");
            };
            Tool probe = FunctionTool.builder("probe", "a probe")
                    .handler(invocation -> {
                        ran.add(invocation.name());
                        return ToolResult.ok("ok");
                    })
                    .build();
            client = new Parsing(levels);
            return Agent.builder(client, new SimpleToolRegistry(List.of(probe)),
                            AgentConfig.builder("m").maxSteps(3).build())
                    .toolGate(denying)
                    .observer(observer)
                    .build()
                    .run(Goal.of("probe"));
        }
    }

    /** What the observer was told about each call. */
    private static final class Recorder implements AgentObserver {
        private final List<ToolInvocation> proposed = new ArrayList<>();
        private final List<Disposition> dispositions = new ArrayList<>();
        private final List<String> contents = new ArrayList<>();

        @Override
        public void onToolProposed(AgentRun run, int step, ToolInvocation invocation) {
            proposed.add(invocation);
        }

        @Override
        public void onToolResult(AgentRun run, int step,
                                 ToolInvocation invocation, ToolInvocation effective,
                                 ToolResult result, Disposition disposition) {
            dispositions.add(disposition);
            contents.add(result.content());
        }
    }
}
