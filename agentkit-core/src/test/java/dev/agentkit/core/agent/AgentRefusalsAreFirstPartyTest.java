package dev.agentkit.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The in-process loop's half of "a framework-authored refusal is first-party" (#272).
 *
 * <h2>Why these branches needed a test of their own</h2>
 *
 * <p>{@code Agent.runTool} already declared {@code FIRST_PARTY} on its refusals, and nothing
 * asserted it beyond the unknown-tool branch. Found by planting the reverse: three of this
 * loop's refusal branches could be returned to {@code ToolResult.error}'s {@code UNKNOWN}
 * default with the whole core suite still green. Two of the three have no consequence to
 * assert — the run is ending by the time they are written — and are checked as values with
 * that said out loud. The third does, and is checked as one.
 *
 * <h2>The consequence, for the branch that has one</h2>
 *
 * <p>{@code Agent.run} asks {@code trustFloor.lowersOn(result.provenance())} on every
 * attempt's result, inside the turn. So a gate denial left at {@code UNKNOWN} tightened the
 * policy for the rest of the run on the strength of a sentence the deployment's own gate
 * wrote about a call that never entered a tool. Asserted through what happens to the
 * <em>next</em> call rather than by reading the field back: a floor that recorded
 * {@code FIRST_PARTY} and lowered anyway would pass the field check.
 */
class AgentRefusalsAreFirstPartyTest {

    private static final AgentConfig CONFIG =
            AgentConfig.builder("m").maxSteps(5).build();

    /** A writer, so {@code readOnly()} — the tightened policy below — refuses it. */
    private static Tool publisher(List<ToolInvocation> entered) {
        return FunctionTool.builder("publish", "Publishes text somewhere public")
                .schema(Map.of("type", "object",
                        "properties", Map.of("text", Map.of("type", "string"))))
                .sideEffects(SideEffects.EXTERNAL)
                // FIRST_PARTY so that its own result cannot be what lowers the floor; what
                // is under test is whether the refusal before it did.
                .provenance(Provenance.FIRST_PARTY)
                .handler(invocation -> {
                    entered.add(invocation);
                    return ToolResult.ok("published");
                })
                .build();
    }

    /** A reader whose answer really is somebody else's, for the denominator arm. */
    private static Tool fetcher() {
        return FunctionTool.builder("fetch", "Fetches a page")
                .schema(Map.of("type", "object", "properties", Map.of()))
                .sideEffects(SideEffects.NONE)
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> ToolResult.ok("a page someone else wrote"))
                .build();
    }

    @Test
    void aGateDenialDoesNotTightenTheFloorForTheRestOfTheRun() {
        // Turn one calls a tool the ordinary policy refuses by name; turn two calls the
        // writer, which the ordinary policy allows and the tightened policy (readOnly) does
        // not. So whether the writer is entered is exactly the question "did the denial
        // lower the floor".
        List<ToolInvocation> entered = new ArrayList<>();
        TrustFloor floor = TrustFloor.afterAnythingUndeclared(
                ToolGates.denyIf(invocation -> invocation.name().equals("fetch"),
                        "fetching is out of scope for this run"),
                ToolGates.readOnly());

        Agent.builder(new FakeLlmClient(
                                FakeLlmClient.toolUse("t1", "fetch", Map.of()),
                                FakeLlmClient.toolUse("t2", "publish", Map.of("text", "hi")),
                                FakeLlmClient.text("Done.")),
                        new SimpleToolRegistry().register(publisher(entered)).register(fetcher()),
                        CONFIG)
                .trustFloor(floor)
                .build()
                .run(Goal.of("go"));

        assertThat(entered)
                .as("a denied call — nothing entered, nothing read — tightened the policy"
                        + " for the rest of the run, so the next call was refused by a floor"
                        + " nothing had lowered")
                .hasSize(1);
    }

    @Test
    void aCallThatReallyReadsSomebodyElsesWordsStillTightensIt() {
        // The denominator. Without it the assertion above is satisfied by a floor that never
        // lowers on anything, which is the failure direction this change makes easy. Same
        // two turns, with the first one allowed to run instead of denied.
        List<ToolInvocation> entered = new ArrayList<>();
        TrustFloor floor = TrustFloor.afterAnythingUndeclared(
                ToolGate.ALLOW_ALL, ToolGates.readOnly());

        Agent.builder(new FakeLlmClient(
                                FakeLlmClient.toolUse("t1", "fetch", Map.of()),
                                FakeLlmClient.toolUse("t2", "publish", Map.of("text", "hi")),
                                FakeLlmClient.text("Done.")),
                        new SimpleToolRegistry().register(publisher(entered)).register(fetcher()),
                        CONFIG)
                .trustFloor(floor)
                .build()
                .run(Goal.of("go"));

        assertThat(entered)
                .as("the floor no longer tightens after a tool that declares it returns"
                        + " somebody else's words, so the assertion above says nothing")
                .isEmpty();
    }

    @Test
    void aTurnRefusedForRepeatedIdsIsReportedAsTheFrameworksOwnWords() {
        // A value rather than a consequence, and the reason is worth stating: this refusal
        // is written as the run ends, so there is no later call for a tightened policy to
        // act on. What reads it is an observer building an audit trail, which is the reader
        // Provenance exists for.
        Recorder recorder = new Recorder();

        Agent.builder(new RepeatedIdTurn(),
                        new SimpleToolRegistry().register(publisher(new ArrayList<>())), CONFIG)
                .observer(recorder)
                .build()
                .run(Goal.of("go"));

        assertThat(recorder.provenances)
                .as("the loop said nothing about who wrote its own refusal of a turn that"
                        + " named one call twice")
                .containsOnly(Provenance.FIRST_PARTY);
        assertThat(recorder.dispositions).containsOnly(Disposition.NOT_ATTEMPTED);
    }

    @Test
    void aCallNotAttemptedAfterAParkIsReportedAsTheFrameworksOwnWords() {
        // The same, for the other branch that writes as the run ends: a later call in a turn
        // whose earlier call parked. It was never offered to a gate, so nothing decided
        // anything about it and nothing was read.
        Recorder recorder = new Recorder();

        Agent.builder(new TwoCallTurn(),
                        new SimpleToolRegistry().register(publisher(new ArrayList<>())), CONFIG)
                .toolGate((tool, invocation) -> GateResult.needsAPerson(
                        ApprovalNeeded.because("a person must look"), invocation))
                .observer(recorder)
                .build()
                .run(Goal.of("go"));

        assertThat(recorder.dispositions)
                .as("the denominator for this arm: the first call really did park and the"
                        + " second really was left unattempted")
                .containsExactly(Disposition.PARKED, Disposition.NOT_ATTEMPTED);
        assertThat(recorder.provenances)
                .as("the loop said nothing about who wrote the two sentences it composed"
                        + " about calls that never ran")
                .containsOnly(Provenance.FIRST_PARTY);
    }

    /** A turn naming one call twice, which the loop refuses outright. */
    private static final class RepeatedIdTurn implements LlmClient {
        @Override
        public LlmResponse generate(LlmRequest request) {
            return LlmResponse.of(Message.of(Role.ASSISTANT, List.of(
                            ProposedCall.of("t1", "publish", Map.of("text", "one")),
                            ProposedCall.of("t1", "publish", Map.of("text", "two")))),
                    LlmStopReason.TOOL_USE, TokenUsage.ZERO);
        }
    }

    /** A turn with two distinct calls, so a park on the first leaves the second unattempted. */
    private static final class TwoCallTurn implements LlmClient {
        private int turns;

        @Override
        public LlmResponse generate(LlmRequest request) {
            if (turns++ > 0) {
                return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of("done")),
                        LlmStopReason.END_TURN, TokenUsage.ZERO);
            }
            return LlmResponse.of(Message.of(Role.ASSISTANT, List.of(
                            ProposedCall.of("t1", "publish", Map.of("text", "one")),
                            ProposedCall.of("t2", "publish", Map.of("text", "two")))),
                    LlmStopReason.TOOL_USE, TokenUsage.ZERO);
        }
    }

    /** What the observer was told about each call. */
    private static final class Recorder implements AgentObserver {
        private final List<Disposition> dispositions = new ArrayList<>();
        private final List<Provenance> provenances = new ArrayList<>();

        @Override
        public void onToolResult(AgentRun run, int step,
                                 ToolInvocation invocation, ToolInvocation effective,
                                 ToolResult result, Disposition disposition) {
            dispositions.add(disposition);
            provenances.add(result.provenance());
        }
    }
}
