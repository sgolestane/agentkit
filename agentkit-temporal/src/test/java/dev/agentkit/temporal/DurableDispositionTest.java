package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.PendingApproval;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Whether a durable run's history says the action happened (#181).
 *
 * <h2>Why the durable path needs its own half of this</h2>
 *
 * <p>The durable path has no {@code AgentObserver} — {@code AgentWorkflowImpl}'s javadoc
 * says so — so <strong>Temporal history is the audit trail</strong>, and the only thing it
 * held about the outcome of a call was {@code isError}. That is true for an unknown tool, a
 * denial, a park, a gate that threw, a tool that returned an error and a tool that threw:
 * six states on one bit, with the two that may have landed a side effect on the same side
 * of it as the four that could not have.
 *
 * <p>#179 put the same argument for {@code effective}, one question earlier: which call was
 * settled on. This is whether it ran. The two runners answer both questions the same way on
 * purpose — a repository comment in {@code ToolActivitiesImpl} justifies itself three times
 * over with "the two runners diverging is worse than either answer" — so the in-process
 * assertions in {@code ObserverCanTellWhatRanTest} and these are deliberately parallel.
 *
 * <p>Every case that claims a tool ran compares the disposition against a list the tool
 * itself appends to, rather than against a value the test computed. A runner that stamped
 * {@code RAN} on everything and ran nothing is the shape this repository has shipped.
 */
class DurableDispositionTest {

    private static final ToolInvocation PROPOSED =
            new ToolInvocation("t1", "publish", Map.of("text", "/etc/shadow"));

    private static FunctionTool publisher(List<ToolInvocation> entered,
                                          Function<ToolInvocation, ToolResult> body) {
        return FunctionTool.builder("publish", "Publishes text somewhere public")
                .schema(Map.of("type", "object",
                        "properties", Map.of("text", Map.of("type", "string"))))
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> {
                    entered.add(invocation);
                    return body.apply(invocation);
                })
                .build();
    }

    private static ToolActivitiesImpl activities(ToolGate gate, List<ToolInvocation> entered,
                                                 Function<ToolInvocation, ToolResult> body) {
        return new ToolActivitiesImpl(
                new SimpleToolRegistry().register(publisher(entered, body)), gate);
    }

    private static Disposition of(ToolOutcome outcome) {
        return outcome.settledAs().orElseThrow(
                () -> new AssertionError("the outcome recorded no disposition at all"));
    }

    // --- the paths where nothing ran ---------------------------------------------

    @Test
    void anUnknownToolIsNotARefusal() {
        ToolActivitiesImpl activities = new ToolActivitiesImpl(new SimpleToolRegistry(),
                ToolGate.ALLOW_ALL);

        ToolOutcome outcome = activities.executeTool(PROPOSED);

        assertThat(of(outcome)).isEqualTo(Disposition.UNKNOWN_TOOL);
        assertThat(of(outcome).reachedTool()).isFalse();
        assertThat(outcome.result().isError()).isTrue();
    }

    @Test
    void theDurableRunnerWritesTheUnknownToolSentenceTheOtherRunnersWrite() {
        // #278. This runner echoed the model's own name into the sentence raw, like the
        // in-process loop and the code bridge did; unlike theirs, this one is persisted in
        // Temporal history rather than merely printed. Asserted against the factory rather
        // than against a literal, because what the issue asks for is that the runners
        // AGREE — a literal here would pass for a fourth runner that had drifted.
        String hostile = "publish\n2026-01-01 INFO all clear" + "z".repeat(500);
        ToolActivitiesImpl activities = new ToolActivitiesImpl(new SimpleToolRegistry(),
                ToolGate.ALLOW_ALL);

        ToolOutcome outcome = activities.executeTool(
                new ToolInvocation("t1", hostile, Map.of()));

        assertThat(outcome.result().content())
                .isEqualTo(ToolResult.unknownTool(hostile).content());
        assertThat(outcome.result().content())
                .as("an unbounded name bought an unbounded history payload")
                .hasSizeLessThan(200)
                .doesNotContain("\n");
        assertThat(outcome.result().provenance()).isEqualTo(Provenance.FIRST_PARTY);
    }

    @Test
    void aDeniedCallIsRefused() {
        List<ToolInvocation> entered = new ArrayList<>();

        ToolOutcome outcome = activities((tool, invocation) -> GateResult.deny("not durably"),
                entered, inv -> ToolResult.ok("published")).executeTool(PROPOSED);

        assertThat(entered).isEmpty();
        assertThat(of(outcome)).isEqualTo(Disposition.REFUSED);
    }

    @Test
    void aParkedCallIsNotARefusalBecauseItIsNotOver() {
        List<ToolInvocation> entered = new ArrayList<>();

        ToolOutcome outcome = activities((tool, invocation) -> GateResult.needsAPerson(
                        ApprovalNeeded.because("a person must look"), invocation),
                entered, inv -> ToolResult.ok("published")).executeTool(PROPOSED);

        assertThat(entered).isEmpty();
        assertThat(of(outcome)).isEqualTo(Disposition.PARKED);
        assertThat(outcome.parked()).isPresent();
    }

    @Test
    void aGateThatThrewIsNotAToolThatThrew() {
        // The pair this enum exists to separate on this runner too: both leave through the
        // same catch, both produce "Tool 'publish' failed." with the thrower's words fenced
        // inside, and one of them may have published something.
        List<ToolInvocation> entered = new ArrayList<>();

        ToolOutcome outcome = activities((tool, invocation) -> {
            throw new IllegalStateException("the policy service is down");
        }, entered, inv -> ToolResult.ok("published")).executeTool(PROPOSED);

        assertThat(entered).isEmpty();
        assertThat(of(outcome)).isEqualTo(Disposition.GATE_FAILED);
        assertThat(of(outcome).reachedTool()).isFalse();
    }

    @Test
    void aRefusedReplacementNeverBecameACall() {
        // effectiveFor throws when a replacement renames the tool (#104). The gate is what
        // failed, not the tool, and nothing was published.
        List<ToolInvocation> entered = new ArrayList<>();

        ToolOutcome outcome = activities((tool, invocation) -> GateResult.allowWith(
                        new ToolInvocation(invocation.id(), "draft", invocation.arguments())),
                entered, inv -> ToolResult.ok("published")).executeTool(PROPOSED);

        assertThat(entered).isEmpty();
        assertThat(of(outcome)).isEqualTo(Disposition.GATE_FAILED);
    }

    @Test
    void anApprovalForADifferentCallAttemptsNothing() {
        // The framework declining to start something, before any gate is consulted. There
        // is no decision and no decider, so recording it as a denial would invent both.
        List<ToolInvocation> entered = new ArrayList<>();

        ToolOutcome outcome = activities(ToolGate.ALLOW_ALL, entered,
                inv -> ToolResult.ok("published"))
                .resumeTool(PROPOSED, new ApprovalVerdict("park-1", "some-other-call",
                        ApprovalDecision.Kind.APPROVE, "", null, "alice"));

        assertThat(entered).isEmpty();
        assertThat(of(outcome)).isEqualTo(Disposition.NOT_ATTEMPTED);
    }

    @Test
    void aPersonsDenialOfAParkedCallIsARefusalAndNotAPark() {
        // The call was parked, somebody answered, and the answer was no. History should say
        // it is settled and will not run — which is what an auditor asks of a row that once
        // had a decision outstanding.
        List<ToolInvocation> entered = new ArrayList<>();

        ToolOutcome outcome = activities((tool, invocation) -> GateResult.needsAPerson(
                        ApprovalNeeded.because("a person must look"), invocation),
                entered, inv -> ToolResult.ok("published"))
                .resumeTool(PROPOSED, new ApprovalVerdict("park-1", "t1",
                        ApprovalDecision.Kind.DENY, "absolutely not", null, "alice"));

        assertThat(entered).isEmpty();
        assertThat(of(outcome))
                .as("a call a person refused was still reported as waiting for one")
                .isEqualTo(Disposition.REFUSED);
    }

    // --- the paths that reached the tool ------------------------------------------

    @Test
    void anExecutedCallRan() {
        List<ToolInvocation> entered = new ArrayList<>();

        ToolOutcome outcome = activities(ToolGate.ALLOW_ALL, entered,
                inv -> ToolResult.ok("published")).executeTool(PROPOSED);

        assertThat(entered).hasSize(1);
        assertThat(of(outcome)).isEqualTo(Disposition.RAN);
        assertThat(of(outcome).reachedTool()).isTrue();
    }

    @Test
    void aToolThatReturnedAnErrorStillRan() {
        List<ToolInvocation> entered = new ArrayList<>();

        ToolOutcome outcome = activities(ToolGate.ALLOW_ALL, entered,
                inv -> ToolResult.error("the endpoint said no")).executeTool(PROPOSED);

        assertThat(entered).hasSize(1);
        assertThat(outcome.result().isError()).isTrue();
        assertThat(of(outcome))
                .as("an error result from a tool that ran is the state isError() could not"
                        + " tell from the five that never reached one")
                .isEqualTo(Disposition.RAN);
    }

    @Test
    void aToolThatThrewReachedTheTool() {
        List<ToolInvocation> entered = new ArrayList<>();

        ToolOutcome outcome = activities(ToolGate.ALLOW_ALL, entered, inv -> {
            throw new IllegalStateException("the endpoint went away mid-publish");
        }).executeTool(PROPOSED);

        assertThat(entered).as("the tool did not run, so this test measured nothing")
                .hasSize(1);
        assertThat(of(outcome)).isEqualTo(Disposition.THREW);
        assertThat(of(outcome).reachedTool()).isTrue();
    }

    @Test
    void aToolThatThrewAnErrorReachedTheToolToo() {
        // The Throwable catch, not the RuntimeException one. It is the branch whose message
        // already asserts "any work it had already done has been done" — so it had better
        // not be reported as a gate failure.
        List<ToolInvocation> entered = new ArrayList<>();

        ToolOutcome outcome = activities(ToolGate.ALLOW_ALL, entered, inv -> {
            throw new StackOverflowError("the tool recursed");
        }).executeTool(PROPOSED);

        assertThat(entered).hasSize(1);
        assertThat(of(outcome)).isEqualTo(Disposition.THREW);
    }

    @Test
    void anApprovedParkedCallRan() {
        List<ToolInvocation> entered = new ArrayList<>();

        ToolOutcome outcome = activities((tool, invocation) -> GateResult.needsAPerson(
                        ApprovalNeeded.because("a person must look"), invocation),
                entered, inv -> ToolResult.ok("published"))
                .resumeTool(PROPOSED, new ApprovalVerdict("park-1", "t1",
                        ApprovalDecision.Kind.APPROVE, "", null, "alice"));

        assertThat(entered).hasSize(1);
        assertThat(of(outcome)).isEqualTo(Disposition.RAN);
    }

    @Test
    void anApprovedParkedCallWhoseToolThrewReachedTheTool() {
        // Found by mutation, and it took two tests to find because `gated` handed the call
        // to the tool from three places: deleting the marker from the resumed-park branch,
        // and separately from the drift branch, each survived every other test in this
        // module, so a tool that threw on a resumed call was reported as a broken gate.
        //
        // It is one test now because #163 merged those two branches into the one verdict
        // branch, for its own reason — they differed by a line and one of them had the line
        // wrong. Measured rather than assumed before deleting the second: a mutant dropping
        // the park disjunct from that branch's condition is killed by this test among
        // others, and a mutant dropping the allowed disjunct is killed by four tests in
        // DurableSettledCallTest and DurableTrustFloorTest and by nothing in this file. So
        // the drift route through the branch is pinned, and a second disposition test on it
        // was pinning nothing.
        List<ToolInvocation> entered = new ArrayList<>();

        ToolOutcome outcome = activities((tool, invocation) -> GateResult.needsAPerson(
                        ApprovalNeeded.because("a person must look"), invocation),
                entered, inv -> {
                    throw new IllegalStateException("the endpoint went away mid-publish");
                })
                .resumeTool(PROPOSED, new ApprovalVerdict("park-1", "t1",
                        ApprovalDecision.Kind.APPROVE, "", null, "alice"));

        assertThat(entered).hasSize(1);
        assertThat(of(outcome)).isEqualTo(Disposition.THREW);
    }

    // --- the wire ------------------------------------------------------------------

    @Test
    void theDispositionSurvivesHistory() throws Exception {
        // An activity result is written into history and read back on every replay, so a
        // component that does not round-trip records nothing at all.
        ObjectMapper mapper = DurableJson.objectMapper();
        List<ToolInvocation> entered = new ArrayList<>();
        ToolOutcome written = activities(ToolGate.ALLOW_ALL, entered, inv -> {
            throw new IllegalStateException("boom");
        }).executeTool(PROPOSED);

        String json = mapper.writeValueAsString(written);
        ToolOutcome restored = mapper.readValue(json, ToolOutcome.class);

        assertThat(json).contains("\"disposition\":\"THREW\"");
        assertThat(of(restored)).isEqualTo(Disposition.THREW);
    }

    @Test
    void aParkRecordsTheDispositionRatherThanLeavingItToBeInferred() throws Exception {
        // Found by mutation: dropping PARKED from ToolOutcome.parked survived every
        // assertion in this file, because settledAs() infers it from `awaiting`. That
        // inference exists for payloads written before the component did — it is not a
        // licence to stop writing it. History is read by people and by tools that never
        // call settledAs(), and for them an absent component is an absent fact.
        ObjectMapper mapper = DurableJson.objectMapper();
        List<ToolInvocation> entered = new ArrayList<>();
        ToolOutcome parked = activities((tool, invocation) -> GateResult.needsAPerson(
                        ApprovalNeeded.because("a person must look"), invocation),
                entered, inv -> ToolResult.ok("published")).executeTool(PROPOSED);

        assertThat(parked.disposition())
                .as("the component a reader of the raw payload sees was left null")
                .isEqualTo(Disposition.PARKED);
        assertThat(mapper.writeValueAsString(parked)).contains("\"disposition\":\"PARKED\"");
    }

    @Test
    void aPayloadFromBeforeThisComponentSaysItDoesNotKnow() {
        // The rolling-deploy case, and the one guess available is the wrong one to make:
        // an old completed payload cannot say whether it was a denial, an unknown tool, a
        // broken gate or a tool that ran, so it reports absent rather than RAN.
        // Every component added since is absent too, brokeAnInvariant (#129) included:
        // that is what "written before this component existed" means on the wire.
        ToolOutcome legacy = new ToolOutcome("published", false, Provenance.UNKNOWN, null,
                false, null, null, null);

        assertThat(legacy.settledAs())
                .as("an old payload was read as proof that the action happened")
                .isEqualTo(Optional.empty());
    }

    @Test
    void anOldPayloadCanStillProveAPark() {
        // The one fallback, and the only one recoverable: awaiting is filled by nothing
        // else, on a payload of any age.
        ToolOutcome legacyPark = new ToolOutcome("a person must look", true,
                Provenance.FIRST_PARTY,
                new PendingApproval(PROPOSED, ApprovalNeeded.because("a person must look")),
                false, null, null, null);

        assertThat(legacyPark.settledAs()).contains(Disposition.PARKED);
    }

    @Test
    void theRecordedComponentIsTrustedAheadOfTheParksFallback() {
        // settledAs states an order and no runner writes a payload where the two disagree,
        // so the order was documented and nothing held it — the same gap a mutant found in
        // settled(). Pinned on the record directly, the only place the disagreement can be
        // built: a payload from a future writer that records both.
        ToolOutcome both = new ToolOutcome("published", false, Provenance.UNKNOWN,
                new PendingApproval(PROPOSED, ApprovalNeeded.because("a person must look")),
                false, null, Disposition.RAN, false);

        assertThat(both.settledAs())
                .as("the park fallback outranked the component written for this purpose")
                .contains(Disposition.RAN);
    }
}
