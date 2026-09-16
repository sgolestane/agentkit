package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The durable runner's half of "a framework-authored refusal is first-party" (#272).
 *
 * <h2>Why the durable path needs its own half</h2>
 *
 * <p>The four runners are meant to agree, and the way they stop agreeing is that one of them
 * has no test. This one had none: the provenance of its unknown-tool and gate-denial
 * refusals was written down and never asserted, so both branches could be returned to
 * {@code ToolResult.error}'s {@code UNKNOWN} default with every durable test still green.
 * Found by planting exactly that, which is why this class exists.
 *
 * <h2>The consequence, not the field</h2>
 *
 * <p>{@code ToolActivitiesImpl} asks {@code floor.lowersOn(result.provenance())} and puts
 * the answer on the outcome, and {@code AgentWorkflowImpl} tightens the rest of the run on
 * it. So {@link ToolOutcome#lowered()} is the consequence here, and it is the same one the
 * in-process loop and itops' workflow runner have: a call that resolved nothing, entered
 * nothing and read nothing must not tighten policy for what comes after it.
 *
 * <p>{@link #aToolThatReallyReturnsSomebodyElsesWordsStillLowersTheFloor} is the
 * denominator. Without it every assertion here is satisfied by a floor that never lowers on
 * anything.
 */
class DurableRefusalsAreFirstPartyTest {

    private static final ToolInvocation PROPOSED =
            new ToolInvocation("t1", "publish", Map.of("text", "hello"));

    /** A floor that lowers on anything not declared FIRST_PARTY, tightening to a denial. */
    private static TrustFloor lowersOnAnythingUndeclared() {
        return TrustFloor.afterAnythingUndeclared(
                (tool, invocation) -> GateResult.allow(),
                (tool, invocation) -> GateResult.deny("tightened"));
    }

    private static SimpleToolRegistry registryDeclaring(Provenance provenance) {
        return new SimpleToolRegistry().register(
                FunctionTool.builder("publish", "Publishes text somewhere public")
                        .schema(Map.of("type", "object",
                                "properties", Map.of("text", Map.of("type", "string"))))
                        .sideEffects(SideEffects.EXTERNAL)
                        .provenance(provenance)
                        .handler(invocation -> ToolResult.ok("published"))
                        .build());
    }

    @Test
    void anUnknownToolDoesNotTightenThePolicy() {
        ToolOutcome outcome = new ToolActivitiesImpl(new SimpleToolRegistry(),
                lowersOnAnythingUndeclared()).executeTool(PROPOSED);

        assertThat(outcome.result().isError())
                .as("the denominator for this arm: the call really was refused")
                .isTrue();
        assertThat(outcome.lowered())
                .as("a call that resolved no tool lowered the run's trust floor")
                .isFalse();
    }

    @Test
    void aGateDenialDoesNotTightenThePolicy() {
        TrustFloor denying = TrustFloor.afterAnythingUndeclared(
                (tool, invocation) -> GateResult.deny("out of scope for this run"),
                (tool, invocation) -> GateResult.deny("tightened"));

        ToolOutcome outcome = new ToolActivitiesImpl(registryDeclaring(Provenance.THIRD_PARTY),
                denying).executeTool(PROPOSED);

        assertThat(outcome.result().content())
                .as("the denominator for this arm: the gate really did refuse")
                .contains("out of scope for this run");
        assertThat(outcome.lowered())
                .as("a denied call — nothing entered, nothing read — lowered the run's trust"
                        + " floor, on a tool whose declaration it never got as far as")
                .isFalse();
    }

    @Test
    void anApprovalThatAnswersAnotherCallDoesNotTightenThePolicy() {
        // The third refusal this class writes: a verdict came back for a different call, so
        // nothing was run and nothing was decided about this one.
        ToolOutcome outcome = new ToolActivitiesImpl(registryDeclaring(Provenance.THIRD_PARTY),
                lowersOnAnythingUndeclared())
                .resumeTool(PROPOSED, new ApprovalVerdict("park-1", "some-other-call",
                        ApprovalDecision.Kind.APPROVE, "", null, "alice"));

        assertThat(outcome.result().content())
                .as("the denominator for this arm: the mismatch really was refused")
                .contains("does not answer this call");
        assertThat(outcome.lowered())
                .as("a call nobody's approval answered lowered the run's trust floor")
                .isFalse();
    }

    @Test
    void aPersonsDenialOfAParkedCallDoesNotTightenThePolicy() {
        // The fourth refusal this class writes, and the one whose text is a person's reason
        // rather than a gate's — still the deployment's own words about a call that did not
        // run, and still nothing this run has read.
        TrustFloor parking = TrustFloor.afterAnythingUndeclared(
                (tool, invocation) -> GateResult.needsAPerson(
                        dev.agentkit.core.reliability.ApprovalNeeded.because(
                                "a person must look"), invocation),
                (tool, invocation) -> GateResult.deny("tightened"));

        ToolOutcome outcome = new ToolActivitiesImpl(registryDeclaring(Provenance.THIRD_PARTY),
                parking)
                .resumeTool(PROPOSED, new ApprovalVerdict("park-1", "t1",
                        ApprovalDecision.Kind.DENY, "absolutely not", null, "alice"));

        assertThat(outcome.result().content())
                .as("the denominator for this arm: the person's answer really was no")
                .contains("absolutely not");
        assertThat(outcome.lowered())
                .as("a call a person refused lowered the run's trust floor")
                .isFalse();
    }

    @Test
    void aToolThatReallyReturnsSomebodyElsesWordsStillLowersTheFloor() {
        ToolOutcome outcome = new ToolActivitiesImpl(registryDeclaring(Provenance.THIRD_PARTY),
                lowersOnAnythingUndeclared()).executeTool(PROPOSED);

        assertThat(outcome.lowered())
                .as("the floor no longer lowers on a tool that declares it returns somebody"
                        + " else's words, so the three assertions above say nothing")
                .isTrue();
    }
}
