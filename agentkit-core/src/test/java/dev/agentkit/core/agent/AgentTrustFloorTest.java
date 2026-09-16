package dev.agentkit.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.reliability.TrustFloor;
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
 * "After you read the web, you cannot write", in process (#122).
 *
 * <p><strong>Every test counts what the tool did.</strong> Asking the loop whether it
 * lowered the floor is asking the control whether it worked; it passes for a loop that
 * lowers the floor and publishes anyway.
 */
class AgentTrustFloorTest {

    private static final AgentConfig CONFIG = AgentConfig.builder("m").maxSteps(6).build();

    /** Returns somebody else's words, and says so. */
    private static Tool fetcher(List<String> entered) {
        return FunctionTool.builder("fetch", "Fetches a page")
                .sideEffects(SideEffects.NONE)
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> {
                    entered.add("fetch");
                    return ToolResult.ok("the page said something");
                })
                .build();
    }

    /** Returns the deployment's own words. */
    private static Tool ourOwnRecords(List<String> entered) {
        return FunctionTool.builder("lookup", "Reads our own table")
                .sideEffects(SideEffects.NONE)
                .provenance(Provenance.FIRST_PARTY)
                .handler(invocation -> {
                    entered.add("lookup");
                    return ToolResult.ok("row 7");
                })
                .build();
    }

    /** Declares nothing, so it reports UNKNOWN. */
    private static Tool undeclared(List<String> entered) {
        return FunctionTool.builder("mystery", "Nobody said what this returns")
                .sideEffects(SideEffects.NONE)
                .handler(invocation -> {
                    entered.add("mystery");
                    return ToolResult.ok("something");
                })
                .build();
    }

    private static Tool publisher(List<String> entered) {
        return FunctionTool.builder("publish", "Publishes text somewhere public")
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> {
                    entered.add("publish");
                    return ToolResult.ok("published");
                })
                .build();
    }

    private static AgentResult run(TrustFloor floor, List<Tool> tools, LlmResponse... turns) {
        return Agent.builder(new FakeLlmClient(turns), new SimpleToolRegistry(tools), CONFIG)
                .trustFloor(floor)
                .build()
                .run(Goal.of("do it"));
    }

    /** One assistant turn asking for both tools, in order. */
    private static LlmResponse bothAtOnce(String first, String second) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, List.of(
                        ProposedCall.of("t1", first, Map.of()),
                        ProposedCall.of("t2", second, Map.of()))),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO);
    }

    // --- the tests ----------------------------------------------------------------

    @Test
    void aWriteAfterAReadOfSomebodyElsesWordsIsRefused() {
        List<String> entered = new ArrayList<>();

        AgentResult result = run(TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, ToolGates.readOnly()),
                List.of(fetcher(entered), publisher(entered)),
                FakeLlmClient.toolUse("t1", "fetch", Map.of()),
                FakeLlmClient.toolUse("t2", "publish", Map.of()),
                FakeLlmClient.text("done"));

        assertThat(entered)
                .as("the fetch is allowed and the publish after it is not — a loop that"
                        + " lowered the floor and published anyway is what this catches")
                .containsExactly("fetch");
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
    }

    @Test
    void thatSameWriteIsAllowedWhenNothingHasBeenRead() {
        // The positive control. Without it, "the publish did not happen" would pass for a
        // floor that refuses writes always, which is a different and much less useful thing.
        List<String> entered = new ArrayList<>();

        run(TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, ToolGates.readOnly()),
                List.of(fetcher(entered), publisher(entered)),
                FakeLlmClient.toolUse("t1", "publish", Map.of()),
                FakeLlmClient.text("done"));

        assertThat(entered).containsExactly("publish");
    }

    @Test
    void readingOurOwnRecordsDoesNotLowerTheFloor() {
        List<String> entered = new ArrayList<>();

        run(TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, ToolGates.readOnly()),
                List.of(ourOwnRecords(entered), publisher(entered)),
                FakeLlmClient.toolUse("t1", "lookup", Map.of()),
                FakeLlmClient.toolUse("t2", "publish", Map.of()),
                FakeLlmClient.text("done"));

        assertThat(entered).containsExactly("lookup", "publish");
    }

    @Test
    void theDeclaredReadingIgnoresAToolThatSaidNothing() {
        List<String> entered = new ArrayList<>();

        run(TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, ToolGates.readOnly()),
                List.of(undeclared(entered), publisher(entered)),
                FakeLlmClient.toolUse("t1", "mystery", Map.of()),
                FakeLlmClient.toolUse("t2", "publish", Map.of()),
                FakeLlmClient.text("done"));

        assertThat(entered)
                .as("a floor cannot act on a fact nobody stated; choosing this reading is"
                        + " choosing that")
                .containsExactly("mystery", "publish");
    }

    @Test
    void theStrictReadingTreatsSilenceAsSomebodyElses() {
        List<String> entered = new ArrayList<>();

        run(TrustFloor.afterAnythingUndeclared(ToolGate.ALLOW_ALL, ToolGates.readOnly()),
                List.of(undeclared(entered), publisher(entered)),
                FakeLlmClient.toolUse("t1", "mystery", Map.of()),
                FakeLlmClient.toolUse("t2", "publish", Map.of()),
                FakeLlmClient.text("done"));

        assertThat(entered).containsExactly("mystery");
    }

    @Test
    void theFloorLowersWithinTheTurnAndNotAfterIt() {
        // A turn that reads a page and then writes has the page in hand before the write is
        // gated. Waiting until the turn ended would make every multi-tool turn's first read
        // a free pass — which is the whole attack, in one turn instead of two.
        List<String> entered = new ArrayList<>();

        run(TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, ToolGates.readOnly()),
                List.of(fetcher(entered), publisher(entered)),
                bothAtOnce("fetch", "publish"),
                FakeLlmClient.text("done"));

        assertThat(entered).containsExactly("fetch");
    }

    @Test
    void bothCallsOfATurnRunWhenNothingLowersTheFloor() {
        // The control the test above was missing, and it was missing something real: a
        // mutant making only the first tool of any multi-tool turn run left all twelve
        // tests in this class green. "containsExactly(fetch)" could not tell "the floor
        // stopped the publish" from "the second call never ran" — verbatim the failure mode
        // this file's header claims to have eliminated.
        List<String> entered = new ArrayList<>();

        run(TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, ToolGates.readOnly()),
                List.of(ourOwnRecords(entered), publisher(entered)),
                bothAtOnce("lookup", "publish"),
                FakeLlmClient.text("done"));

        assertThat(entered).containsExactly("lookup", "publish");
    }

    @Test
    void onceLoweredItStaysLoweredForTheRestOfTheRun() {
        List<String> entered = new ArrayList<>();

        run(TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, ToolGates.readOnly()),
                List.of(fetcher(entered), ourOwnRecords(entered), publisher(entered)),
                FakeLlmClient.toolUse("t1", "fetch", Map.of()),
                FakeLlmClient.toolUse("t2", "lookup", Map.of()),
                FakeLlmClient.toolUse("t3", "publish", Map.of()),
                FakeLlmClient.text("done"));

        assertThat(entered)
                .as("reading our own words afterwards does not undo having read the web —"
                        + " the content is in the context and nothing here can see what the"
                        + " model did with it")
                .containsExactly("fetch", "lookup");
    }

    @Test
    void aPlainGateStillMeansWhatItMeant() {
        // Every existing caller wires a gate and no floor. That becomes a floor whose two
        // policies are the same one, so nothing about it changes.
        List<String> entered = new ArrayList<>();

        AgentResult result = Agent.builder(
                        new FakeLlmClient(FakeLlmClient.toolUse("t1", "publish", Map.of()),
                                FakeLlmClient.text("done")),
                        new SimpleToolRegistry(List.of(fetcher(entered), publisher(entered))),
                        CONFIG)
                .toolGate(ToolGates.readOnly())
                .build()
                .run(Goal.of("publish"));

        assertThat(entered).isEmpty();
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
    }

    @Test
    void settingBothAGateAndAFloorIsRefusedRatherThanResolved() {
        // They are alternatives — a floor already holds the policy a gate would be — so
        // honouring one means dropping the other. The first version resolved it by
        // last-writer-wins in both directions, which meant .trustFloor(f).toolGate(g)
        // silently demoted a two-policy control to one gate: an authorization policy
        // dropped without a word, which is the shape CodeExecutionTool's builder refuses.
        assertThatThrownBy(() -> Agent.builder(new FakeLlmClient(FakeLlmClient.text("x")),
                        new SimpleToolRegistry(List.of()), CONFIG)
                .trustFloor(TrustFloor.afterThirdParty(ToolGates.readOnly(), ToolGates.readOnly()))
                .toolGate(ToolGate.ALLOW_ALL)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alternatives");
    }

    @Test
    void compactionCannotRaiseTheFloorItLowered() {
        // One of #122's own open questions. A summary of third-party content is still
        // third-party, and SummarizingCompactor renders a tool result into plain text with
        // no provenance on it — so a floor derived by *scanning the transcript* would rise
        // the moment the transcript was rewritten, silently, on a long run.
        //
        // This strategy is the worst case, stated plainly: it throws the whole history away
        // except the goal. The floor is a monotonic flag and not a scan, so it does not care.
        List<String> entered = new ArrayList<>();
        dev.agentkit.core.context.ContextStrategy forgetsEverything =
                history -> List.of(history.get(0));

        Agent.builder(new FakeLlmClient(
                                FakeLlmClient.toolUse("t1", "fetch", Map.of()),
                                FakeLlmClient.toolUse("t2", "publish", Map.of()),
                                FakeLlmClient.text("done")),
                        new SimpleToolRegistry(List.of(fetcher(entered), publisher(entered))),
                        CONFIG)
                .trustFloor(TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, ToolGates.readOnly()))
                .contextStrategy(forgetsEverything)
                .build()
                .run(Goal.of("do it"));

        assertThat(entered)
                .as("the evidence that the floor should be down was deleted from the"
                        + " transcript, and the floor stayed down")
                .containsExactly("fetch");
    }

    @Test
    void thatSameCompactionDoesNotLowerAFloorNothingHasLowered() {
        // The control for the test above: the forgetful strategy is not itself what stops
        // the publish. Without this, "containsExactly(fetch)" would pass for a strategy
        // that broke the run outright.
        List<String> entered = new ArrayList<>();
        dev.agentkit.core.context.ContextStrategy forgetsEverything =
                history -> List.of(history.get(0));

        Agent.builder(new FakeLlmClient(
                                FakeLlmClient.toolUse("t1", "lookup", Map.of()),
                                FakeLlmClient.toolUse("t2", "publish", Map.of()),
                                FakeLlmClient.text("done")),
                        new SimpleToolRegistry(List.of(ourOwnRecords(entered), publisher(entered))),
                        CONFIG)
                .trustFloor(TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, ToolGates.readOnly()))
                .contextStrategy(forgetsEverything)
                .build()
                .run(Goal.of("do it"));

        assertThat(entered).containsExactly("lookup", "publish");
    }

    @Test
    void aFailingThirdPartyToolStillLowersTheFloor() {
        // A tool that throws returns whoever's words were in the exception — #113 is the
        // issue about exactly that — so a failure is not a free pass. A tool that fails on
        // purpose would otherwise be a way to read the web without paying for it.
        List<String> entered = new ArrayList<>();
        Tool failsWithSomebodyElsesWords = FunctionTool.builder("fetch", "Fetches a page")
                .sideEffects(SideEffects.NONE)
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> {
                    entered.add("fetch");
                    throw new IllegalStateException("HTTP 500: <the server's own words>");
                })
                .build();

        run(TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, ToolGates.readOnly()),
                List.of(failsWithSomebodyElsesWords, publisher(entered)),
                FakeLlmClient.toolUse("t1", "fetch", Map.of()),
                FakeLlmClient.toolUse("t2", "publish", Map.of()),
                FakeLlmClient.text("done"));

        assertThat(entered).containsExactly("fetch");
    }

    @Test
    void aFailingFirstPartyToolDoesNotLowerTheFloor() {
        // The control for the test above. Without it, that test also passes for an
        // implementation where *any* throw lowers the floor — which would be a different
        // rule, and a worse one.
        List<String> entered = new ArrayList<>();
        Tool failsWithOurOwnWords = FunctionTool.builder("lookup", "Reads our own table")
                .sideEffects(SideEffects.NONE)
                .provenance(Provenance.FIRST_PARTY)
                .handler(invocation -> {
                    entered.add("lookup");
                    throw new IllegalStateException("our own table is down");
                })
                .build();

        run(TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, ToolGates.readOnly()),
                List.of(failsWithOurOwnWords, publisher(entered)),
                FakeLlmClient.toolUse("t1", "lookup", Map.of()),
                FakeLlmClient.toolUse("t2", "publish", Map.of()),
                FakeLlmClient.text("done"));

        assertThat(entered).containsExactly("lookup", "publish");
    }

    @Test
    void aRunWithNoFloorIsNeverToldOneEngaged() {
        // A plain gate used to be stored as afterThirdParty(gate, gate), which lowers — so
        // a deployment that had never heard of a floor logged that its floor had engaged on
        // its first third-party result, and durably switched activity method mid-run. Every
        // MCP tool declares THIRD_PARTY, so that was most MCP deployments.
        List<String> entered = new ArrayList<>();

        Agent.builder(new FakeLlmClient(
                                FakeLlmClient.toolUse("t1", "fetch", Map.of()),
                                FakeLlmClient.toolUse("t2", "publish", Map.of()),
                                FakeLlmClient.text("done")),
                        new SimpleToolRegistry(List.of(fetcher(entered), publisher(entered))),
                        CONFIG)
                .toolGate(ToolGate.ALLOW_ALL)
                .build()
                .run(Goal.of("do it"));

        assertThat(entered)
                .as("no floor was wired, so nothing tightens and the publish runs")
                .containsExactly("fetch", "publish");
        assertThat(dev.agentkit.core.reliability.TrustFloor.none(ToolGate.ALLOW_ALL).exists())
                .isFalse();
    }
}
