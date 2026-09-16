package dev.agentkit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The gate outcome that says a person must decide (#101).
 *
 * <p>The property under test everywhere here is the one that made this addition safe: a
 * parked result reports {@code allowed() == false}. A runner that has never heard of
 * parking — one outside this repository, or one inside it that a later change forgets to
 * update — refuses the call. Every test that pins a park also pins that fallback, because
 * the fallback is the whole reason a third outcome could be added to a boolean at an
 * authorization boundary at all.
 */
class ParkedApprovalTest {

    private static final Tool PUBLISH = FunctionTool.builder("publish", "Publishes text")
            .sideEffects(SideEffects.EXTERNAL)
            .handler(invocation -> ToolResult.ok("published"))
            .build();

    private static ToolInvocation call() {
        return new ToolInvocation("t1", "publish", Map.of("text", "the payload"));
    }

    @Test
    void aParkedResultIsNotAllowed() {
        GateResult parked = GateResult.needsAPerson(ApprovalNeeded.because("somebody must sign off"));

        assertThat(parked.allowed())
                .as("a runner that only knows allow/deny must refuse a parked call, not run it")
                .isFalse();
        assertThat(parked.awaiting()).isPresent();
    }

    @Test
    void aParkedResultSaysWhyToAReaderThatOnlyKnowsAllowAndDeny() {
        // The duplication is deliberate: an unaware runner reads reason() and nothing else,
        // and "" would have it refuse the call with no explanation at all.
        GateResult parked = GateResult.needsAPerson(ApprovalNeeded.because("somebody must sign off"));

        assertThat(parked.reason()).isEqualTo("somebody must sign off");
    }

    @Test
    void aResultCannotBothRunAndWaitForSomebody() {
        // This was an assertThatThrownBy against the canonical constructor, which refused
        // `allowed && awaiting.isPresent()` with a sentence about the two contradicting
        // each other at the one place a runner reads to decide whether the tool runs. The
        // constructor is gone and so is the state: GateResult.Allowed has no ApprovalNeeded
        // component to put one in, and GateResult.NeedsAPerson answers false to allowed().
        // What can still be checked is that the property holds across the whole hierarchy,
        // so the switch below is the test and the assertions are what it asserts.
        for (GateResult result : everyShape()) {
            boolean waits = switch (result) {
                case GateResult.Allowed allowed -> false;
                case GateResult.Denied denied -> false;
                case GateResult.NeedsAPerson needs -> true;
            };
            assertThat(result.allowed() && waits)
                    .as("%s both runs the call and waits for somebody", result)
                    .isFalse();
            assertThat(result.awaiting().isPresent())
                    .as("%s disagrees with its own type about whether a person is owed an"
                            + " answer", result)
                    .isEqualTo(waits);
        }
    }

    @Test
    void theThreeFactoriesStillMeanWhatTheyMeant() {
        // The allow/deny constructors this replaced were the compatibility shims #158 says
        // should go with the reshape: they let a `!allowed` caller keep compiling unchanged,
        // which is how two of the defects in GateResult's javadoc survived to review. The
        // factories are what every caller in this repository used anyway.
        assertThat(GateResult.deny("no").awaiting()).isEmpty();
        assertThat(GateResult.deny("no").allowed()).isFalse();
        assertThat(GateResult.deny("no").reason()).isEqualTo("no");
        assertThat(GateResult.allow().awaiting()).isEmpty();
        assertThat(GateResult.allow().allowed()).isTrue();
        assertThat(GateResult.allow().reason()).isEmpty();
        assertThat(GateResult.allow().replacement()).isEmpty();
    }

    /**
     * One of each arm, so a switch below is exhaustive over what a gate can actually
     * return rather than over what this test remembered to build.
     */
    private static java.util.List<GateResult> everyShape() {
        return java.util.List.of(
                GateResult.allow(),
                GateResult.allowWith(call()),
                GateResult.deny("no"),
                GateResult.needsAPerson(ApprovalNeeded.because("sign off")),
                GateResult.needsAPerson(ApprovalNeeded.because("sign off"), call()));
    }

    @Test
    void anUnexaminedActionIsNotClaimedReversible() {
        assertThat(ApprovalNeeded.because("sign off").reversible())
                .as("false reads as 'not claimed reversible', which is the answer that makes"
                        + " a reviewer look harder")
                .isFalse();
        assertThat(ApprovalNeeded.because("sign off").thatCanBeUndone().reversible()).isTrue();
    }

    // --- the gate ----------------------------------------------------------------

    @Test
    void aParkingGateDoesNotWaitForAnybody() {
        ToolGate gate = ToolGates.parkForApproval(inv -> true, ApprovalNeeded.because("sign off"));

        assertThat(gate.waitsForAHuman())
                .as("the durable runner refuses a gate that waits; this one returns at once,"
                        + " which is the entire point of the outcome")
                .isFalse();
    }

    @Test
    void aParkingGateLetsThroughWhatItDoesNotMatch() {
        ToolGate gate = ToolGates.parkForApproval(
                inv -> inv.name().equals("delete"), ApprovalNeeded.because("sign off"));

        assertThat(gate.evaluate(PUBLISH, call()).allowed()).isTrue();
    }

    @Test
    void aParkingGateStopsWhatItMatchesAndSaysWhy() {
        ToolGate gate = ToolGates.parkForApproval(inv -> inv.name().equals("publish"),
                (tool, invocation) -> ApprovalNeeded
                        .because("publishing is " + tool.sideEffects() + " and needs a person")
                        .withEffect("The text becomes publicly visible."));

        GateResult decision = gate.evaluate(PUBLISH, call());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.awaiting().orElseThrow().reason())
                .as("the gate is handed the tool, so a reason can speak to what it does")
                .contains("EXTERNAL");
        assertThat(decision.awaiting().orElseThrow().effect()).isNotBlank();
    }

    // --- composition -------------------------------------------------------------

    @Test
    void aParkSurvivesBeingComposed() {
        ToolGate composed = ToolGates.allOf(
                ToolGates.allowAll(),
                ToolGates.parkForApproval(inv -> true, ApprovalNeeded.because("sign off")));

        GateResult decision = composed.evaluate(PUBLISH, call());

        assertThat(decision.awaiting())
                .as("a composite that flattened a park into a plain denial would lose the"
                        + " only signal a runner has that somebody can still answer")
                .isPresent();
        assertThat(decision.allowed()).isFalse();
    }

    @Test
    void aCompositeThatCanParkDoesNotClaimToWaitForAnybody() {
        ToolGate composed = ToolGates.allOf(
                ToolGates.readOnly(),
                ToolGates.parkForApproval(inv -> true, ApprovalNeeded.because("sign off")));

        assertThat(composed.waitsForAHuman())
                .as("composing a parking gate must not make the whole policy unusable on the"
                        + " durable runner, which is the runner it was built for")
                .isFalse();
    }

    @Test
    void aDenialBeforeAParkStopsTheChainAndNobodyIsAsked() {
        // allOf short-circuits on the first stop of any kind. A member that would deny
        // outright, placed first, means nobody is asked a question they cannot answer.
        List<String> asked = new java.util.ArrayList<>();
        ToolGate composed = ToolGates.allOf(
                ToolGates.denyTools(java.util.Set.of("publish")),
                ToolGates.parkForApproval(inv -> {
                    asked.add(inv.name());
                    return true;
                }, ApprovalNeeded.because("sign off")));

        GateResult decision = composed.evaluate(PUBLISH, call());

        assertThat(decision.awaiting()).isEmpty();
        assertThat(decision.allowed()).isFalse();
        assertThat(asked)
                .as("the later member never ran, so a gate that pages somebody did not page"
                        + " them for a call policy had already refused")
                .isEmpty();
    }

    @Test
    void aDenialAfterAParkBeatsTheParkAndNobodyIsAsked() {
        // The chain runs past a park, which is the asymmetry the composition rule turns on.
        // A denial is an answer, so it stops the chain. A park is a question, and a later
        // member that refuses the call outright has already answered it.
        //
        // The first version of this returned the park here, on the reasoning that no member
        // should run that would not have run before. That bought a hole: measured through
        // ToolActivitiesImpl with allOf(park, denyWires), a proposal of 5,000,000 parked,
        // was approved, and RAN — because the resume re-evaluated the composite, short-
        // circuited at the same parking member, and never reached the denial.
        List<String> reached = new java.util.ArrayList<>();
        ToolGate composed = ToolGates.allOf(
                ToolGates.parkForApproval(inv -> true, ApprovalNeeded.because("sign off")),
                (tool, invocation) -> {
                    reached.add(invocation.name());
                    return GateResult.deny("publishing is switched off entirely");
                });

        GateResult decision = composed.evaluate(PUBLISH, call());

        assertThat(decision.awaiting())
                .as("asking somebody about a call a later member refuses outright is a"
                        + " question whose answer cannot matter — and, before this, could")
                .isEmpty();
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("publishing is switched off entirely");
        assertThat(reached).containsExactly("publish");
    }

    @Test
    void anEditMadeBeforeAParkTravelsWithTheQuestion() {
        // Otherwise the reviewer is shown one call and another runs. Measured before this:
        // allOf(narrowTo100k, park) on a proposal of 5,000,000 showed the reviewer
        // 5,000,000 and ran the tool with 5,000,000, losing the narrowing at both ends —
        // #104's failure arriving through a new door.
        ToolGate composed = ToolGates.allOf(
                (tool, invocation) -> GateResult.allowWith(new ToolInvocation(
                        invocation.id(), invocation.name(), Map.of("text", "narrowed"))),
                ToolGates.parkForApproval(inv -> true, ApprovalNeeded.because("sign off")));

        GateResult decision = composed.evaluate(PUBLISH, call());

        assertThat(decision.awaiting()).isPresent();
        assertThat(decision.effectiveFor(call()).arguments())
                .containsEntry("text", "narrowed");
    }

    @Test
    void aParkingMembersOwnEditTravelsWithItsOwnQuestion() {
        // The door the test above does not cover, and the one that was open. There, an
        // EARLIER member narrows and a LATER member parks; allOf recorded the edit in the
        // allowed arm and carried it. Here the parking member is the one that narrows —
        // which is exactly what GateResult.needsAPerson(why, replacement) is for — and the
        // park arm applied the substitution without recording it, so the composite handed
        // back the proposal. Measured before this change, on a proposal of 5,000,000:
        //
        //   the gate alone settled on      : {amount=100000}
        //   the same gate inside allOf     : {amount=5000000}
        //   the member after it was judged : {amount=100000}
        //
        // Every one of those three lines is wrong together: the later member cleared a
        // narrowed call, the reviewer is shown the un-narrowed one, and on approval the
        // un-narrowed one runs. That is #104's failure a third time, and it survived
        // because `edited` was a second encoding of "is there a replacement" that one arm
        // could forget to write. It is now read off the accumulated call itself.
        ToolInvocation proposed =
                new ToolInvocation("t1", "publish", Map.of("text", "the whole payload"));
        ToolGate narrowThenPark = (tool, invocation) -> GateResult.needsAPerson(
                ApprovalNeeded.because("sign off"),
                new ToolInvocation(invocation.id(), invocation.name(),
                        Map.of("text", "narrowed")));
        List<String> judged = new java.util.ArrayList<>();
        ToolGate later = (tool, invocation) -> {
            judged.add(String.valueOf(invocation.argument("text")));
            return GateResult.allow();
        };

        GateResult alone = narrowThenPark.evaluate(PUBLISH, proposed);
        GateResult composed = ToolGates.allOf(narrowThenPark, later).evaluate(PUBLISH, proposed);

        assertThat(alone.effectiveFor(proposed).arguments())
                .as("the denominator: the same gate, uncomposed")
                .containsEntry("text", "narrowed");
        assertThat(composed.awaiting()).isPresent();
        assertThat(composed.effectiveFor(proposed).arguments())
                .as("composing the gate lost the narrowing it parked with, so the reviewer"
                        + " is shown a call policy had already reduced")
                .containsEntry("text", "narrowed");
        assertThat(judged)
                .as("the later member judged something other than what the composite"
                        + " settled on")
                .containsExactly("narrowed");
    }

    @Test
    void aCompositeThatEditsNothingSaysSo() {
        // The other side of reading the edit off the call rather than off a flag. A chain
        // of members that all allow plainly must report no replacement, or every runner
        // logs an edit that did not happen and every observer records one.
        ToolGate composed = ToolGates.allOf(ToolGates.allowAll(), ToolGates.allowAll());

        GateResult decision = composed.evaluate(PUBLISH, call());

        assertThat(decision.replacement()).isEmpty();
        assertThat(decision.effectiveFor(call())).isEqualTo(call());
    }

    @Test
    void aParkIsTheOneStopThatMayCarryAReplacement() {
        // The invariant that made this possible, and its edge. A denial still may not.
        assertThat(GateResult.needsAPerson(ApprovalNeeded.because("sign off"), call())
                .replacement()).isPresent();
        // The edge was an assertThatThrownBy against the canonical constructor. A denial
        // has no replacement component now, so there is nothing to throw about: the check
        // that remains is that every not-allowed shape which is not a park carries none.
        for (GateResult result : everyShape()) {
            if (!result.allowed() && result.awaiting().isEmpty()) {
                assertThat(result.replacement())
                        .as("%s is a denial carrying a call it says will never run", result)
                        .isEmpty();
            }
        }
    }

    @Test
    void aCompositeAsksOneQuestionAtATime() {
        ToolGate composed = ToolGates.allOf(
                ToolGates.parkForApproval(inv -> true, ApprovalNeeded.because("the first question")),
                ToolGates.parkForApproval(inv -> true, ApprovalNeeded.because("the second question")));

        GateResult decision = composed.evaluate(PUBLISH, call());

        assertThat(decision.awaiting().orElseThrow().reason())
                .as("the first answer may change what the second member decides, so asking"
                        + " both at once asks about a call that may not survive the first")
                .isEqualTo("the first question");
    }

    // --- what a partial wire payload does ------------------------------------------

    @Test
    void anApprovalWithNothingStatedIsShownRatherThanThrown() {
        // These records ride inside a Temporal activity's return value, which a workflow
        // re-reads from history on every replay. A constructor that refuses a payload
        // written before a component existed fails the workflow task, which Temporal
        // retries forever — the run stalls rather than fails, which is worse than either.
        // ToolOutcome and DurableAgentOptions already said so; these two did the opposite.
        ApprovalNeeded coerced = new ApprovalNeeded(null, null, false);

        assertThat(coerced.reason()).isNotBlank();
        assertThat(coerced.effect()).isEmpty();
        assertThat(new PendingApproval(null, null).invocation()).isNotNull();
    }

    @Test
    void aGateAuthorStillFailsLoudlyOnABlankReason() {
        // The other side of the same coin: coercion is for Jackson reading history, not for
        // somebody at a keyboard. A blank reason is a mistake worth catching before it
        // reaches a reviewer's screen.
        assertThatThrownBy(() -> ApprovalNeeded.because("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
