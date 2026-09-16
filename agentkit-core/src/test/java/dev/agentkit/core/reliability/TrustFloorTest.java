package dev.agentkit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.tool.Provenance;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The policy object behind "after you read the web, you cannot write" (#122).
 *
 * <p>What is under test here is the <em>shape</em>: which provenances lower a floor, what a
 * floor refuses to be, and that both policies are asked whether they block. Whether a
 * lowered floor actually stops a tool is asserted against the tools themselves, in
 * {@code AgentTrustFloorTest} and {@code DurableTrustFloorTest}.
 */
class TrustFloorTest {

    private static final ToolGate READ_ONLY = ToolGates.readOnly();
    private static final ToolGate OPEN = ToolGate.ALLOW_ALL;

    @Test
    void theDeclaredReadingLowersOnlyForAToolThatSaysSo() {
        TrustFloor floor = TrustFloor.afterThirdParty(OPEN, READ_ONLY);

        assertThat(floor.lowersOn(Provenance.THIRD_PARTY)).isTrue();
        assertThat(floor.lowersOn(Provenance.UNKNOWN))
                .as("a floor cannot act on a fact nobody has stated; that is a real state of"
                        + " affairs and not a bug in this reading")
                .isFalse();
        assertThat(floor.lowersOn(Provenance.FIRST_PARTY)).isFalse();
    }

    @Test
    void theStrictReadingCountsUndeclaredToo() {
        TrustFloor floor = TrustFloor.afterAnythingUndeclared(OPEN, READ_ONLY);

        assertThat(floor.lowersOn(Provenance.UNKNOWN)).isTrue();
        assertThat(floor.lowersOn(Provenance.THIRD_PARTY)).isTrue();
        assertThat(floor.lowersOn(Provenance.FIRST_PARTY))
                .as("your own words are the one thing that cannot lower a floor, or every"
                        + " run lowers on its first tool call")
                .isFalse();
    }

    @Test
    void anAbsentProvenanceIsReadAsUndeclaredRatherThanThrowing() {
        // ToolResult coerces a null provenance to UNKNOWN, and so does this — a policy
        // object that throws at an authorization boundary because a field was absent turns
        // one problem into a different one.
        assertThat(TrustFloor.afterAnythingUndeclared(OPEN, READ_ONLY).lowersOn(null)).isTrue();
        assertThat(TrustFloor.afterThirdParty(OPEN, READ_ONLY).lowersOn(null)).isFalse();
    }

    @Test
    void aFloorNothingLowersIsRefused() {
        assertThatThrownBy(() -> new TrustFloor(OPEN, READ_ONLY, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a floor");
    }

    @Test
    void aFloorYourOwnWordsLowerIsRefused() {
        // It would lower on the first tool call of every run, which is the same as wiring
        // the tightened gate directly — and reads at every call site like a control that
        // sometimes applies.
        assertThatThrownBy(() -> new TrustFloor(OPEN, READ_ONLY,
                Set.of(Provenance.FIRST_PARTY, Provenance.THIRD_PARTY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FIRST_PARTY");
    }

    @Test
    void bothPoliciesAreAskedWhetherTheyWaitForAPerson() {
        // A run reaches both. A floor whose *tightened* gate blocks would otherwise be
        // accepted by the durable runner and kill the run the first time anything came
        // back from the web.
        ToolGate blocking = ToolGates.requireApproval(invocation -> true,
                (tool, invocation) -> ApprovalDecision.approve());

        assertThat(TrustFloor.afterThirdParty(OPEN, blocking).waitsForAHuman()).isTrue();
        assertThat(TrustFloor.afterThirdParty(blocking, OPEN).waitsForAHuman()).isTrue();
        assertThat(TrustFloor.afterThirdParty(OPEN, READ_ONLY).waitsForAHuman()).isFalse();
    }

    @Test
    void aParkingFloorIsStillUsableOnTheDurableRunner() {
        // parkForApproval returns at once, so a floor built on it does not block — which is
        // what keeps "tighten to needing a person" available on the runner that refuses a
        // gate that waits (#101).
        TrustFloor floor = TrustFloor.afterThirdParty(OPEN, ToolGates.parkForApproval(
                invocation -> true, ApprovalNeeded.because("this run has read the web")));

        assertThat(floor.waitsForAHuman()).isFalse();
    }

    @Test
    void theFloorChoosesBetweenTheTwoPoliciesAndNothingElse() {
        TrustFloor floor = TrustFloor.afterThirdParty(OPEN, READ_ONLY);

        assertThat(floor.inForce(false)).isSameAs(OPEN);
        assertThat(floor.inForce(true)).isSameAs(READ_ONLY);
    }

    @Test
    void aFloorThatTightensToTheSamePolicyIsRefused() {
        // The shape that shipped a live defect: a trigger with nothing to switch to reports
        // that it lowered — a false log line at an authorization boundary, a wrong audit
        // payload in history, and durably a change of activity type mid-run — while
        // changing nothing. The framework used to build exactly this to mean "no floor".
        assertThatThrownBy(() -> TrustFloor.afterThirdParty(OPEN, OPEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("none");
    }

    @Test
    void noFloorIsAShapeThisTypeCanBeIn() {
        TrustFloor none = TrustFloor.none(OPEN);

        assertThat(none.exists()).isFalse();
        assertThat(none.lowersOn(Provenance.THIRD_PARTY))
                .as("nothing lowers it, so nothing tells a caller a floor engaged")
                .isFalse();
        assertThat(none.inForce(true)).isSameAs(OPEN);
    }

    @Test
    void bothPoliciesMustGuaranteeReadOnly() {
        // The AND, in the direction that tells it from ordinarily.guaranteesReadOnly(). The
        // only existing coverage used (ALLOW_ALL, readOnly), where the first conjunct
        // already fails and the second is never consulted — so a mutant reducing this to
        // the first gate survived the whole core suite.
        //
        // Two read-only gates rather than one twice: one twice is the decorative shape the
        // constructor refuses, and refusing it is itself correct.
        assertThat(TrustFloor.afterThirdParty(ToolGates.readOnly(), ToolGates.readOnly())
                .guaranteesReadOnly()).isTrue();
        assertThat(TrustFloor.afterThirdParty(READ_ONLY, OPEN).guaranteesReadOnly())
                .as("a run reaches both, and the writes a loose tightened policy permits are"
                        + " writes")
                .isFalse();
        assertThat(TrustFloor.afterThirdParty(OPEN, READ_ONLY).guaranteesReadOnly()).isFalse();
    }

    @Test
    void aFloorThatCountsSilenceMustAlsoCountAnAdmission() {
        // {UNKNOWN} alone inverts the intent: a tool that *says* it returns somebody else's
        // words would not lower it, while one that said nothing would.
        assertThatThrownBy(() -> new TrustFloor(OPEN, READ_ONLY, Set.of(Provenance.UNKNOWN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("THIRD_PARTY");
    }
}
