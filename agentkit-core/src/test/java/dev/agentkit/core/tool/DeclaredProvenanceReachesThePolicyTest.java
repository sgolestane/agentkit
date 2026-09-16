package dev.agentkit.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.collab.Blackboard;
import dev.agentkit.core.collab.BlackboardTools;
import dev.agentkit.core.memory.MemoryTools;
import dev.agentkit.core.memory.WorkingMemory;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.reliability.TrustFloor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@link Provenance#FIRST_PARTY} declaration reaches the result, and therefore reaches a
 * policy (#161).
 *
 * <p>{@code ToolResult.attributedTo} promises that "an undeclared result inherits the tool's
 * declaration", and {@code ToolResult}'s class javadoc promises that {@code UNKNOWN} on a
 * result means "ask the tool". Both were true of {@code THIRD_PARTY} and false of
 * {@code FIRST_PARTY}: a result that had not spoken collapsed a {@code FIRST_PARTY} tool
 * back to {@code UNKNOWN}, so the declaration reached no reader at all.
 *
 * <p>Which matters because #161 asks whether a floor can key on this enum when it was
 * calibrated for fencing. The answer is yes, provided a deployment can say which tools are
 * inside <em>its</em> trust boundary — and the lever for that, {@code Tools.withProvenance},
 * did not work in the one direction anybody would reach for it.
 */
class DeclaredProvenanceReachesThePolicyTest {

    private static final TrustFloor STRICT = TrustFloor.afterAnythingUndeclared(
            ToolGate.ALLOW_ALL, ToolGates.readOnly());
    private static final TrustFloor LENIENT = TrustFloor.afterThirdParty(
            ToolGate.ALLOW_ALL, ToolGates.readOnly());

    private static Tool tool(Provenance declared, Provenance said) {
        FunctionTool.Builder b = FunctionTool.builder("t", "d").provenance(declared);
        return b.handler(inv -> said == Provenance.UNKNOWN
                        ? ToolResult.ok("x")
                        : ToolResult.from(said, "x"))
                .build();
    }

    private static Provenance attributed(Provenance declared, Provenance said) {
        Tool t = tool(declared, said);
        return t.execute(new ToolInvocation("i", "t", Map.of())).attributedTo(t).provenance();
    }

    @Test
    @DisplayName("a result that says nothing inherits the tool's declaration, first-party included")
    void aFirstPartyDeclarationSurvivesAnOrdinaryOk() {
        // The defect, in one line. ToolResult.ok produces UNKNOWN — "ask the tool" — and
        // the tool was never asked when its answer was FIRST_PARTY.
        assertThat(attributed(Provenance.FIRST_PARTY, Provenance.UNKNOWN))
                .as("a tool declared its content is the deployment's own words and the "
                        + "result said nobody had declared anything")
                .isEqualTo(Provenance.FIRST_PARTY);
    }

    @Test
    @DisplayName("the whole nine-cell table, so the one cell that moved is the only one")
    void everyCombinationIsPinned() {
        // Written out rather than asserted case by case, because the change is one cell of
        // nine and a reader has to be able to see that the other eight did not move. Read
        // as: attributed(what the TOOL declares, what the RESULT said).
        assertThat(attributed(Provenance.UNKNOWN, Provenance.UNKNOWN))
                .isEqualTo(Provenance.UNKNOWN);
        assertThat(attributed(Provenance.UNKNOWN, Provenance.FIRST_PARTY))
                .as("a result may not talk an undeclared tool into a declaration")
                .isEqualTo(Provenance.UNKNOWN);
        assertThat(attributed(Provenance.UNKNOWN, Provenance.THIRD_PARTY))
                .isEqualTo(Provenance.THIRD_PARTY);

        assertThat(attributed(Provenance.FIRST_PARTY, Provenance.UNKNOWN))
                .as("the cell that moved")
                .isEqualTo(Provenance.FIRST_PARTY);
        assertThat(attributed(Provenance.FIRST_PARTY, Provenance.FIRST_PARTY))
                .isEqualTo(Provenance.FIRST_PARTY);
        assertThat(attributed(Provenance.FIRST_PARTY, Provenance.THIRD_PARTY))
                .as("narrowing still works: a file reader knows a user's upload from its config")
                .isEqualTo(Provenance.THIRD_PARTY);

        assertThat(attributed(Provenance.THIRD_PARTY, Provenance.UNKNOWN))
                .isEqualTo(Provenance.THIRD_PARTY);
        assertThat(attributed(Provenance.THIRD_PARTY, Provenance.FIRST_PARTY))
                .as("a tool may not talk its way out of its own declaration")
                .isEqualTo(Provenance.THIRD_PARTY);
        assertThat(attributed(Provenance.THIRD_PARTY, Provenance.THIRD_PARTY))
                .isEqualTo(Provenance.THIRD_PARTY);
    }

    @Test
    @DisplayName("the framework's own first-party tools no longer lower the strict floor")
    void rememberAndPostNoteDoNotLowerAFloor() {
        // Both declare FIRST_PARTY and both return ToolResult.ok, so both were labelled
        // UNKNOWN — and afterAnythingUndeclared, whose contract is "lowers for anything not
        // declared FIRST_PARTY", lowered on them. remember's entire output is the word
        // "Noted."
        Tool remember = MemoryTools.rememberTool(new WorkingMemory());
        ToolResult noted = remember.execute(new ToolInvocation("i", remember.name(),
                Map.of("note", "a"))).attributedTo(remember);
        assertThat(noted.provenance()).isEqualTo(Provenance.FIRST_PARTY);
        assertThat(STRICT.lowersOn(noted.provenance()))
                .as("a run lost its write capability to the framework saying 'Noted.'")
                .isFalse();

        Tool post = BlackboardTools.postNoteTool(new Blackboard(), "me");
        ToolResult posted = post.execute(new ToolInvocation("i", post.name(),
                Map.of("topic", "t", "content", "c"))).attributedTo(post);
        assertThat(posted.provenance()).isEqualTo(Provenance.FIRST_PARTY);
        assertThat(STRICT.lowersOn(posted.provenance())).isFalse();
    }

    @Test
    @DisplayName("a deployment can put a tool it did not author inside its own boundary")
    void withProvenanceIsTheLeverThatAnswers161() {
        // #161's own example: read_skill over a bundle the deployment ships and versions.
        // The declaration is right for the question Provenance asks — a skill bundle is not
        // the deployment's prose — and wrong for the question a floor asks, because nobody
        // outside can write it. Tools.withProvenance is where that judgement belongs: only
        // the deployment knows whether its bundle is attacker-writable.
        Tool vendored = FunctionTool.builder("read_skill", "d")
                .provenance(Provenance.THIRD_PARTY)
                .handler(inv -> ToolResult.ok("the bundle's text"))
                .build();

        ToolResult asShipped = vendored.execute(new ToolInvocation("i", "read_skill", Map.of()))
                .attributedTo(vendored);
        assertThat(asShipped.provenance()).isEqualTo(Provenance.THIRD_PARTY);
        assertThat(LENIENT.lowersOn(asShipped.provenance())).isTrue();

        Tool ours = Tools.withProvenance(vendored, Provenance.FIRST_PARTY);
        ToolResult restated = ours.execute(new ToolInvocation("i", "read_skill", Map.of()))
                .attributedTo(ours);
        assertThat(restated.provenance())
                .as("the deployment said this bundle is its own and got UNKNOWN back, which "
                        + "the strict floor lowers on anyway — so the lever did nothing")
                .isEqualTo(Provenance.FIRST_PARTY);
        assertThat(LENIENT.lowersOn(restated.provenance())).isFalse();
        assertThat(STRICT.lowersOn(restated.provenance())).isFalse();
    }

    @Test
    @DisplayName("nothing that lowered a floor before stops lowering it")
    void noThirdPartyAnswerIsWeakened() {
        // The direction that would be a hole. Stated as a loop over the enum rather than as
        // three assertions, so a fourth constant could not be added past it.
        for (Provenance declared : Provenance.values()) {
            for (Provenance said : Provenance.values()) {
                if (declared == Provenance.THIRD_PARTY || said == Provenance.THIRD_PARTY) {
                    assertThat(attributed(declared, said))
                            .as("declared=%s said=%s", declared, said)
                            .isEqualTo(Provenance.THIRD_PARTY);
                }
            }
        }
        assertThat(List.of(Provenance.values())).hasSize(3);
    }
}
