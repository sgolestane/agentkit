package dev.agentkit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApprovalGateTest {

    /** A stand-in for tests whose gate does not read the tool; the parameter is mandatory. */
    private static final Tool ANY_TOOL = FunctionTool.builder("any", "any")
            .handler(i -> ToolResult.ok("ok")).build();

    private static ToolInvocation inv(String name, Map<String, Object> args) {
        return new ToolInvocation("i", name, args);
    }

    @Test
    void nonMatchingInvocationsAreAllowedUnchanged() {
        ToolGate gate = ToolGates.requireApproval(i -> i.name().equals("send"), Approver.DENY_ALL);
        GateResult result = gate.evaluate(ANY_TOOL, inv("read", Map.of()));
        assertThat(result.allowed()).isTrue();
        assertThat(result.replacement()).isEmpty();
    }

    @Test
    void approveAllAllowsMatchingInvocations() {
        ToolGate gate = ToolGates.requireApproval(i -> true, Approver.APPROVE_ALL);
        assertThat(gate.evaluate(ANY_TOOL, inv("send", Map.of())).allowed()).isTrue();
    }

    @Test
    void denyAllSurfacesASafeDefaultReason() {
        ToolGate gate = ToolGates.requireApproval(i -> true, Approver.DENY_ALL);
        GateResult result = gate.evaluate(ANY_TOOL, inv("send", Map.of()));
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("human approval");
    }

    @Test
    void customDenyReasonReachesTheGateResult() {
        Approver approver = (tool, i) -> ApprovalDecision.deny("recipient not on the allowlist");
        ToolGate gate = ToolGates.requireApproval(i -> true, approver);
        assertThat(gate.evaluate(ANY_TOOL, inv("send", Map.of())).reason()).isEqualTo("recipient not on the allowlist");
    }

    @Test
    void approveWithArgumentsReplacesTheInvocationPreservingIdAndName() {
        Approver approver = (tool, i) -> ApprovalDecision.approveWithArguments(Map.of("amount", 10));
        ToolGate gate = ToolGates.requireApproval(i -> true, approver);

        GateResult result = gate.evaluate(ANY_TOOL, inv("wire", Map.of("amount", 1_000_000)));

        assertThat(result.allowed()).isTrue();
        ToolInvocation replacement = result.replacement().orElseThrow();
        assertThat(replacement.id()).isEqualTo("i");
        assertThat(replacement.name()).isEqualTo("wire");
        assertThat(replacement.argument("amount")).isEqualTo(10);
    }

    @Test
    void argumentsAccessorRejectsNonEditDecisions() {
        assertThatThrownBy(() -> ApprovalDecision.approve().arguments())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ApprovalDecision.deny("no").arguments())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDeniedGateResultCannotCarryAReplacement() {
        // Was a runtime refusal by the canonical constructor. GateResult.Denied has one
        // component, the reason, so the state is unrepresentable and this asserts the
        // property that replaced the check: an approver's denial substitutes nothing.
        assertThat(ApprovalDecision.deny("no").toGateResult(inv("x", Map.of())).replacement())
                .isEmpty();
        assertThat(GateResult.deny("no")).isInstanceOf(GateResult.Denied.class);
        assertThat(GateResult.deny("no").replacement()).isEmpty();
    }

    @Test
    void allOfPreservesAnApprovalEditAndThreadsItToLaterGates() {
        // First gate caps the amount; a later gate must see the edited value (not the
        // original 1,000,000), and the combined result must carry the edit forward.
        Approver capping = (tool, i) -> ApprovalDecision.approveWithArguments(Map.of("amount", 10));
        AtomicReference<Object> seenByLaterGate = new AtomicReference<>();
        ToolGate observingLater = (tool, i) -> {
            seenByLaterGate.set(i.argument("amount"));
            return GateResult.allow();
        };

        ToolGate combined = ToolGates.allOf(
                ToolGates.requireApproval(i -> i.name().equals("wire"), capping),
                observingLater);

        GateResult result = combined.evaluate(ANY_TOOL, inv("wire", Map.of("amount", 1_000_000)));

        assertThat(seenByLaterGate.get()).isEqualTo(10); // later gate saw the edited args
        assertThat(result.replacement().orElseThrow().argument("amount")).isEqualTo(10);
    }

    @Test
    void allOfLetsTwoEditingGatesEachContributeWhenTheySecondBuildsOnTheFirst() {
        // Gate A caps the amount; gate B rebuilds from the invocation it receives
        // (which already has A's edit) and adds a note — so both edits survive.
        ToolGate capAmount = ToolGates.requireApproval(i -> true,
                (tool, i) -> ApprovalDecision.approveWithArguments(withEntry(i.arguments(), "amount", 10)));
        ToolGate addNote = ToolGates.requireApproval(i -> true,
                (tool, i) -> ApprovalDecision.approveWithArguments(withEntry(i.arguments(), "note", "reviewed")));

        GateResult result = ToolGates.allOf(capAmount, addNote)
                .evaluate(ANY_TOOL, inv("wire", Map.of("amount", 1_000_000, "recipient", "acme")));

        ToolInvocation effective = result.replacement().orElseThrow();
        assertThat(effective.argument("amount")).isEqualTo(10);       // A's edit survived
        assertThat(effective.argument("note")).isEqualTo("reviewed"); // B's edit applied
        assertThat(effective.argument("recipient")).isEqualTo("acme"); // untouched original
    }

    private static Map<String, Object> withEntry(Map<String, Object> base, String key, Object value) {
        var copy = new java.util.LinkedHashMap<String, Object>(base);
        copy.put(key, value);
        return copy;
    }

    @Test
    void allOfWithNoEditsReturnsAPlainAllow() {
        ToolGate combined = ToolGates.allOf(ToolGates.allowAll(), ToolGates.allowAll());
        assertThat(combined.evaluate(ANY_TOOL, inv("read", Map.of())).replacement()).isEmpty();
    }

    @Test
    void allOfStillShortCircuitsOnTheFirstDenial() {
        ToolGate combined = ToolGates.allOf(
                ToolGates.requireApproval(i -> true, (tool, i) -> ApprovalDecision.deny("nope")),
                ToolGates.allowAll());
        GateResult result = combined.evaluate(ANY_TOOL, inv("send", Map.of()));
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).isEqualTo("nope");
    }

    @Test
    void editedArgumentsActuallyReachTheToolInTheAgentLoop() {
        AtomicReference<Object> executedAmount = new AtomicReference<>();
        var registry = new SimpleToolRegistry().register(
                FunctionTool.builder("wire", "wires money")
                        .handler(i -> {
                            executedAmount.set(i.argument("amount"));
                            return ToolResult.ok("wired");
                        }).build());

        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "wire", Map.of("amount", 1_000_000)),
                FakeLlmClient.text("Done."));

        // The approver caps the amount at 10 instead of the model's 1,000,000.
        Approver capping = (tool, i) -> ApprovalDecision.approveWithArguments(Map.of("amount", 10));
        Agent agent = Agent.builder(llm, registry, AgentConfig.builder("m").maxSteps(5).build())
                .toolGate(ToolGates.requireApproval(i -> i.name().equals("wire"), capping))
                .build();

        AgentResult result = agent.run(Goal.of("wire a lot of money"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(executedAmount.get()).isEqualTo(10); // the edited value, not 1,000,000
    }

    @Test
    @DisplayName("an approver sees the tool, so a side-effect policy composes with approval")
    void anApproverSeesTheTool() {
        // "Require approval for anything not declared NONE" could not be written with the
        // shipped combinators: requireApproval dropped the tool, so allOf handed it to one
        // member of the policy and not the other. A person deciding also wants the
        // description and the declaration, which is most of what there is to go on.
        List<String> reviewed = new ArrayList<>();
        Tool writer = FunctionTool.builder("send", "sends mail")
                .sideEffects(SideEffects.EXTERNAL)
                .handler(i -> ToolResult.ok("sent")).build();

        ToolGate gate = ToolGates.requireApproval(i -> true, (tool, invocation) -> {
            reviewed.add(tool.name() + ":" + tool.sideEffects());
            return tool.sideEffects() == SideEffects.NONE
                    ? ApprovalDecision.approve()
                    : ApprovalDecision.deny("not declared side-effect free");
        });

        assertThat(gate.evaluate(writer, new ToolInvocation("c", "send", Map.of())).allowed())
                .isFalse();
        assertThat(reviewed).containsExactly("send:EXTERNAL");
    }


    @Test
    @DisplayName("a confirmation handler sees the tool as well")
    void aConfirmationHandlerSeesTheTool() {
        // The sibling combinator forwards the tool through its own lambda, and that lambda
        // is exactly the shape that discarded it before. Replacing the forward with null
        // left the whole reactor green, so this pins the half the approval test did not.
        List<String> reviewed = new ArrayList<>();
        Tool writer = FunctionTool.builder("send", "sends mail")
                .sideEffects(SideEffects.EXTERNAL)
                .handler(i -> ToolResult.ok("sent")).build();

        ToolGate gate = ToolGates.requireConfirmation(i -> true, (tool, invocation) -> {
            reviewed.add(tool.name() + ":" + tool.sideEffects());
            return tool.sideEffects() == SideEffects.NONE;
        });

        assertThat(gate.evaluate(writer, inv("send", Map.of())).allowed()).isFalse();
        assertThat(reviewed).containsExactly("send:EXTERNAL");
    }

    @Test
    @DisplayName("a null tool is refused at the boundary rather than reaching a policy")
    void aNullToolIsRejected() {
        // The interface says the tool is never null and no framework caller can produce
        // one, but the method is public. Left unguarded, the answer depended on which gate
        // you picked — a name-deciding composite allowed, a tool-deciding one threw — and
        // that difference should not be discoverable by accident on a fail-closed policy.
        assertThatThrownBy(() -> ToolGates.readOnly().evaluate(null, inv("send", Map.of())))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ToolGates.requireApproval(i -> true, Approver.APPROVE_ALL)
                .evaluate(null, inv("send", Map.of())))
                .isInstanceOf(NullPointerException.class);
    }

    @org.junit.jupiter.api.Test
    void anApproversEditedArgumentsCannotChangeAfterTheDecision() {
        // The mutant that survived: ApprovalDecision reverting to a shallow copy passed the
        // whole reactor. What the *tool* receives is protected either way — toGateResult
        // builds a ToolInvocation, which freezes again — so the property this pins is the
        // narrower and true one: the decision's own public accessor is a snapshot, and an
        // approver holding a reference cannot change what a caller reads back off it.
        var edited = new java.util.ArrayList<>(java.util.List.of("/tmp/harmless.txt"));
        ApprovalDecision decision = ApprovalDecision.approveWithArguments(
                java.util.Map.of("paths", edited));

        edited.set(0, "/etc/shadow");

        org.assertj.core.api.Assertions.assertThat(decision.arguments().get("paths"))
                .isEqualTo(java.util.List.of("/tmp/harmless.txt"));
    }

    @Test
    void aLambdaApproverIsPresumedToWaitForAPerson() {
        // The presumption itself, since withoutWaiting is only meaningful against it. This
        // is fail-closed and deliberate: an approver that does reach a person is the case
        // that hurts if it is assumed not to.
        Approver lambda = (tool, i) -> ApprovalDecision.approve();

        assertThat(lambda.waitsForAHuman()).isTrue();
        assertThat(ToolGates.requireApproval(i -> true, lambda).waitsForAHuman()).isTrue();
    }

    @Test
    void withoutWaitingDeclaresTheNegativeALambdaCouldNotOtherwiseSay() {
        // #290. Before this, saying "this one does not wait" meant implementing Approver as
        // a class purely to override one method, which is enough friction that the honest
        // answer went unsaid -- and since #283 an unsaid answer is a refused registration.
        Approver approver = Approver.withoutWaiting(
                (tool, i) -> ApprovalDecision.deny("over the unattended limit"));

        assertThat(approver.waitsForAHuman()).isFalse();
        assertThat(ToolGates.requireApproval(i -> true, approver).waitsForAHuman()).isFalse();
    }

    @Test
    void withoutWaitingChangesOnlyTheDeclarationAndNotTheDecision() {
        // A declaration that also altered the answer would be a policy change wearing a
        // property's name. All three decision shapes go through unchanged.
        assertThat(ToolGates.requireApproval(i -> true,
                        Approver.withoutWaiting((tool, i) -> ApprovalDecision.approve()))
                .evaluate(ANY_TOOL, inv("send", Map.of())).allowed()).isTrue();

        GateResult denied = ToolGates.requireApproval(i -> true,
                        Approver.withoutWaiting((tool, i) -> ApprovalDecision.deny("no wires")))
                .evaluate(ANY_TOOL, inv("send", Map.of()));
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reason()).isEqualTo("no wires");

        GateResult edited = ToolGates.requireApproval(i -> true,
                        Approver.withoutWaiting((tool, i) ->
                                ApprovalDecision.approveWithArguments(Map.of("amount", 10))))
                .evaluate(ANY_TOOL, inv("wire", Map.of("amount", 1_000_000)));
        assertThat(edited.replacement().orElseThrow().argument("amount")).isEqualTo(10);
    }

    @Test
    void withoutWaitingPassesTheToolThroughLikeAnyOtherApprover() {
        // The tool is half of what a policy decides from -- Tool.sideEffects() and the
        // description -- and a factory that dropped it would quietly make the shipped
        // non-blocking path weaker than the hand-written one.
        AtomicReference<Tool> seen = new AtomicReference<>();
        Approver approver = Approver.withoutWaiting((tool, i) -> {
            seen.set(tool);
            return ApprovalDecision.approve();
        });

        approver.review(ANY_TOOL, inv("send", Map.of()));

        assertThat(seen.get()).isSameAs(ANY_TOOL);
    }

    @Test
    void withoutWaitingRefusesNullRatherThanAnsweringWithOne() {
        assertThatThrownBy(() -> Approver.withoutWaiting(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Approver.withoutWaiting((tool, i) -> null)
                        .review(ANY_TOOL, inv("send", Map.of())))
                .as("a review answering null would reach ApprovalDecision.toGateResult and"
                        + " fail there, one frame further from the mistake")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theShippedConstantsAlreadySaidTheyDoNotWait() {
        // The two cases that had the answer before there was a way to spell it, so the
        // factory is an addition and not a correction.
        assertThat(Approver.DENY_ALL.waitsForAHuman()).isFalse();
        assertThat(Approver.APPROVE_ALL.waitsForAHuman()).isFalse();
    }
}
