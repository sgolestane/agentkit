package dev.agentkit.core.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A supervisor's {@code delegate} says what its roster holds (#313, item 1).
 *
 * <h2>The defect</h2>
 *
 * <p>{@code SubagentTools.delegateTool} answered {@link Tool#holdsGateWaitingForAHuman()}
 * {@code false} for every roster there has ever been, and it was not detectable: a
 * {@link Subagent} is a {@code Function<Goal, AgentResult>}, so there is no {@code Agent} to
 * ask and no {@code ToolGate} to forward. A supervisor whose subagent blocks on an approver
 * therefore registered on a durable worker as holding nothing, and the approver was paged
 * once per activity retry — the exact defect {@code Tool.holdsGateWaitingForAHuman} exists
 * to prevent (#283/#296), laundered by one layer of indirection.
 *
 * <p>The repair is a declaration on {@code Subagent} that {@code delegate} ORs across the
 * roster, the way a composite gate ORs across its members.
 *
 * <h2>Why "live" is the load-bearing word</h2>
 *
 * <p>The obvious spelling folds the OR into a field at {@code delegateTool(...)} time, and
 * #308 had just made that wrong: {@link SubagentRoster#add} is public and mutating, and
 * {@code delegate} renders its catalog and its {@code subagent} enum live precisely because
 * a roster grown mid-run must be advertised and callable. A build-time fold would have left
 * a subagent that is advertised, reachable and invisible to a registration check — #308's
 * divergence reintroduced one method along, in a value that decides an authorization
 * question. {@link #theOrIsLiveNotFoldedAtBuildTime()} is the test that spells that out, and
 * it is the one a folded implementation fails.
 *
 * <h2>What the mutation pass killed</h2>
 *
 * <p>Each mutant was planted on this branch, built clean, and put through this class and
 * {@code DurableToolHeldGateTest} in {@code agentkit-temporal}. Counts are read off the
 * {@code Tests run:} lines, never off the build's exit status — a killed surefire fork
 * prints {@code Tests run: 0}, which is not a result either.
 *
 * <pre>
 * mutant                                                 here   durable
 * DelegateTool.holdsGateWaitingForAHuman() deleted         3       2
 * DelegateTool.holdsGateBoundToOneRun() deleted            1       1
 * the OR folded into a field in the constructor            2       1
 * recordingParks built with Subagent.handling again        1       -
 * holdingAGateWaitingForAHuman() setting the sibling       2       -
 * </pre>
 *
 * <p><strong>One mutant survived half of what it should have, and the survival is the
 * finding.</strong> Rebuilding {@code recordingParks} on {@code Subagent.handling} drops two
 * things — the declarations, which this class catches, and the parent link, which nothing
 * did: the shipped wrapper had no test putting it behind a bound {@code delegate}.
 * {@code RunKnowsItsParentTest.aWrappedSubagentStillNamesItsParent} was added for that half,
 * and the mutant now fails a test in each file.
 */
class DelegateDeclaresWhatItsRosterHoldsTest {

    /**
     * The hazard itself: a gate whose approver is a bare lambda, so it is presumed to wait.
     *
     * <p>Held as a field and asserted about in {@link #theFixtureIsTheHazardAndNotAStandIn()}
     * rather than described in a comment, because a test whose "blocking subagent" is a
     * lambda returning a canned result measures nothing — it would pass against an
     * implementation that had no roster and no gate anywhere near it.
     */
    private static final ToolGate BLOCKING = ToolGates.requireApproval(
            invocation -> invocation.name().equals("publish"),
            (tool, invocation) -> ApprovalDecision.approve());

    /** An agent whose one tool call goes through {@link #BLOCKING}. */
    private static Agent blockingAgent() {
        Tool publish = FunctionTool.builder("publish", "Publishes text somewhere public")
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> ToolResult.ok("published"))
                .build();
        return Agent.builder(new FakeLlmClient(
                        FakeLlmClient.toolUse("t1", "publish", Map.of("text", "x")),
                        FakeLlmClient.text("done")),
                        new SimpleToolRegistry(List.of(publish)),
                        AgentConfig.builder("m").maxSteps(3).build())
                .toolGate(BLOCKING)
                .build();
    }

    private static Subagent plain(String name) {
        return Subagent.of(name, name + " does ordinary work", () -> new Agent(
                new FakeLlmClient(FakeLlmClient.text("done")),
                new SimpleToolRegistry(), AgentConfig.builder("m").build()));
    }

    // --- the headline -------------------------------------------------------------

    /**
     * A subagent that holds a blocking gate makes {@code delegate} say so.
     *
     * <p>The declaration is the deployment's, because nothing here can derive it — see
     * {@link Subagent}'s class javadoc for why even the overload holding an {@code Agent}
     * does not. What is pinned is that the declaration <em>arrives</em>: before this,
     * {@code delegate} answered {@code false} whatever the roster said, because there was
     * nothing for the roster to say.
     */
    @Test
    @DisplayName("delegate reports a blocking gate held by one of its subagents")
    void delegateReportsABlockingGateHeldBySomebodyInTheRoster() {
        SubagentRoster roster = SubagentRoster.of(
                plain("researcher"),
                Subagent.of("publisher", "publishes drafts",
                        DelegateDeclaresWhatItsRosterHoldsTest::blockingAgent)
                        .holdingAGateWaitingForAHuman());

        Tool delegate = SubagentTools.delegateTool(roster);

        assertThat(delegate.holdsGateWaitingForAHuman())
                .as("a supervisor whose subagent blocks on an approver used to register as"
                        + " holding nothing, and the approver was paged once per activity"
                        + " retry (#283/#313)")
                .isTrue();
        // The sibling is a separate question and this roster does not raise it: a test that
        // let one declaration answer both would pass against an implementation that ORed the
        // wrong field.
        assertThat(delegate.holdsGateBoundToOneRun()).isFalse();
    }

    /**
     * The OR is taken when it is asked, not when the tool was built.
     *
     * <p>The test a build-time fold fails, and the reason this is not a one-line change.
     * The roster is grown <em>after</em> {@code delegateTool} returned, which is exactly what
     * #308 shipped support for and exactly the state a model that spawns its own subagents
     * leaves the roster in. The first assertion is what makes the second one mean something:
     * without it, an implementation that answered {@code true} unconditionally would pass.
     */
    @Test
    @DisplayName("the roster OR is live: a subagent added after the tool was built flips it")
    void theOrIsLiveNotFoldedAtBuildTime() {
        SubagentRoster roster = SubagentRoster.of(plain("researcher"));
        Tool delegate = SubagentTools.delegateTool(roster);

        assertThat(delegate.holdsGateWaitingForAHuman())
                .as("nothing in the roster holds anything yet")
                .isFalse();

        roster.add(Subagent.of("publisher", "publishes drafts",
                        DelegateDeclaresWhatItsRosterHoldsTest::blockingAgent)
                .holdingAGateWaitingForAHuman());

        assertThat(delegate.holdsGateWaitingForAHuman())
                .as("#308 made the roster growable through the API's own front door, so a"
                        + " declaration folded at build time would describe a roster that no"
                        + " longer exists — the divergence #308 closed, one method along")
                .isTrue();
        // And the advertised contract moved with it, so the subagent whose hazard was just
        // declared is one the model can actually reach. A test that only checked the boolean
        // would pass for a roster the enum had never heard of.
        assertThat(delegate.inputSchema().toString()).contains("publisher");
    }

    /** The same, for the sibling declaration and its own case. */
    @Test
    @DisplayName("the per-run declaration is ORed live too, and separately")
    void thePerRunDeclarationIsAlsoLive() {
        SubagentRoster roster = SubagentRoster.of(plain("researcher"));
        Tool delegate = SubagentTools.delegateTool(roster);

        assertThat(delegate.holdsGateBoundToOneRun()).isFalse();

        roster.add(plain("screener").holdingAGateBoundToOneRun());

        assertThat(delegate.holdsGateBoundToOneRun()).isTrue();
        // Independent of the blocking one: one run's objective wired into a gate is not a
        // gate that waits for anybody.
        assertThat(delegate.holdsGateWaitingForAHuman()).isFalse();
    }

    // --- the properties the headline rests on --------------------------------------

    @Test
    @DisplayName("a roster that declares nothing still answers false to both")
    void anUndeclaredRosterIsStillTheCommonCase() {
        Tool delegate = SubagentTools.delegateTool(
                SubagentRoster.of(plain("a"), plain("b"), plain("c")));

        // The population that holds no gate at all is most of them, and an implementation
        // that over-reported would refuse every delegating supervisor at a durable worker.
        assertThat(delegate.holdsGateWaitingForAHuman()).isFalse();
        assertThat(delegate.holdsGateBoundToOneRun()).isFalse();
    }

    @Test
    @DisplayName("an empty roster answers false rather than throwing")
    void anEmptyRosterAnswersFalse() {
        Tool delegate = SubagentTools.delegateTool(new SubagentRoster());

        assertThat(delegate.holdsGateWaitingForAHuman()).isFalse();
        assertThat(delegate.holdsGateBoundToOneRun()).isFalse();
    }

    @Test
    @DisplayName("the two declarations are independent of each other")
    void theTwoDeclarationsAreIndependent() {
        Subagent blocking = plain("x").holdingAGateWaitingForAHuman();
        Subagent perRun = plain("y").holdingAGateBoundToOneRun();
        Subagent both = plain("z").holdingAGateWaitingForAHuman().holdingAGateBoundToOneRun();

        assertThat(blocking.holdsGateWaitingForAHuman()).isTrue();
        assertThat(blocking.holdsGateBoundToOneRun()).isFalse();
        assertThat(perRun.holdsGateWaitingForAHuman()).isFalse();
        assertThat(perRun.holdsGateBoundToOneRun()).isTrue();
        assertThat(both.holdsGateWaitingForAHuman()).isTrue();
        assertThat(both.holdsGateBoundToOneRun()).isTrue();
    }

    @Test
    @DisplayName("a wither copies rather than mutating, and carries the name and description")
    void aWitherCopies() {
        Subagent original = plain("researcher");
        Subagent declared = original.holdingAGateWaitingForAHuman();

        assertThat(original.holdsGateWaitingForAHuman())
                .as("the original is unchanged, so a roster holding it is not retroactively"
                        + " re-declared by a wither called somewhere else")
                .isFalse();
        assertThat(declared.name()).isEqualTo(original.name());
        assertThat(declared.description()).isEqualTo(original.description());
        assertThat(declared.holdsGateWaitingForAHuman()).isTrue();
    }

    /**
     * {@code recordingParks} must not launder the declaration it is most likely to meet.
     *
     * <p>The wrapper is built for the subagent that parks, so the subagent reaching it is
     * the one most likely to hold a blocking gate. Building it with
     * {@code Subagent.handling} — which is what it did, and which is the obvious thing to
     * keep doing — produces a new subagent that answers {@code false} however loudly the
     * wrapped one says otherwise: the {@code ForwardingTool} defect, committed one level up
     * by the class that added the declaration.
     */
    @Test
    @DisplayName("recordingParks carries both declarations through the wrapper")
    void aRecordingWrapperDoesNotLaunderTheDeclaration() {
        Subagent declared = Subagent.of("publisher", "publishes drafts",
                        DelegateDeclaresWhatItsRosterHoldsTest::blockingAgent)
                .holdingAGateWaitingForAHuman()
                .holdingAGateBoundToOneRun();

        Subagent wrapped = SubagentTools.recordingParks(declared, park -> { });

        assertThat(wrapped.holdsGateWaitingForAHuman()).isTrue();
        assertThat(wrapped.holdsGateBoundToOneRun()).isTrue();
        // And through the tool, which is where it is read from.
        assertThat(SubagentTools.delegateTool(SubagentRoster.of(wrapped))
                .holdsGateWaitingForAHuman()).isTrue();
    }

    /**
     * {@code Subagent.handling} does <em>not</em> carry a declaration, and that is stated
     * rather than glossed.
     *
     * <p>It builds a new subagent from a bare {@code Function}; there is nothing for it to
     * carry. The remedy is the wither, and this pins that the remedy works — so the
     * paragraph in {@code handling}'s javadoc is executable rather than advisory.
     */
    @Test
    @DisplayName("a handling-built wrapper drops the declaration, and the wither restores it")
    void handlingDropsTheDeclarationAndTheWitherIsTheWayBack() {
        Subagent declared = plain("publisher").holdingAGateWaitingForAHuman();

        Subagent rebuilt = Subagent.handling(declared.name(), declared.description(),
                declared::handle);

        assertThat(rebuilt.holdsGateWaitingForAHuman())
                .as("handling builds a new subagent out of a Function; it has nothing to"
                        + " carry, which is why recordingParks does not go through it")
                .isFalse();
        assertThat(rebuilt.holdingAGateWaitingForAHuman().holdsGateWaitingForAHuman()).isTrue();
    }

    /**
     * The fixture above is the hazard the declaration is about, not a stand-in for it.
     *
     * <p>{@code Approver.waitsForAHuman()} presumes {@code true} and a bare lambda has no
     * way to say otherwise (#290), so the gate inside {@link #blockingAgent()} is one a
     * durable worker refuses at registration. That is the thing a {@code Subagent} cannot
     * see and therefore has to declare — this asserts the premise so the rest of the file is
     * measuring something real.
     */
    @Test
    @DisplayName("the fixture holds a gate that really does wait for a person")
    void theFixtureIsTheHazardAndNotAStandIn() {
        assertThat(BLOCKING.waitsForAHuman()).isTrue();
        // And it is invisible from outside the subagent, which is the whole reason the
        // declaration exists: nothing here can reach that gate from a Subagent.
        assertThat(Subagent.of("publisher", "publishes drafts",
                        DelegateDeclaresWhatItsRosterHoldsTest::blockingAgent)
                .holdsGateWaitingForAHuman())
                .as("undeclared is undetectable, which is why the answer is a declaration")
                .isFalse();
    }
}
