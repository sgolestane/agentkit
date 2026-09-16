package dev.agentkit.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.supervisor.SubagentTools;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The record a run is held against (#310).
 *
 * <p>Written by hand in {@code SelfWiringAgent} until this shipped, which is why these tests
 * are here rather than only there: the details below are the ones a deployment re-deriving
 * the ledger gets wrong, and each of them survives every end-to-end test in the example.
 */
class RunLedgerTest {

    private static final String SPAWN = "spawn_subagent";
    private static final String SUPERVISOR = "supervisor";

    private static ToolInvocation call(String name, Map<String, Object> arguments) {
        return new ToolInvocation("t", name, arguments);
    }

    /**
     * First declaration wins, and the second is kept rather than dropped.
     *
     * <p>The detail #310 says every deployment gets wrong. Last-wins lets a run rewrite its
     * promise after seeing how the run went, which makes declaring one worth nothing.
     */
    @Test
    @DisplayName("the first declaration is the one the run is held to, and later ones are kept")
    void holdsTheRunToItsFirstDeclaration() {
        RunLedger ledger = new RunLedger();
        DeclaredPlan first = new DeclaredPlan(List.of("researcher"), true, false, "First.");
        DeclaredPlan second = new DeclaredPlan(List.of(), false, false, "On reflection.");

        assertThat(ledger.declare(first)).isTrue();
        assertThat(ledger.declare(second)).isFalse();

        assertThat(ledger.declaredPlan()).contains(first);
        assertThat(ledger.redeclarations()).containsExactly(second);
    }

    /**
     * The trace carries the call the gate settled on, not the one the model proposed.
     *
     * <p>The whole of #131 at this seam. Nothing in the example narrows an invocation, so no
     * run-level test there separates the two — and a trace that reports the proposal is wrong
     * about the only question anybody asks it.
     */
    @Test
    @DisplayName("a narrowed call is recorded as the gate settled it")
    void recordsTheSettledCallRatherThanTheProposal() {
        RunLedger ledger = new RunLedger();

        ledger.onToolResult(AgentRun.of(SUPERVISOR), 1,
                call(SubagentTools.DELEGATE, Map.of("subagent", "drafter", "goal", "write it")),
                call(SubagentTools.DELEGATE, Map.of("subagent", "researcher", "goal", "look")),
                ToolResult.ok("done"), Disposition.RAN);

        assertThat(ledger.trace()).singleElement()
                .satisfies(step -> assertThat(step.target()).isEqualTo("researcher"));
        assertThat(ledger.by(SUPERVISOR)).hasSize(1);
        assertThat(ledger.by("researcher")).isEmpty();
    }

    /**
     * A build that ran and refused built nothing.
     *
     * <p>{@link Disposition#RAN} covers a tool that ran and returned an error — a duplicate
     * name, a name the framework could not print — so "it ran" is not "it worked". A ledger
     * that read {@link RunLedger.Step#happened()} here would report a subagent this run never
     * created, and {@link Conformance} would then call a delegation to a name that does not
     * exist a note about a specialist the run built.
     */
    @Test
    @DisplayName("a build that ran and returned an error is not a subagent this run built")
    void aFailedBuildBuiltNothing() {
        RunLedger ledger = new RunLedger(Map.of(SPAWN, "name"));
        AgentRun run = AgentRun.of(SUPERVISOR);

        ledger.onToolResult(run, 1, call(SPAWN, Map.of("name", "auditor")),
                call(SPAWN, Map.of("name", "auditor")),
                ToolResult.ok("Built 'auditor'."), Disposition.RAN);
        ledger.onToolResult(run, 2, call(SPAWN, Map.of("name", "duplicate")),
                call(SPAWN, Map.of("name", "duplicate")),
                ToolResult.error("A subagent named 'duplicate' already exists."), Disposition.RAN);
        ledger.onToolResult(run, 3, call(SPAWN, Map.of("name", "refused")),
                call(SPAWN, Map.of("name", "refused")),
                ToolResult.error("blocked"), Disposition.REFUSED);

        // All three are in the trace, because a trace with holes is not a trace, and all
        // three are classified as builds. Only the one that worked built anything.
        assertThat(ledger.trace()).hasSize(3)
                .allSatisfy(step -> assertThat(step.kind())
                        .isEqualTo(RunLedger.Kind.SUBAGENT_BUILD));
        assertThat(ledger.trace().get(1).happened()).isTrue();
        assertThat(ledger.trace().get(1).succeeded()).isFalse();
        assertThat(ledger.builtSubagents()).containsExactly("auditor");
    }

    /**
     * A ledger not told about a spawning tool reads its call as an ordinary one.
     *
     * <p>The framework ships no {@code spawn_subagent} (#313), so the deployment names its
     * own. Pinned because the failure is silent and points the wrong way: an unrecognised
     * build leaves {@link RunLedger#builtSubagents()} empty, and the delegation that follows
     * then reads as routing to a subagent nobody named — the false report #316 is about,
     * reached through a typo instead of through the old check.
     */
    @Test
    @DisplayName("a build tool the ledger was not told about is recorded as an ordinary call")
    void anUnnamedBuildToolIsJustAnotherCall() {
        RunLedger untold = new RunLedger();
        RunLedger told = new RunLedger(Map.of(SPAWN, "name"));
        AgentRun run = AgentRun.of(SUPERVISOR);

        for (RunLedger ledger : List.of(untold, told)) {
            ledger.onToolResult(run, 1, call(SPAWN, Map.of("name", "auditor")),
                    call(SPAWN, Map.of("name", "auditor")),
                    ToolResult.ok("built"), Disposition.RAN);
        }

        assertThat(untold.trace()).singleElement().satisfies(step -> {
            assertThat(step.kind()).isEqualTo(RunLedger.Kind.OTHER);
            assertThat(step.target()).isNull();
        });
        assertThat(untold.builtSubagents()).isEmpty();
        assertThat(told.builtSubagents()).containsExactly("auditor");
    }

    /** Core's own two tools are recognised without being named. */
    @Test
    @DisplayName("delegate and verify_claim are classified without any configuration")
    void recognisesTheFrameworksOwnTools() {
        RunLedger ledger = new RunLedger();
        AgentRun run = AgentRun.of(SUPERVISOR);

        ledger.onToolResult(run, 1,
                call(SubagentTools.DELEGATE, Map.of("subagent", "researcher")),
                call(SubagentTools.DELEGATE, Map.of("subagent", "researcher")),
                ToolResult.ok("facts"), Disposition.RAN);
        ledger.onToolResult(run, 2,
                call(dev.agentkit.core.verify.VerifierTools.VERIFY_CLAIM, Map.of("claim", "x")),
                call(dev.agentkit.core.verify.VerifierTools.VERIFY_CLAIM, Map.of("claim", "x")),
                ToolResult.ok("PASS"), Disposition.RAN);

        assertThat(ledger.trace()).extracting(RunLedger.Step::kind)
                .containsExactly(RunLedger.Kind.DELEGATION, RunLedger.Kind.VERIFICATION);
        assertThat(ledger.trace().getFirst().target()).isEqualTo("researcher");
    }

    /** One ledger, two runs, and every row says which of them it belongs to (#311). */
    @Test
    @DisplayName("rows from a child are attributed to the child")
    void attributesRowsToTheRunThatMadeThem() {
        RunLedger ledger = new RunLedger();
        AgentRun supervisor = AgentRun.of(SUPERVISOR);
        AgentRun child = AgentRun.of("drafter");

        ledger.onToolResult(child, 1, call("publish", Map.of()), call("publish", Map.of()),
                ToolResult.error("held"), Disposition.PARKED);
        ledger.onToolResult(supervisor, 1, call("lookup", Map.of()), call("lookup", Map.of()),
                ToolResult.ok("facts"), Disposition.RAN);

        assertThat(ledger.by("drafter")).singleElement()
                .satisfies(step -> assertThat(step.happened()).isFalse());
        assertThat(ledger.by(SUPERVISOR)).singleElement()
                .satisfies(step -> assertThat(step.tool()).isEqualTo("lookup"));
    }
}
