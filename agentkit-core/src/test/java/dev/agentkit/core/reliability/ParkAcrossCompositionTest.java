package dev.agentkit.core.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.collab.Critic;
import dev.agentkit.core.collab.Critics;
import dev.agentkit.core.collab.Critique;
import dev.agentkit.core.collab.Peer;
import dev.agentkit.core.collab.RefineLoop;
import dev.agentkit.core.collab.RefineResult;
import dev.agentkit.core.goap.Action;
import dev.agentkit.core.goap.ActionResult;
import dev.agentkit.core.goap.GoapResult;
import dev.agentkit.core.goap.GoapRunner;
import dev.agentkit.core.goap.GoapStop;
import dev.agentkit.core.goap.Objective;
import dev.agentkit.core.goap.WorldState;
import dev.agentkit.core.graph.AgentGraph;
import dev.agentkit.core.graph.GraphNode;
import dev.agentkit.core.graph.GraphResult;
import dev.agentkit.core.graph.GraphStop;
import dev.agentkit.core.graph.NodeState;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.reflect.LessonBook;
import dev.agentkit.core.reflect.ReflectiveAgent;
import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.core.supervisor.DelegatedTask;
import dev.agentkit.core.supervisor.Subagent;
import dev.agentkit.core.supervisor.SubagentRoster;
import dev.agentkit.core.supervisor.SubagentTools;
import dev.agentkit.core.supervisor.SupervisionResult;
import dev.agentkit.core.supervisor.Supervisor;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.verify.SelfVerifyingAgent;
import dev.agentkit.core.verify.Verdict;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * What an agent that composes another one does when the inner one parks (#159).
 *
 * <p>{@code GateResult.needsAPerson} works on the runner that raises it. This is about the
 * level above: a subagent, a graph node, a GOAP action, a refinement round. Every one of
 * them asked {@code AgentResult.isSuccess()} and got {@code false}, so a question about
 * whether an effect should happen arrived as a worker that failed.
 *
 * <p><strong>Every test here counts what actually happened, not what was reported.</strong>
 * A composer that reports {@code AWAITING_APPROVAL} and takes the fallback branch anyway is
 * the bug, and asserting on the stop reason alone passes it. So the assertions are on the
 * fallback action's run count, the fallback node's run count, and the number of generator
 * rounds — the alternative routes, and whether they were taken.
 *
 * <p>The failure mode being measured is specific: an outer agent told "the inner one
 * failed" <em>routes around it</em>. A {@code GoapRunner} abandons a failed action and
 * re-plans by design; a graph's default edge condition is "it succeeded", so a fallback edge
 * is written as {@code r -> !r.isSuccess()}; a {@code RefineLoop} sends the generator back
 * round. Each of those turns "a person must decide" into "get the same effect another way",
 * which is what {@code Supervisor}'s own park message tells a model not to do.
 */
class ParkAcrossCompositionTest {

    private static final AgentConfig CONFIG = AgentConfig.builder("m").maxSteps(5).build();

    /** The gated side effect. Every test asserts it never ran. */
    private static Tool publisher(AtomicInteger ran) {
        return FunctionTool.builder("publish", "Publishes text somewhere public")
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> {
                    ran.incrementAndGet();
                    return ToolResult.ok("published");
                })
                .build();
    }

    /** An agent whose one tool call is parked by a gate, so its run ends AWAITING_APPROVAL. */
    private static Agent parkingAgent(AtomicInteger published) {
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "publish", Map.of("text", "SECRET-PAYLOAD")),
                FakeLlmClient.text("unreached"));
        return Agent.builder(llm, new SimpleToolRegistry(List.of(publisher(published))), CONFIG)
                .toolGate(ToolGates.parkForApproval(inv -> inv.name().equals("publish"),
                        ApprovalNeeded.because("publishing needs a person")
                                .withEffect("The text becomes publicly visible.")))
                .build();
    }

    /** A run that genuinely fails: the scripted client is empty, so the first turn throws. */
    private static Agent failingAgent() {
        return Agent.builder(new FakeLlmClient(), new SimpleToolRegistry(List.of()), CONFIG).build();
    }

    private static Agent plainAgent(String answer) {
        return Agent.builder(new FakeLlmClient(FakeLlmClient.text(answer)),
                new SimpleToolRegistry(List.of()), CONFIG).build();
    }

    // --- GOAP -----------------------------------------------------------------

    /**
     * The headline measurement. Two actions produce {@code sources}; the cheap one parks.
     * Before the fix the planner abandoned it, took the dearer route, and the run reported
     * {@code OBJECTIVE_MET} — the effect obtained while the question was outstanding.
     */
    @Test
    void goapDoesNotPlanARouteAroundAPerson() {
        AtomicInteger published = new AtomicInteger();
        AtomicInteger archiveRuns = new AtomicInteger();

        GoapResult result = GoapRunner.forObjective(Objective.of("sources gathered", "sources"))
                .action(Action.named("search_web").produces("sources")
                        .agent("Find primary sources", () -> parkingAgent(published)).build())
                .action(Action.named("read_archive").cost(3).produces("sources")
                        .handler(state -> {
                            archiveRuns.incrementAndGet();
                            return ActionResult.ok("sources", "from the archive");
                        }).build())
                .run(WorldState.EMPTY);

        assertThat(archiveRuns)
                .as("the alternative route to the same fact must not run while a person is"
                        + " being asked about the first one; asserting on the stop reason"
                        + " alone would pass a runner that reported the park and re-planned")
                .hasValue(0);
        assertThat(published).hasValue(0);
        assertThat(result.stop()).isEqualTo(GoapStop.AWAITING_APPROVAL);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.path()).containsExactly("search_web");
    }

    @Test
    void goapCarriesTheQuestionOnItsResult() {
        AtomicInteger published = new AtomicInteger();

        GoapResult result = GoapRunner.forObjective(Objective.of("sources gathered", "sources"))
                .action(Action.named("search_web").produces("sources")
                        .agent("Find primary sources", () -> parkingAgent(published)).build())
                .run(WorldState.EMPTY);

        assertThat(result.awaiting()).hasSize(1);
        assertThat(result.awaiting().get(0).toolName()).isEqualTo("publish");
        assertThat(result.awaiting().get(0).why().reason()).isEqualTo("publishing needs a person");
        assertThat(result.trace().get(0).awaitsAPerson()).isTrue();
    }

    /** Facts established before the park survive it, as they do for every other stop. */
    @Test
    void goapKeepsWhatItEstablishedBeforeTheQuestion() {
        AtomicInteger published = new AtomicInteger();

        GoapResult result = GoapRunner.forObjective(Objective.of("published", "receipt"))
                .action(Action.named("gather").produces("sources")
                        .handler(state -> ActionResult.ok("sources", "three papers")).build())
                .action(Action.named("publish_it").needs("sources").produces("receipt")
                        .agent("Publish the summary", () -> parkingAgent(published)).build())
                .run(WorldState.EMPTY);

        assertThat(result.stop()).isEqualTo(GoapStop.AWAITING_APPROVAL);
        assertThat(result.output("sources")).contains("three papers");
        assertThat(published).hasValue(0);
    }

    /**
     * A handler that files a fact from a run that parked stops the run too. The fact was
     * never earned: nothing did the work the person is being asked about.
     */
    @Test
    void goapStopsEvenWhenAHandlerCallsAParkedRunASuccess() {
        AtomicInteger published = new AtomicInteger();
        AtomicInteger downstream = new AtomicInteger();

        GoapResult result = GoapRunner.forObjective(Objective.of("done", "article"))
                .action(Action.named("gather").produces("sources")
                        .handler(state -> {
                            AgentResult ran = parkingAgent(published).run(Goal.of("publish"));
                            return ActionResult.ok(Map.of("sources", ran.output()), ran);
                        }).build())
                .action(Action.named("write").needs("sources").produces("article")
                        .handler(state -> {
                            downstream.incrementAndGet();
                            return ActionResult.ok("article", "written");
                        }).build())
                .run(WorldState.EMPTY);

        assertThat(downstream).hasValue(0);
        assertThat(result.stop()).isEqualTo(GoapStop.AWAITING_APPROVAL);
    }

    // --- Graph ----------------------------------------------------------------

    /**
     * A fallback edge is written {@code r -> !r.isSuccess()}, so before the fix a park took
     * it. That is the graph's version of the same defect: the branch drawn for "the gated
     * route did not work" fired on "somebody is deciding whether the gated route may work".
     */
    @Test
    void graphDoesNotTakeAFallbackEdgeOutOfAPark() {
        AtomicInteger published = new AtomicInteger();
        AtomicInteger fallbackRuns = new AtomicInteger();

        GraphResult result = AgentGraph.builder()
                .node("gated", GraphNode.agent("publish it", () -> parkingAgent(published)))
                .node("plan_b", (GraphNode) input -> {
                    fallbackRuns.incrementAndGet();
                    return AgentResult.completed("did it another way", 1);
                })
                .edge("gated", "plan_b", r -> !r.isSuccess())
                .build()
                .run(Goal.of("publish"));

        assertThat(fallbackRuns)
                .as("the other arm of the branch must not run while the question is open")
                .hasValue(0);
        assertThat(published).hasValue(0);
        assertThat(result.stop()).isEqualTo(GraphStop.AWAITING_APPROVAL);
        assertThat(result.outcome("gated").orElseThrow().state())
                .isEqualTo(NodeState.AWAITING_APPROVAL);
        assertThat(result.outcome("plan_b").orElseThrow().state()).isEqualTo(NodeState.NOT_RUN);
        assertThat(result.awaiting()).hasSize(1);
        assertThat(result.awaiting().get(0).toolName()).isEqualTo("publish");
        assertThat(result.isSuccess()).isFalse();
    }

    /** A park is a question, so it is not in {@code failures()} — {@code awaiting()} is where it is. */
    @Test
    void aParkedNodeIsNotCountedAsAFailure() {
        AtomicInteger published = new AtomicInteger();

        GraphResult result = AgentGraph.builder()
                .node("gated", GraphNode.agent("publish it", () -> parkingAgent(published)))
                .build()
                .run(Goal.of("publish"));

        assertThat(result.failures()).isEmpty();
        assertThat(result.outcome("gated").orElseThrow().awaitsAPerson()).isTrue();
        assertThat(result.outcome("gated").orElseThrow().ran()).isTrue();
    }

    /**
     * The same, ordered: the sibling cannot finish until after the park has happened, so
     * this fails for a graph that breaks out of its loop on a park rather than latching and
     * draining. The unordered version above passes either way — a lambda node finishes
     * before an agent node does — which is exactly the kind of test that pins nothing.
     */
    @Test
    void graphDoesNotCancelASiblingStillRunningWhenTheParkArrives() throws Exception {
        AtomicInteger published = new AtomicInteger();
        CountDownLatch parkReached = new CountDownLatch(1);
        AtomicInteger siblingFinished = new AtomicInteger();

        Agent gated = Agent.builder(
                        new FakeLlmClient(
                                FakeLlmClient.toolUse("t1", "publish", Map.of("text", "x")),
                                FakeLlmClient.text("unreached")),
                        new SimpleToolRegistry(List.of(publisher(published))), CONFIG)
                .toolGate(ToolGates.parkForApproval(inv -> {
                    parkReached.countDown();
                    return inv.name().equals("publish");
                }, ApprovalNeeded.because("publishing needs a person")))
                .build();

        GraphResult result = AgentGraph.builder()
                .node("gated", GraphNode.agent("publish it", () -> gated))
                .node("slow", (GraphNode) input -> {
                    try {
                        // Still in flight when the park lands, by construction.
                        if (!parkReached.await(10, TimeUnit.SECONDS)) {
                            return AgentResult.failed(new IllegalStateException("no park"), 0);
                        }
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return AgentResult.failed(e, 0);
                    }
                    siblingFinished.incrementAndGet();
                    return AgentResult.completed("unrelated work", 1);
                })
                .build()
                .run(Goal.of("publish"));

        assertThat(siblingFinished)
                .as("a park says nothing about unrelated work that has already been paid"
                        + " for; TIMED_OUT cancels because the clock is the thing being"
                        + " enforced, and there is no such thing here")
                .hasValue(1);
        assertThat(result.outcome("slow").orElseThrow().state()).isEqualTo(NodeState.COMPLETED);
        assertThat(result.stop()).isEqualTo(GraphStop.AWAITING_APPROVAL);
    }

    /**
     * Siblings already running are left alone. A park says nothing about unrelated work, and
     * cancelling it would spend the run's tokens and throw the answers away.
     */
    @Test
    void graphLetsWorkAlreadyInFlightFinish() {
        AtomicInteger published = new AtomicInteger();
        AtomicInteger siblingRuns = new AtomicInteger();

        GraphResult result = AgentGraph.builder()
                .node("gated", GraphNode.agent("publish it", () -> parkingAgent(published)))
                .node("unrelated", (GraphNode) input -> {
                    siblingRuns.incrementAndGet();
                    return AgentResult.completed("unrelated work", 1);
                })
                .build()
                .run(Goal.of("publish"));

        assertThat(siblingRuns).hasValue(1);
        assertThat(result.outcome("unrelated").orElseThrow().state())
                .isEqualTo(NodeState.COMPLETED);
        assertThat(result.stop()).isEqualTo(GraphStop.AWAITING_APPROVAL);
    }

    // --- Supervisor -----------------------------------------------------------

    /**
     * A fan-out has no route to take around a park, so it does not stop — what was missing
     * was the name. Before this, a parked subagent and one that ran out of steps were the
     * same two facts: {@code allSucceeded() == false} and one entry in {@code failures()}.
     */
    @Test
    void aFanOutNamesTheParkWithoutAbandoningTheRest() {
        AtomicInteger published = new AtomicInteger();
        SubagentRoster roster = SubagentRoster.of(
                Subagent.of("gated", "publishes", () -> parkingAgent(published)),
                Subagent.of("other", "answers", () -> plainAgent("other done")));

        SupervisionResult result = Supervisor.of(roster).fanOut(Goal.of("g"), List.of(
                new DelegatedTask("gated", Goal.of("publish")),
                new DelegatedTask("other", Goal.of("something else"))));

        assertThat(result.awaiting()).hasSize(1);
        assertThat(result.awaiting().get(0).toolName()).isEqualTo("publish");
        assertThat(result.outcomes().get(0).awaitsAPerson()).isTrue();
        assertThat(result.outcomes().get(1).awaitsAPerson())
                .as("a subagent that simply finished is not waiting on anybody")
                .isFalse();
        assertThat(result.outcomes().get(1).succeeded())
                .as("the independent subgoal still ran and still answered")
                .isTrue();
        assertThat(published).hasValue(0);
    }

    // --- SubagentTools --------------------------------------------------------

    /**
     * The supervisor's model is told a person was asked, and told not to find another way.
     * Before this it read {@code "Subagent 'gated' did not complete (AWAITING_APPROVAL)."},
     * which is what a model retries or re-routes on.
     */
    @Test
    void theDelegateToolSaysAPersonWasAskedAndNotToRouteAround() {
        AtomicInteger published = new AtomicInteger();
        SubagentRoster roster = SubagentRoster.of(
                Subagent.of("gated", "publishes", () -> parkingAgent(published)));

        ToolResult result = SubagentTools.delegateTool(roster).execute(
                new ToolInvocation("d1", "delegate",
                        Map.of("subagent", "gated", "goal", "publish it")));

        assertThat(result.isError()).isTrue();
        assertThat(result.content())
                .contains("asked a person to decide")
                .contains("it did not fail")
                .contains("do not attempt an alternative route to the same effect")
                .contains("'publish'")
                .contains("publishing needs a person")
                .contains("The text becomes publicly visible.");
        assertThat(result.content())
                .as("the parked call's arguments are the child model's words and a"
                        + " supervisor does not need them to stop; the person deciding sees"
                        + " them, through PendingApproval, where they are shown faithfully")
                .doesNotContain("SECRET-PAYLOAD");
        assertThat(published).hasValue(0);
    }

    /**
     * The message is bounded, so something is the tail. It must not be the instruction:
     * written last, a tight ceiling removed the one sentence the message exists to deliver
     * and left the gate's reasons, which are what make a model want to try something else.
     */
    @Test
    void theInstructionSurvivesTheCutAndTheExplanationIsWhatGoes() {
        AtomicInteger published = new AtomicInteger();
        SubagentRoster roster = SubagentRoster.of(
                Subagent.of("gated", "publishes", () -> parkingAgent(published)));

        ToolResult result = SubagentTools.delegateTool(roster, 200).execute(
                new ToolInvocation("d1", "delegate",
                        Map.of("subagent", "gated", "goal", "publish it")));

        assertThat(result.content())
                .hasSizeLessThan(230)
                .contains("do not attempt an alternative route to the same effect");
    }

    // --- RefineLoop and Critics ----------------------------------------------

    @Test
    void aParkedGeneratorEndsTheLoopCarryingTheQuestion() {
        AtomicInteger published = new AtomicInteger();
        AtomicInteger reviews = new AtomicInteger();

        RefineResult result = new RefineLoop(() -> parkingAgent(published), (goal, draft) -> {
            reviews.incrementAndGet();
            return Critique.approve();
        }, 3).run(Goal.of("write it"));

        assertThat(reviews)
                .as("nothing was produced to review")
                .hasValue(0);
        assertThat(result.awaitsAPerson()).isTrue();
        assertThat(result.awaiting()).hasSize(1);
        assertThat(result.awaiting().get(0).toolName()).isEqualTo("publish");
        assertThat(result.approved()).isFalse();
        assertThat(result.rounds()).isEqualTo(1);
        assertThat(published).hasValue(0);
    }

    /**
     * A parked reviewer used to arrive as {@code revise("Reviewer did not complete ...")},
     * which means "the draft fell short" — so the generator was sent back over a draft
     * nobody had read. Measured before the fix: three generator rounds off one unanswered
     * question, stopped only by the round cap.
     */
    @Test
    void aParkedReviewerDoesNotDriveAnotherGeneratorRound() {
        AtomicInteger published = new AtomicInteger();
        AtomicInteger generatorRounds = new AtomicInteger();
        Critic critic = Critics.agent(
                Peer.of("reviewer", "reviews drafts", () -> parkingAgent(published)));

        RefineResult result = new RefineLoop(() -> {
            generatorRounds.incrementAndGet();
            return plainAgent("draft " + generatorRounds.get());
        }, critic, 3).run(Goal.of("write it"));

        assertThat(generatorRounds)
                .as("one round produced the draft; the rest were work done because a"
                        + " question about the reviewer was read as a complaint about it")
                .hasValue(1);
        assertThat(result.awaitsAPerson()).isTrue();
        assertThat(result.output())
                .as("the draft the person is about to be asked about cost a generator run")
                .isEqualTo("draft 1");
        assertThat(result.lastFeedback())
                .as("a reviewer that never read the draft has nothing to say about it")
                .isEmpty();
        assertThat(published).hasValue(0);
    }

    @Test
    void aParkedCriticReportsWhatIsPending() {
        AtomicInteger published = new AtomicInteger();
        Critique critique = Critics.agent(
                        Peer.of("reviewer", "reviews drafts", () -> parkingAgent(published)))
                .review(Goal.of("write it"), "a draft");

        assertThat(critique.awaitsAPerson()).isTrue();
        assertThat(critique.approved()).isFalse();
        assertThat(critique.feedback()).isEmpty();
        assertThat(critique.awaiting().get(0).toolName()).isEqualTo("publish");
    }

    // --- the two that were already right -------------------------------------

    /**
     * Pinned, not fixed. Both rebuild their result rather than reconstructing it by stop
     * reason — {@code ReflectiveAgent} through {@code AgentResult.withTotals}, and
     * {@code SelfVerifyingAgent} by returning the inner result untouched — so the park
     * already travelled. #159's claim was that <em>every</em> composer loses it; these two
     * do not, and a test that says so is what stops a later change quietly making them.
     */
    @Test
    void theSingleAgentWrappersAlreadyPropagateAPark() {
        AtomicInteger published = new AtomicInteger();

        AgentResult reflective = new ReflectiveAgent(() -> parkingAgent(published),
                (goal, output) -> Verdict.pass(),
                (goal, result, feedback) -> "lesson",
                new LessonBook(MemoryStore.inMemory()), 3).run(Goal.of("publish"));

        AgentResult verifying = new SelfVerifyingAgent(() -> parkingAgent(published),
                (goal, output) -> Verdict.pass(), 3).run(Goal.of("publish"));

        assertThat(reflective.stopReason()).isEqualTo(StopReason.AWAITING_APPROVAL);
        assertThat(reflective.awaiting()).hasSize(1);
        assertThat(verifying.stopReason()).isEqualTo(StopReason.AWAITING_APPROVAL);
        assertThat(verifying.awaiting()).hasSize(1);
        assertThat(published).hasValue(0);
    }

    // --- the new invariants ---------------------------------------------------

    @Test
    void aCritiqueCannotApproveAndWaitAtOnce() {
        AtomicInteger published = new AtomicInteger();
        List<PendingApproval> pending =
                parkingAgent(published).run(Goal.of("publish")).awaiting();

        assertThatThrownBy(() -> new Critique(true, "", pending))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot also be waiting");
        assertThatThrownBy(() -> Critique.needsAPerson(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must name what is pending");
    }

    @Test
    void aRefineResultCannotBeApprovedAndWaitAtOnce() {
        AtomicInteger published = new AtomicInteger();
        List<PendingApproval> pending =
                parkingAgent(published).run(Goal.of("publish")).awaiting();

        assertThatThrownBy(() -> new RefineResult("d", true, 1, "", pending))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot also be waiting");
        assertThatThrownBy(() -> RefineResult.awaitingApproval("d", 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must name what is pending");
    }

    /**
     * The other direction, and the one a fix like this gets wrong by over-reaching: every
     * assertion above would still pass if "parked" were spelled "did not succeed". It is
     * not — a composer that stops on a failure has taken away the routing around that
     * {@code GoapRunner} exists for and that a graph's fallback edge is drawn for.
     */
    @Test
    void aRunThatSimplyFailedIsNotMistakenForAPark() {
        AgentResult failed = failingAgent().run(Goal.of("anything"));
        assertThat(failed.stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(failed.isAwaitingApproval()).isFalse();

        AtomicInteger fallbackRuns = new AtomicInteger();
        GoapResult goap = GoapRunner.forObjective(Objective.of("sources gathered", "sources"))
                .action(Action.named("search_web").produces("sources")
                        .agent("Find primary sources", ParkAcrossCompositionTest::failingAgent)
                        .build())
                .action(Action.named("read_archive").cost(3).produces("sources")
                        .handler(state -> {
                            fallbackRuns.incrementAndGet();
                            return ActionResult.ok("sources", "from the archive");
                        }).build())
                .run(WorldState.EMPTY);
        assertThat(fallbackRuns)
                .as("a failed action is still routed around — that is what this runner is for")
                .hasValue(1);
        assertThat(goap.stop()).isEqualTo(GoapStop.OBJECTIVE_MET);
        assertThat(goap.awaiting()).isEmpty();

        GraphResult graph = AgentGraph.builder()
                .node("broken", GraphNode.agent("do it",
                        ParkAcrossCompositionTest::failingAgent))
                .build()
                .run(Goal.of("anything"));
        assertThat(graph.outcome("broken").orElseThrow().state()).isEqualTo(NodeState.FAILED);
        assertThat(graph.failures()).hasSize(1);
        assertThat(graph.awaiting()).isEmpty();

        SupervisionResult supervised = Supervisor.of(SubagentRoster.of(
                        Subagent.of("broken", "fails",
                                ParkAcrossCompositionTest::failingAgent)))
                .fanOut(Goal.of("g"), List.of(new DelegatedTask("broken", Goal.of("do it"))));
        assertThat(supervised.outcomes().get(0).awaitsAPerson())
                .as("nobody was asked anything; this subagent simply failed")
                .isFalse();
        assertThat(supervised.failures()).hasSize(1);
        assertThat(supervised.awaiting()).isEmpty();
    }

    @Test
    void isAwaitingApprovalAgreesWithTheStopReasonAndTheList() {
        AtomicInteger published = new AtomicInteger();
        AgentResult parked = parkingAgent(published).run(Goal.of("publish"));
        AgentResult finished = plainAgent("done").run(Goal.of("anything"));

        assertThat(parked.isAwaitingApproval()).isTrue();
        assertThat(parked.isSuccess()).isFalse();
        assertThat(finished.isAwaitingApproval()).isFalse();
    }
}
