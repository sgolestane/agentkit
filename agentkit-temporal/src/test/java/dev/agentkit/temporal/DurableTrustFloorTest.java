package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * "After you read the web, you cannot write", durably (#122).
 *
 * <p>The durable half is where the design had to be careful, and the reason is division of
 * labour. The <em>gates</em> live in the activity, because the workflow has no {@code Tool}
 * to gate. The <em>one bit of state</em> — has this run read somebody else's words — lives
 * in the workflow, because one activity instance serves every run on the worker and a field
 * there would leak between unrelated runs and tenants. So the workflow says which policy
 * applies by which method it calls, and it learns whether to switch from the activity, which
 * holds the floor and therefore owns the judgement.
 *
 * <p>The sticky cache is off in every test here, so every workflow task genuinely replays
 * from history. A flag that only survived in a live worker's memory would be caught.
 *
 * <p><strong>Every test counts what the tool did.</strong>
 */
class DurableTrustFloorTest {

    private static final String TASK_QUEUE = "agentkit-floor-test";

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

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

    private static Tool publisher(List<String> entered) {
        return FunctionTool.builder("publish", "Publishes text somewhere public")
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> {
                    entered.add("publish");
                    return ToolResult.ok("published");
                })
                .build();
    }

    private AgentRunResult runWith(LlmClient llm, SimpleToolRegistry tools, TrustFloor floor) {
        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(TemporalAgent.dataConverter())
                        .build())
                .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder()
                        .setWorkflowCacheSize(0)
                        .build())
                .build());
        Worker worker = env.newWorker(TASK_QUEUE);
        TemporalAgent.register(worker, llm, tools, floor);
        env.start();
        return TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE)
                .run(DurableAgentRun.of(Goal.of("do it"),
                        AgentConfig.builder("m").maxSteps(6).build(), tools.advertisedSpecs()));
    }

    private static TrustFloor readThenReadOnly() {
        return TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, ToolGates.readOnly());
    }

    // --- the tests ----------------------------------------------------------------

    @Test
    void aWriteAfterAReadOfSomebodyElsesWordsIsRefused() {
        List<String> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(fetcher(entered)).register(publisher(entered));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "fetch", Map.of()))
                .then(ScriptedLlm.toolUse("t2", "publish", Map.of()))
                .then(ScriptedLlm.text("done"));

        AgentRunResult result = runWith(llm, tools, readThenReadOnly());

        assertThat(entered)
                .as("the flag lives in the workflow and the gates in the activity; a run"
                        + " that lost the flag on replay would publish here")
                .containsExactly("fetch");
        assertThat(result.output()).isEqualTo("done");
    }

    @Test
    void thatSameWriteIsAllowedWhenNothingHasBeenRead() {
        // The positive control. Without it the test above passes for a floor that refuses
        // writes always, which is a different and much weaker claim.
        List<String> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(fetcher(entered)).register(publisher(entered));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "publish", Map.of()))
                .then(ScriptedLlm.text("done"));

        runWith(llm, tools, readThenReadOnly());

        assertThat(entered).containsExactly("publish");
    }

    @Test
    void readingOurOwnRecordsDoesNotLowerTheFloor() {
        List<String> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(ourOwnRecords(entered)).register(publisher(entered));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "lookup", Map.of()))
                .then(ScriptedLlm.toolUse("t2", "publish", Map.of()))
                .then(ScriptedLlm.text("done"));

        runWith(llm, tools, readThenReadOnly());

        assertThat(entered).containsExactly("lookup", "publish");
    }

    @Test
    void theFloorSurvivesEveryReplayBetweenLoweringAndTheWrite() {
        // The sticky cache is off, so each workflow task replays the whole history. Several
        // turns between the read and the write means several replays that must all arrive
        // at the same answer — which is the property a transcript scan would not have had,
        // since it would have to agree with history rather than with in-memory state.
        List<String> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(fetcher(entered)).register(ourOwnRecords(entered))
                .register(publisher(entered));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "fetch", Map.of()))
                .then(ScriptedLlm.toolUse("t2", "lookup", Map.of()))
                .then(ScriptedLlm.toolUse("t3", "lookup", Map.of()))
                .then(ScriptedLlm.toolUse("t4", "publish", Map.of()))
                .then(ScriptedLlm.text("done"));

        runWith(llm, tools, readThenReadOnly());

        assertThat(entered).containsExactly("fetch", "lookup", "lookup");
    }

    @Test
    void theStrictReadingCountsAToolThatDeclaredNothing() {
        List<String> entered = new ArrayList<>();
        Tool undeclared = FunctionTool.builder("mystery", "Nobody said what this returns")
                .sideEffects(SideEffects.NONE)
                .handler(invocation -> {
                    entered.add("mystery");
                    return ToolResult.ok("something");
                })
                .build();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(undeclared).register(publisher(entered));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "mystery", Map.of()))
                .then(ScriptedLlm.toolUse("t2", "publish", Map.of()))
                .then(ScriptedLlm.text("done"));

        runWith(llm, tools, TrustFloor.afterAnythingUndeclared(
                ToolGate.ALLOW_ALL, ToolGates.readOnly()));

        assertThat(entered).containsExactly("mystery");
    }

    @Test
    void aFloorWhoseTightenedPolicyBlocksIsRefusedAtRegistration() {
        // Both gates are checked, because a run reaches both. Checking only the ordinary one
        // would accept this wiring and kill the run the first time anything returned a page
        // — the failure mode #58's registration guard exists to prevent, arriving through
        // the second gate.
        SimpleToolRegistry tools = new SimpleToolRegistry().register(publisher(new ArrayList<>()));
        ToolGate blocks = ToolGates.requireApproval(invocation -> true,
                (tool, invocation) -> ApprovalDecision.approve());

        assertThatThrownBy(() -> new ToolActivitiesImpl(tools,
                TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, blocks)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block waiting for a person");
    }

    @Test
    void aParkingFloorIsAcceptedWhereABlockingOneIsRefused() {
        // The composition #101 and #122 are for together: once this run has read the web,
        // everything with a side effect needs a person. parkForApproval returns at once, so
        // the durable runner takes it.
        SimpleToolRegistry tools = new SimpleToolRegistry().register(publisher(new ArrayList<>()));
        TrustFloor floor = TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL,
                ToolGates.parkForApproval(invocation -> true,
                        dev.agentkit.core.reliability.ApprovalNeeded.because(
                                "this run has read somebody else's words")));

        assertThat(new ToolActivitiesImpl(tools, floor)).isNotNull();
    }

    // --- what the single-tool turns above could not see -------------------------------

    @Test
    void theFloorLowersWithinATurnDurablyToo() {
        // Every test above uses single-tool turns, so the durable within-turn rule had no
        // coverage at all: a mutant snapshotting the flag at the start of each turn — the
        // first read of a turn is a free pass — survived all 114 temporal tests. The rule
        // is re-implemented in the workflow and the in-process test cannot reach it.
        List<String> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(fetcher(entered)).register(publisher(entered));

        runWith(new ScriptedLlm().then(ScriptedLlm.multiProposedCall(List.of(
                        dev.agentkit.core.message.ProposedCall.of("t1", "fetch", Map.of()),
                        dev.agentkit.core.message.ProposedCall.of("t2", "publish", Map.of()))))
                        .then(ScriptedLlm.text("done")),
                tools, readThenReadOnly());

        assertThat(entered).containsExactly("fetch");
    }

    @Test
    void bothCallsOfATurnRunWhenNothingLowersTheFloorDurably() {
        // The control: the test above must be about the floor, not about a workflow that
        // drops the second call of every multi-tool turn.
        List<String> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(ourOwnRecords(entered)).register(publisher(entered));

        runWith(new ScriptedLlm().then(ScriptedLlm.multiProposedCall(List.of(
                        dev.agentkit.core.message.ProposedCall.of("t1", "lookup", Map.of()),
                        dev.agentkit.core.message.ProposedCall.of("t2", "publish", Map.of()))))
                        .then(ScriptedLlm.text("done")),
                tools, readThenReadOnly());

        assertThat(entered).containsExactly("lookup", "publish");
    }

    @Test
    void aFailingThirdPartyToolLowersTheFloorDurablyToo() {
        // Also re-implemented in the activity and also uncovered: a mutant that stopped
        // judging results against the floor there survived all 114 tests, because the only
        // test of this rule was in core.
        List<String> entered = new ArrayList<>();
        Tool failsWithSomebodyElsesWords = FunctionTool.builder("fetch", "Fetches a page")
                .sideEffects(SideEffects.NONE)
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> {
                    entered.add("fetch");
                    throw new IllegalStateException("HTTP 500: <the server's own words>");
                })
                .build();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(failsWithSomebodyElsesWords).register(publisher(entered));

        runWith(new ScriptedLlm()
                        .then(ScriptedLlm.toolUse("t1", "fetch", Map.of()))
                        .then(ScriptedLlm.toolUse("t2", "publish", Map.of()))
                        .then(ScriptedLlm.text("done")),
                tools, readThenReadOnly());

        assertThat(entered).containsExactly("fetch");
    }

    @Test
    void aParkedCallLowersNothingBecauseItRanNothing() {
        List<String> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(publisher(entered));
        ToolActivitiesImpl activities = new ToolActivitiesImpl(tools,
                TrustFloor.afterAnythingUndeclared(
                        ToolGates.parkForApproval(invocation -> true,
                                dev.agentkit.core.reliability.ApprovalNeeded.because("ask")),
                        ToolGates.readOnly()));

        ToolOutcome parked = activities.executeTool(
                new dev.agentkit.core.tool.ToolInvocation("t1", "publish", Map.of()));

        assertThat(parked.parked()).isPresent();
        assertThat(parked.lowered())
                .as("nothing ran, so nothing was read")
                .isFalse();
    }

    @Test
    void anApprovedReadStillLowersTheFloor() {
        // A floor whose *ordinary* policy parks — put an approval on your reads, which is
        // prudent — never lowered at all, because resumeTool returned a bare ToolResult and
        // could not report one. So: approve the fetch, and the write after it ran unguarded.
        List<String> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(fetcher(entered)).register(publisher(entered));
        ToolActivitiesImpl activities = new ToolActivitiesImpl(tools,
                TrustFloor.afterThirdParty(
                        ToolGates.parkForApproval(invocation -> invocation.name().equals("fetch"),
                                dev.agentkit.core.reliability.ApprovalNeeded.because("ask first")),
                        ToolGates.readOnly()));
        dev.agentkit.core.tool.ToolInvocation read =
                new dev.agentkit.core.tool.ToolInvocation("t1", "fetch", Map.of());

        ToolOutcome parked = activities.executeTool(read);
        ToolOutcome resumed = activities.resumeTool(read,
                ApprovalVerdict.approve(parked.parked().orElseThrow(), "alice"));

        assertThat(entered).containsExactly("fetch");
        assertThat(resumed.lowered())
                .as("a person approving a read has approved reading a page, not everything"
                        + " the model does with the page afterwards")
                .isTrue();
    }

    @Test
    void aResumedCallIsGatedByThePolicyThatParkedIt() {
        // Measured before this: with the composition the README recommends
        // (ordinarily = ALLOW_ALL, onceLowered = parkForApproval), resuming under the
        // ordinary policy meant the tightened gate never parked, the verdict branch was
        // skipped, and the call fell through to run the model's ORIGINAL invocation — a
        // reviewer's edit discarded and every other restriction of the tightened gate
        // waived by the act of approving.
        List<dev.agentkit.core.tool.ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(
                FunctionTool.builder("publish", "Publishes")
                        .sideEffects(SideEffects.EXTERNAL)
                        .handler(invocation -> {
                            entered.add(invocation);
                            return ToolResult.ok("published");
                        }).build());
        ToolActivitiesImpl activities = new ToolActivitiesImpl(tools,
                TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, ToolGates.allOf(
                        (tool, invocation) -> invocation.name().equals("publish")
                                ? dev.agentkit.core.reliability.GateResult.allowWith(
                                        new dev.agentkit.core.tool.ToolInvocation(invocation.id(),
                                                invocation.name(), Map.of("text", "just the summary")))
                                : dev.agentkit.core.reliability.GateResult.allow(),
                        ToolGates.parkForApproval(invocation -> invocation.name().equals("publish"),
                                dev.agentkit.core.reliability.ApprovalNeeded.because("read the web")))));
        dev.agentkit.core.tool.ToolInvocation proposed = new dev.agentkit.core.tool.ToolInvocation(
                "t1", "publish", Map.of("text", "EVERY SECRET"));

        ToolOutcome parked = activities.executeToolUnderLoweredTrust(proposed);
        activities.resumeToolUnderLoweredTrust(proposed, ApprovalVerdict.approveWithArguments(
                parked.parked().orElseThrow(), "alice", Map.of("text", "just the summary")));

        assertThat(entered).hasSize(1);
        assertThat(entered.get(0).stringArgument("text")).isEqualTo("just the summary");
    }

    @Test
    void aRunWithNoFloorNeverSwitchesToTheTightenedEntryPoint() {
        // A plain gate used to be stored as afterThirdParty(gate, gate), which lowers — so
        // an opted-out deployment reported a lowering on its first THIRD_PARTY result and
        // the workflow switched activity method mid-run. Every MCP tool declares
        // THIRD_PARTY, so that was most MCP deployments, and it changes the activity type a
        // run schedules partway through.
        List<String> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(fetcher(entered));
        ToolActivitiesImpl activities = new ToolActivitiesImpl(tools, ToolGate.ALLOW_ALL);

        ToolOutcome outcome = activities.executeTool(
                new dev.agentkit.core.tool.ToolInvocation("t1", "fetch", Map.of()));

        assertThat(outcome.lowered()).isFalse();
    }

    @Test
    void approvingAReadStillCostsTheRunItsWriteCapability() throws Exception {
        // The end-to-end version of the test above, and the one that matters: the activity
        // reporting a lowering is no use if the workflow drops it. A mutant that left
        // loweredHere false on the resume branch survived all 121 temporal tests, because
        // the only test of this rule called the activity directly.
        //
        // The shape is a deployment that puts an approval on its reads — prudent, and
        // exactly where the hole was: approve the fetch, and the write after it ran
        // unguarded.
        List<String> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(fetcher(entered)).register(publisher(entered));
        TrustFloor floor = TrustFloor.afterThirdParty(
                ToolGates.parkForApproval(invocation -> invocation.name().equals("fetch"),
                        dev.agentkit.core.reliability.ApprovalNeeded.because("ask before reading")),
                ToolGates.readOnly());

        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(TemporalAgent.dataConverter())
                        .build())
                .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder()
                        .setWorkflowCacheSize(0)
                        .build())
                .build());
        TemporalAgent.register(env.newWorker(TASK_QUEUE), new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "fetch", Map.of()))
                .then(ScriptedLlm.toolUse("t2", "publish", Map.of()))
                .then(ScriptedLlm.text("done")), tools, floor);
        env.start();
        AgentWorkflow stub = TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE);
        java.util.concurrent.CompletableFuture<AgentRunResult> running =
                io.temporal.client.WorkflowClient.execute(stub::run,
                        DurableAgentRun.of(Goal.of("do it"),
                                AgentConfig.builder("m").maxSteps(6).build(),
                                tools.advertisedSpecs()));

        dev.agentkit.core.reliability.PendingApproval parked = null;
        for (int attempt = 0; attempt < 200 && parked == null; attempt++) {
            List<dev.agentkit.core.reliability.PendingApproval> pending = stub.pendingApprovals();
            if (!pending.isEmpty()) {
                parked = pending.get(0);
            } else {
                Thread.sleep(25);
            }
        }
        stub.decide(ApprovalVerdict.approve(parked, "alice"));
        running.get(30, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(entered)
                .as("a person approving a read has approved reading a page, not everything"
                        + " the model does with the page afterwards")
                .containsExactly("fetch");
    }

    @Test
    void theWorkflowResumesUnderTheParkingPolicy() throws Exception {
        // The workflow half of the resume fix, which had no test — a mutant restoring the
        // pre-fix `activities.resumeTool(...)` survived all 122 temporal tests and
        // reproduced the original exploit exactly. The activity half was tested. That is
        // the same mistake confessed two commits earlier — proving a bit is produced and
        // never that anybody uses it — applied to the other hole. And it is the composition
        // the README recommends, so it was the one durable path with no end-to-end cover.
        List<dev.agentkit.core.tool.ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry()
                .register(fetcher(new ArrayList<>()))
                .register(FunctionTool.builder("publish", "Publishes")
                        .sideEffects(SideEffects.EXTERNAL)
                        .handler(invocation -> {
                            entered.add(invocation);
                            return ToolResult.ok("published");
                        }).build());

        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(TemporalAgent.dataConverter())
                        .build())
                .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder()
                        .setWorkflowCacheSize(0)
                        .build())
                .build());
        TemporalAgent.register(env.newWorker(TASK_QUEUE), new ScriptedLlm()
                        .then(ScriptedLlm.toolUse("t1", "fetch", Map.of()))
                        .then(ScriptedLlm.toolUse("t2", "publish", Map.of("text", "EVERY SECRET")))
                        .then(ScriptedLlm.text("done")), tools,
                TrustFloor.afterThirdParty(ToolGate.ALLOW_ALL, ToolGates.allOf(
                        (tool, call) -> call.name().equals("publish")
                                ? dev.agentkit.core.reliability.GateResult.allowWith(
                                        new dev.agentkit.core.tool.ToolInvocation(call.id(),
                                                call.name(),
                                                Map.of("text", "just the summary")))
                                : dev.agentkit.core.reliability.GateResult.allow(),
                        ToolGates.parkForApproval(call -> call.name().equals("publish"),
                                dev.agentkit.core.reliability.ApprovalNeeded.because("read the web")))));
        env.start();
        AgentWorkflow stub = TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE);
        java.util.concurrent.CompletableFuture<AgentRunResult> running =
                io.temporal.client.WorkflowClient.execute(stub::run,
                        DurableAgentRun.of(Goal.of("do it"),
                                AgentConfig.builder("m").maxSteps(6).build(),
                                tools.advertisedSpecs()));

        dev.agentkit.core.reliability.PendingApproval parked = null;
        for (int attempt = 0; attempt < 200 && parked == null; attempt++) {
            List<dev.agentkit.core.reliability.PendingApproval> pending = stub.pendingApprovals();
            if (!pending.isEmpty()) {
                parked = pending.get(0);
            } else {
                Thread.sleep(25);
            }
        }
        assertThat(parked.invocation().stringArgument("text"))
                .as("the reviewer is shown what the tightened policy narrowed it to")
                .isEqualTo("just the summary");
        stub.decide(ApprovalVerdict.approve(parked, "alice"));
        running.get(30, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(entered).hasSize(1);
        assertThat(entered.get(0).stringArgument("text"))
                .as("and a plain approval runs that, not the model's proposal — which is"
                        + " only true if the resume re-ran the tightened policy")
                .isEqualTo("just the summary");
    }

    @Test
    void aFailingFirstPartyToolDoesNotLowerTheFloorDurablyEither() {
        // The control its third-party twin was missing: without it that test also passes
        // for an implementation where *any* throw lowers the floor. Its in-process sibling
        // has this control; the durable one was copied without it, and the mutant survived.
        List<String> entered = new ArrayList<>();
        Tool failsWithOurOwnWords = FunctionTool.builder("lookup", "Reads our own table")
                .sideEffects(SideEffects.NONE)
                .provenance(Provenance.FIRST_PARTY)
                .handler(invocation -> {
                    entered.add("lookup");
                    throw new IllegalStateException("our own table is down");
                })
                .build();

        runWith(new ScriptedLlm()
                        .then(ScriptedLlm.toolUse("t1", "lookup", Map.of()))
                        .then(ScriptedLlm.toolUse("t2", "publish", Map.of()))
                        .then(ScriptedLlm.text("done")),
                new SimpleToolRegistry().register(failsWithOurOwnWords).register(publisher(entered)),
                readThenReadOnly());

        assertThat(entered).containsExactly("lookup", "publish");
    }

    @Test
    void anApprovalStandsEvenAfterTheGateStopsParking() {
        // Policy drift in the approve direction. The verdict was read only inside the
        // parking branch, so a gate loosened while the call sat on somebody's desk meant
        // the branch was skipped and the *proposed* invocation ran — the reviewer's edit
        // discarded. The refusal direction was already handled; this is its other half.
        List<dev.agentkit.core.tool.ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(
                FunctionTool.builder("publish", "Publishes")
                        .sideEffects(SideEffects.EXTERNAL)
                        .handler(invocation -> {
                            entered.add(invocation);
                            return ToolResult.ok("published");
                        }).build());
        dev.agentkit.core.tool.ToolInvocation proposed =
                new dev.agentkit.core.tool.ToolInvocation("t1", "publish",
                        Map.of("text", "EVERY SECRET"));

        new ToolActivitiesImpl(tools, ToolGate.ALLOW_ALL).resumeTool(proposed,
                ApprovalVerdict.approveWithArguments(
                        new dev.agentkit.core.reliability.PendingApproval(proposed,
                                dev.agentkit.core.reliability.ApprovalNeeded.because("ask"),
                                "park-1"),
                        "alice", Map.of("text", "just the summary")));

        assertThat(entered).hasSize(1);
        assertThat(entered.get(0).stringArgument("text")).isEqualTo("just the summary");
    }
}
