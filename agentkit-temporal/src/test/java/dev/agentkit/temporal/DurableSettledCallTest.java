package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Which call Temporal history says a durable run actually made (#179).
 *
 * <h2>Why this file exists at all</h2>
 *
 * <p>The durable path has no {@code AgentObserver} — {@code AgentWorkflowImpl}'s javadoc
 * says so — so <strong>Temporal history is the audit trail</strong>. History records an
 * activity's input, which is the call the model proposed, and its output, which is a
 * {@link ToolOutcome}. Until this change the output said nothing about which call ran, and
 * {@code ToolActivitiesImpl} unwrapped the gate's replacement inside a private helper where
 * nothing could see it. Measured on the in-memory test server, with a gate that narrows a
 * publish:
 *
 * <pre>
 * history activity input  = {"id":"t1","name":"publish","arguments":{"text":"/etc/shadow"}}
 * history activity result = {"content":"published","isError":false,"provenance":"UNKNOWN",
 *                            "awaiting":null,"lowersTrust":false}
 * tool actually ran       = [/tmp/harmless.txt]
 *
 * after: history activity result gains
 *   "effective":{"id":"t1","name":"publish","arguments":{"text":"/tmp/harmless.txt"}}
 * </pre>
 *
 * <p>Every trace of the call in history was the proposal a narrowing gate existed to
 * overrule. That is #131's defect — an audit record must report the call the gate
 * <em>settled on</em> — arriving on the third of this repository's five runners.
 *
 * <h2>What "settled on" means, and why it is not "what the tool received"</h2>
 *
 * <p>{@code AgentObserver.onToolResult}'s javadoc carries the argument; these tests are the
 * durable half of it, one per path, because the first attempt at #131 stated one rule and
 * got two paths wrong under it. A <strong>park</strong> reports the gate's narrowed call
 * though no tool received anything, because that is what a reviewer is shown and what runs
 * on approval. A <strong>denied</strong> call and a <strong>gate that threw</strong> report
 * the proposal, because there is literally no second invocation. A <strong>tool that
 * threw</strong> reports what it was handed.
 *
 * <p>The assertions below compare the recorded call against the invocation the
 * <em>tool</em> was entered with wherever a tool ran, rather than against a value the test
 * computed. Asserting the record equals the gate's own replacement would pass for a runner
 * that recorded the replacement and then ran something else, which is the shape this
 * repository has shipped twice.
 */
class DurableSettledCallTest {

    private static final String TASK_QUEUE = "agentkit-settled-test";

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    // --- the world ---------------------------------------------------------------

    private static final ToolInvocation PROPOSED =
            new ToolInvocation("t1", "publish", Map.of("text", "/etc/shadow"));

    /** A tool that records every invocation it was actually entered with. */
    private static FunctionTool publisher(List<ToolInvocation> entered) {
        return FunctionTool.builder("publish", "Publishes text somewhere public")
                .schema(Map.of("type", "object",
                        "properties", Map.of("text", Map.of("type", "string"))))
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> {
                    entered.add(invocation);
                    return ToolResult.ok("published");
                })
                .build();
    }

    /** The same tool, entered and then failing part-way through. */
    private static FunctionTool failingPublisher(List<ToolInvocation> entered) {
        return FunctionTool.builder("publish", "Publishes text somewhere public")
                .schema(Map.of("type", "object",
                        "properties", Map.of("text", Map.of("type", "string"))))
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> {
                    entered.add(invocation);
                    throw new IllegalStateException("the endpoint went away mid-publish");
                })
                .build();
    }

    /** The same tool again, leaving through the {@code Throwable} catch and not the other. */
    private static FunctionTool erroringPublisher(List<ToolInvocation> entered) {
        return FunctionTool.builder("publish", "Publishes text somewhere public")
                .schema(Map.of("type", "object",
                        "properties", Map.of("text", Map.of("type", "string"))))
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> {
                    entered.add(invocation);
                    throw new StackOverflowError("the tool recursed");
                })
                .build();
    }

    /**
     * A gate that parks the first call it sees and plainly allows every one after.
     *
     * <p>Policy moving while a call sits on somebody's desk, which is the branch
     * {@code gated} has for it: on the resume the gate no longer parks, so the verdict is
     * applied outside the park branch and against the call as it arrived.
     */
    private static ToolGate parksOnceThenAllows() {
        AtomicInteger seen = new AtomicInteger();
        return (tool, invocation) -> seen.getAndIncrement() == 0
                ? GateResult.needsAPerson(ApprovalNeeded.because("a person must look"),
                        new ToolInvocation(invocation.id(), invocation.name(),
                                Map.of("text", "/tmp/parked.txt")))
                : GateResult.allow();
    }

    private static ToolGate narrowingTo(String text) {
        return (tool, invocation) -> GateResult.allowWith(new ToolInvocation(
                invocation.id(), invocation.name(), Map.of("text", text)));
    }

    private static ApprovalVerdict approving(String invocationId) {
        return new ApprovalVerdict("park-1", invocationId, ApprovalDecision.Kind.APPROVE, "",
                null, "alice");
    }

    // --- the paths ---------------------------------------------------------------

    @Test
    void aNarrowedCallIsRecordedAsTheCallTheToolWasHanded() {
        // The defect. Before this, the outcome carried no invocation at all and the only
        // call named anywhere in history was /etc/shadow, which is precisely what the gate
        // had overruled.
        List<ToolInvocation> entered = new ArrayList<>();
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(publisher(entered)),
                narrowingTo("/tmp/harmless.txt"));

        ToolOutcome outcome = activities.executeTool(PROPOSED);

        assertThat(entered).hasSize(1);
        assertThat(outcome.settled(PROPOSED))
                .as("history named the model's proposal for a call the gate had narrowed")
                .isEqualTo(entered.get(0));
        assertThat(outcome.settled(PROPOSED).stringArgument("text"))
                .isEqualTo("/tmp/harmless.txt");
    }

    @Test
    void anUngatedCallRecordsTheProposalBecauseNothingNarrowedIt() {
        // The positive control for the test above: a record that always differed from the
        // proposal would be as wrong in the other direction, and would pass a test that
        // only asserted "not the proposal".
        List<ToolInvocation> entered = new ArrayList<>();
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(publisher(entered)), ToolGate.ALLOW_ALL);

        ToolOutcome outcome = activities.executeTool(PROPOSED);

        assertThat(outcome.settled(PROPOSED)).isEqualTo(PROPOSED).isEqualTo(entered.get(0));
    }

    @Test
    void aDeniedCallRecordsTheProposalBecauseThereIsNoSecondInvocation() {
        List<ToolInvocation> entered = new ArrayList<>();
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(publisher(entered)),
                (tool, invocation) -> GateResult.deny("not on a durable run"));

        ToolOutcome outcome = activities.executeTool(PROPOSED);

        assertThat(entered).isEmpty();
        assertThat(outcome.settled(PROPOSED))
                .as("nothing ran and nothing was narrowed, so the proposal is the only "
                        + "call there is to name")
                .isEqualTo(PROPOSED);
    }

    @Test
    void aParkedCallRecordsTheGatesNarrowedCallThoughNoToolReceivedIt() {
        // The path that makes "what the tool received" a lie rather than an approximation.
        // A gate may narrow and then park, and the reviewer must be shown — and the resume
        // must run — the narrowed call, which is why needsAPerson carries a replacement at
        // all (#104). Reporting the proposal here would be the worse repair.
        List<ToolInvocation> entered = new ArrayList<>();
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(publisher(entered)),
                (tool, invocation) -> GateResult.needsAPerson(
                        ApprovalNeeded.because("a person must look"),
                        new ToolInvocation(invocation.id(), invocation.name(),
                                Map.of("text", "/tmp/harmless.txt"))));

        ToolOutcome outcome = activities.executeTool(PROPOSED);

        assertThat(entered).as("a park runs nothing").isEmpty();
        assertThat(outcome.settled(PROPOSED).stringArgument("text"))
                .as("the reviewer is shown the narrowed call, so the record must name it")
                .isEqualTo("/tmp/harmless.txt");
        // And it is read from the park's own copy rather than stored twice: a large
        // argument map already costs four writes into history on this path, and #137 is the
        // issue about that growth being what stalls a run.
        assertThat(outcome.effective())
                .as("the parked call's arguments were written into history a fifth time")
                .isNull();
        assertThat(outcome.settled(PROPOSED))
                .isEqualTo(outcome.parked().orElseThrow().invocation());
    }

    @Test
    void aGateThatThrewRecordsTheProposalBecauseItSettledOnNothing() {
        List<ToolInvocation> entered = new ArrayList<>();
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(publisher(entered)),
                (tool, invocation) -> {
                    throw new IllegalStateException("the policy service is down");
                });

        ToolOutcome outcome = activities.executeTool(PROPOSED);

        assertThat(entered).isEmpty();
        assertThat(outcome.result().isError()).isTrue();
        assertThat(outcome.settled(PROPOSED))
                .as("a gate that threw reached no verdict, so there is no narrowed call "
                        + "to name and inventing one would be a fabricated audit fact")
                .isEqualTo(PROPOSED);
    }

    @Test
    void aReplacementThatWasRefusedRecordsTheProposal() {
        // effectiveFor throws when a replacement renames the tool (#104), which is a gate
        // author's mistake at an authorization boundary: nothing runs. It throws from the
        // same statement that assigns the recorded call, so the record still holds the
        // proposal — which is the truth, since the refused replacement never became a call.
        List<ToolInvocation> entered = new ArrayList<>();
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(publisher(entered)),
                (tool, invocation) -> GateResult.allowWith(
                        new ToolInvocation(invocation.id(), "draft", invocation.arguments())));

        ToolOutcome outcome = activities.executeTool(PROPOSED);

        assertThat(entered).isEmpty();
        assertThat(outcome.result().isError()).isTrue();
        assertThat(outcome.settled(PROPOSED)).isEqualTo(PROPOSED);
    }

    @Test
    void aToolThatThrewRecordsWhatItWasHandedAndNotWhatWasProposed() {
        // The failure branches are audited too. A tool that threw part-way through has
        // done whatever it did before throwing, and with what is the question asked
        // afterwards — so this is the path where naming the proposal misleads most.
        List<ToolInvocation> entered = new ArrayList<>();
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(failingPublisher(entered)),
                narrowingTo("/tmp/harmless.txt"));

        ToolOutcome outcome = activities.executeTool(PROPOSED);

        assertThat(entered).hasSize(1);
        assertThat(outcome.result().isError()).isTrue();
        assertThat(outcome.settled(PROPOSED))
                .as("the record named a call the tool was never entered with")
                .isEqualTo(entered.get(0));
    }

    @Test
    void aReviewersEditIsWhatTheResumedCallRecords() {
        // The verdict's own replacement goes through effectiveFor exactly as a gate's does,
        // and the resumed call is the one recorded. Two narrowings in sequence — the gate's
        // before the park, the person's on the resume — so a record that stopped at either
        // one is visible.
        List<ToolInvocation> entered = new ArrayList<>();
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(publisher(entered)),
                (tool, invocation) -> GateResult.needsAPerson(
                        ApprovalNeeded.because("a person must look"),
                        new ToolInvocation(invocation.id(), invocation.name(),
                                Map.of("text", "/tmp/harmless.txt"))));

        ToolOutcome parked = activities.executeTool(PROPOSED);
        ToolInvocation resumed = parked.parked().orElseThrow().invocation();
        ToolOutcome outcome = activities.resumeTool(resumed,
                new ApprovalVerdict("park-1", "t1", ApprovalDecision.Kind.APPROVE_WITH_ARGUMENTS,
                        "", Map.of("text", "/tmp/reviewed.txt"), "alice"));

        assertThat(entered).hasSize(1);
        assertThat(outcome.settled(resumed))
                .as("the reviewer's edit ran and the record named the pre-edit call")
                .isEqualTo(entered.get(0));
        assertThat(outcome.settled(resumed).stringArgument("text")).isEqualTo("/tmp/reviewed.txt");
    }

    @Test
    void aPlainApprovalRecordsTheCallTheReviewerSaw() {
        // The other half: an approval that edits nothing still records the gate's narrowed
        // call, not the model's proposal, because that is what runs.
        List<ToolInvocation> entered = new ArrayList<>();
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(publisher(entered)),
                (tool, invocation) -> GateResult.needsAPerson(
                        ApprovalNeeded.because("a person must look"),
                        new ToolInvocation(invocation.id(), invocation.name(),
                                Map.of("text", "/tmp/harmless.txt"))));

        ToolInvocation resumed = activities.executeTool(PROPOSED)
                .parked().orElseThrow().invocation();
        ToolOutcome outcome = activities.resumeTool(resumed, approving("t1"));

        assertThat(outcome.settled(resumed)).isEqualTo(entered.get(0));
        assertThat(outcome.settled(resumed).stringArgument("text")).isEqualTo("/tmp/harmless.txt");
    }

    @Test
    void anUnknownToolRecordsTheProposalWithoutConsultingAnything() {
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(publisher(new ArrayList<>())),
                ToolGate.ALLOW_ALL);
        ToolInvocation missing = new ToolInvocation("t1", "no-such-tool", Map.of("text", "x"));

        ToolOutcome outcome = activities.executeTool(missing);

        assertThat(outcome.result().isError()).isTrue();
        assertThat(outcome.settled(missing)).isEqualTo(missing);
    }

    @Test
    void theNarrowingIsUnwrappedOnceSoTheRecordAndTheExecutionCannotDisagree() {
        // A gate asked twice may answer differently, and the second answer is the one the
        // auditor reads. This gate narrows to a new value on every evaluation, so a runner
        // that re-derived the effective call to record it would file a call that never ran
        // — and the two values would be one apart, which no equality against a constant
        // could catch. The assertion is the tool's own invocation against the record.
        AtomicInteger evaluations = new AtomicInteger();
        List<ToolInvocation> entered = new ArrayList<>();
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(publisher(entered)),
                (tool, invocation) -> GateResult.allowWith(new ToolInvocation(
                        invocation.id(), invocation.name(),
                        Map.of("text", "/tmp/" + evaluations.incrementAndGet() + ".txt"))));

        ToolOutcome outcome = activities.executeTool(PROPOSED);

        assertThat(entered).hasSize(1);
        assertThat(evaluations.get())
                .as("the gate was consulted more than once for one call")
                .isEqualTo(1);
        assertThat(outcome.settled(PROPOSED)).isEqualTo(entered.get(0));
    }

    @Test
    void aVerdictAppliedAfterThePolicyStoppedParkingRecordsTheReviewersEdit() {
        // The branch for policy that moved while the call sat on somebody's desk: the gate
        // has stopped parking and now simply allows, so the verdict is applied outside the
        // park branch, by a second `effectiveFor` call against the call as it arrived. A
        // mutant recording the arriving call instead survived every other test in this
        // file — reaching this branch needs a gate that answers differently on the resume,
        // and a verdict that edits, and nothing else in the suite had both.
        List<ToolInvocation> entered = new ArrayList<>();
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(publisher(entered)), parksOnceThenAllows());

        ToolInvocation resumed = activities.executeTool(PROPOSED)
                .parked().orElseThrow().invocation();
        ToolOutcome outcome = activities.resumeTool(resumed,
                new ApprovalVerdict("park-1", "t1", ApprovalDecision.Kind.APPROVE_WITH_ARGUMENTS,
                        "", Map.of("text", "/tmp/reviewed.txt"), "alice"));

        assertThat(entered).hasSize(1);
        assertThat(outcome.settled(resumed))
                .as("the reviewer's edit ran and the record named the parked call")
                .isEqualTo(entered.get(0));
        assertThat(outcome.settled(resumed).stringArgument("text")).isEqualTo("/tmp/reviewed.txt");
    }

    @Test
    void aToolThatThrewAnErrorRecordsWhatItWasHandedToo() {
        // The second catch, which exists because an Error is not a RuntimeException and a
        // tool that threw one has still done whatever it did first. It is a separate return
        // statement from the RuntimeException catch's, so it is a separate place to get the
        // recorded call wrong — and a mutant that did survived until this test existed.
        List<ToolInvocation> entered = new ArrayList<>();
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(erroringPublisher(entered)),
                narrowingTo("/tmp/harmless.txt"));

        ToolOutcome outcome = activities.executeTool(PROPOSED);

        assertThat(entered).hasSize(1);
        assertThat(outcome.result().isError()).isTrue();
        assertThat(outcome.settled(PROPOSED))
                .as("the record named a call the tool was never entered with")
                .isEqualTo(entered.get(0));
    }

    @Test
    void theRecordedComponentIsTrustedAheadOfTheParksCopy() {
        // settled() states an order its three sources are trusted in, and no runner here
        // writes a payload with two of them set — so the order was documented and nothing
        // held it. A mutant swapping the first two survived. Pinned directly on the record,
        // because that is the only place the disagreement can be constructed: a payload
        // from a future writer, or from another runner, that records both.
        ToolInvocation proposed = new ToolInvocation("t1", "publish", Map.of("text", "proposed"));
        ToolInvocation recorded = new ToolInvocation("t1", "publish", Map.of("text", "recorded"));
        ToolOutcome both = new ToolOutcome("parked", true,
                dev.agentkit.core.tool.Provenance.FIRST_PARTY,
                new dev.agentkit.core.reliability.PendingApproval(
                        new ToolInvocation("t1", "publish", Map.of("text", "parked")),
                        ApprovalNeeded.because("a person must look")),
                false, recorded, dev.agentkit.core.tool.Disposition.PARKED, false);

        assertThat(both.settled(proposed))
                .as("the park's copy outranked the component written for this very purpose")
                .isEqualTo(recorded);
    }

    // --- and it reaches history ---------------------------------------------------

    @Test
    void theExecutedCallIsInTheHistoryPayloadAndNotOnlyInTheReturnedObject() {
        // Everything above asserts on the value the activity returned in process. This one
        // asserts on the bytes Temporal wrote, because that is the artefact a reviewer
        // actually opens — and because a component the data converter dropped would pass
        // every test above.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry tools = new SimpleToolRegistry().register(publisher(entered));
        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(TemporalAgent.dataConverter())
                        .build())
                .build());
        TemporalAgent.register(env.newWorker(TASK_QUEUE), new ScriptedLlm()
                        .then(ScriptedLlm.toolUse("t1", "publish", Map.of("text", "/etc/shadow")))
                        .then(ScriptedLlm.text("done")),
                tools, narrowingTo("/tmp/harmless.txt"));
        env.start();
        AgentWorkflow stub = TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE);

        stub.run(DurableAgentRun.of(Goal.of("publish something"),
                AgentConfig.builder("m").maxSteps(5).build(), tools.advertisedSpecs()));

        List<String> activityResults = env.getWorkflowClient()
                .fetchHistory(WorkflowStub.fromTyped(stub).getExecution().getWorkflowId())
                .getHistory().getEventsList().stream()
                .filter(e -> e.hasActivityTaskCompletedEventAttributes())
                .map(e -> e.getActivityTaskCompletedEventAttributes().getResult()
                        .getPayloads(0).getData().toStringUtf8())
                .toList();

        assertThat(entered).hasSize(1);
        // Read back through the same converter the workflow replays with, and asked the
        // same question a reviewer asks — not a substring search, which would pass for a
        // payload that merely mentioned the string somewhere.
        assertThat(activityResults)
                .as("nothing in history named the call that ran; every trace of it was the "
                        + "proposal the gate had overruled")
                .anySatisfy(json -> assertThat(DurableJson.objectMapper()
                        .readValue(json, ToolOutcome.class)
                        .settled(new ToolInvocation("t1", "publish",
                                Map.of("text", "/etc/shadow"))))
                        .isEqualTo(entered.get(0)));
    }

    /**
     * A gate that parks the first call it sees and <em>narrows</em> every one after.
     *
     * <p>{@link #parksOnceThenAllows} is the same drift with a gate that plainly allows,
     * which is the only drift the suite had. The difference is the whole defect: a plainly
     * allowing gate settles on the call it was handed, so a branch that never asked it what
     * it had settled on agreed with it by accident.
     */
    private static ToolGate parksOnceThenNarrowsTo(String text) {
        AtomicInteger seen = new AtomicInteger();
        return (tool, invocation) -> seen.getAndIncrement() == 0
                ? GateResult.needsAPerson(ApprovalNeeded.because("a person must look"),
                        new ToolInvocation(invocation.id(), invocation.name(),
                                Map.of("text", "/tmp/parked.txt")))
                : GateResult.allowWith(new ToolInvocation(invocation.id(), invocation.name(),
                        Map.of("text", text)));
    }

    @Test
    void aGateThatNarrowsAfterItStoppedParkingIsStillObeyed() {
        // The drift branch built its GateResult from the call as it ARRIVED and never asked
        // `decision` what it had settled on, so a gate that narrows on the resume was
        // overruled by the stale call the reviewer had been shown. Measured before the fix:
        //
        //   gate on the resume said : allowWith({"text":"/tmp/narrowed.txt"})
        //   tool was entered with   : {"text":"/tmp/parked.txt"}
        //   history recorded        : {"text":"/tmp/parked.txt"}
        //
        // A narrowing the policy in force asked for, dropped at an authorization boundary,
        // with the record and the execution agreeing — because both were wrong, which is
        // why #179's own suite could not see it. Every other test in this file has either a
        // gate that keeps parking (so the park branch runs) or a drifted gate that plainly
        // allows (so `invocation` and the gate's settled call are the same object).
        List<ToolInvocation> entered = new ArrayList<>();
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(publisher(entered)),
                parksOnceThenNarrowsTo("/tmp/narrowed.txt"));

        ToolInvocation resumed = activities.executeTool(PROPOSED)
                .parked().orElseThrow().invocation();
        ToolOutcome outcome = activities.resumeTool(resumed, approving("t1"));

        assertThat(entered).hasSize(1);
        assertThat(entered.get(0).stringArgument("text"))
                .as("the tool ran the call the gate in force settled on")
                .isEqualTo("/tmp/narrowed.txt");
        assertThat(outcome.settled(resumed))
                .as("and history records that same call, not the one the reviewer saw")
                .isEqualTo(entered.get(0));
    }

    @Test
    void aReviewersEditIsAppliedOnTopOfTheDriftedGatesNarrowingAndNotInsteadOfIt() {
        // The other order of the same two narrowings. A verdict may edit arguments, and an
        // edit replaces the argument map wholesale — but the call it edits has to be the one
        // the gate settled on, because that is where the id and the name come from and
        // because effectiveFor is what refuses a rename.
        //
        // Honest about what this one is: it passes against the unfixed code too, because an
        // edit overwrites the arguments either way. It is a pin and not a proof — the
        // composition order is what the two tests around it turn on, and a future
        // simplification that applied the verdict first would break them and not this.
        // The proofs are the narrowing above and the rename below.
        List<ToolInvocation> entered = new ArrayList<>();
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(publisher(entered)),
                parksOnceThenNarrowsTo("/tmp/narrowed.txt"));

        ToolInvocation resumed = activities.executeTool(PROPOSED)
                .parked().orElseThrow().invocation();
        ToolOutcome outcome = activities.resumeTool(resumed,
                new ApprovalVerdict("park-1", "t1", ApprovalDecision.Kind.APPROVE_WITH_ARGUMENTS,
                        "", Map.of("text", "/tmp/reviewed.txt"), "alice"));

        assertThat(entered).hasSize(1);
        assertThat(entered.get(0).stringArgument("text")).isEqualTo("/tmp/reviewed.txt");
        assertThat(outcome.settled(resumed)).isEqualTo(entered.get(0));
    }

    @Test
    void aDriftedGateThatRenamesTheToolIsRefusedRatherThanIgnored() {
        // GateResult.effectiveFor refuses a replacement that redirects to another tool, and
        // every other branch of `gated` puts the gate's answer through it. The drift branch
        // did not, so on that one path a gate's redirect was silently discarded and the call
        // it was trying to redirect away from ran. Refused now, like everywhere else — and
        // refused is the fail-closed direction: the model is told, the tool did not run.
        List<ToolInvocation> entered = new ArrayList<>();
        AtomicInteger seen = new AtomicInteger();
        ToolActivitiesImpl activities = new ToolActivitiesImpl(
                new SimpleToolRegistry().register(publisher(entered)),
                (tool, invocation) -> seen.getAndIncrement() == 0
                        ? GateResult.needsAPerson(ApprovalNeeded.because("a person must look"),
                                new ToolInvocation(invocation.id(), invocation.name(),
                                        Map.of("text", "/tmp/parked.txt")))
                        : GateResult.allowWith(new ToolInvocation(invocation.id(),
                                "draft", Map.of("text", "/tmp/parked.txt"))));

        ToolInvocation resumed = activities.executeTool(PROPOSED)
                .parked().orElseThrow().invocation();
        ToolOutcome outcome = activities.resumeTool(resumed, approving("t1"));

        assertThat(entered).as("nothing ran; the redirect was refused").isEmpty();
        assertThat(outcome.result().isError()).isTrue();
        assertThat(outcome.settled(resumed))
                .as("a gate that settled on nothing reports the call as it arrived")
                .isEqualTo(resumed);
    }

    // --- the operator's line -------------------------------------------------------

    /**
     * The one line that says a verdict was applied, and which of the two ways in it came.
     *
     * <p><strong>Written because a mutation pass found nothing else could see it.</strong>
     * The two branches that read a verdict — the gate still parks, or policy drifted while
     * the call sat on somebody's desk — used to emit an operator line each, and {@code
     * if (true)} over the pair survived all 167 tests in this module. The first reading of
     * that was "this repository wires {@code slf4j-simple} and captures logs nowhere, so no
     * log line here is killable". That inference was wrong and it is worth saying why,
     * because it nearly cost the line its only check: {@code slf4j-simple} writes to {@code
     * System.err} and resolves the stream per write rather than caching it, so setting
     * {@code System.err} around the call does capture it. {@code
     * SupervisorTest.aCutGoalIsReportedToTheOperatorAndNotOnlyToTheModel} (#142) and {@code
     * GoapObserverThrowsTest.theDefaultHandlerSaysSomethingRatherThanNothing} (#173) are
     * the same technique; the first of those was a real defect that survived a whole suite
     * for the same reason.
     *
     * <p>The distinction is worth a test rather than only a branch. The durable path has no
     * {@code AgentObserver} — {@code AgentWorkflowImpl}'s javadoc says so — so for anything
     * that is not in the activity result, this line is the entire report. A verdict being
     * spent against a policy that has moved since it was asked for is exactly the thing an
     * operator would want to find afterwards, and #58 was "nothing failed and nothing
     * logged".
     *
     * <p>Both ways in are driven, and each asserts the other's word is absent, so a line
     * that reported one posture for both cases fails rather than half-passing.
     */
    @Test
    void theOperatorIsToldWhetherTheGateStillParkedTheCallOrHadStoppedParkingIt() {
        String whenItStillParks = errWhile(() -> {
            List<ToolInvocation> entered = new ArrayList<>();
            ToolActivitiesImpl activities = new ToolActivitiesImpl(
                    new SimpleToolRegistry().register(publisher(entered)),
                    (tool, invocation) -> GateResult.needsAPerson(
                            ApprovalNeeded.because("a person must look"),
                            new ToolInvocation(invocation.id(), invocation.name(),
                                    Map.of("text", "/tmp/harmless.txt"))));
            ToolInvocation resumed = activities.executeTool(PROPOSED)
                    .parked().orElseThrow().invocation();
            activities.resumeTool(resumed, approving("t1"));
        });

        String whenItHasDrifted = errWhile(() -> {
            List<ToolInvocation> entered = new ArrayList<>();
            ToolActivitiesImpl activities = new ToolActivitiesImpl(
                    new SimpleToolRegistry().register(publisher(entered)),
                    parksOnceThenNarrowsTo("/tmp/narrowed.txt"));
            ToolInvocation resumed = activities.executeTool(PROPOSED)
                    .parked().orElseThrow().invocation();
            activities.resumeTool(resumed, approving("t1"));
        });

        assertThat(whenItStillParks)
                .as("the verdict answered the question the gate was still asking")
                .contains("Applying a APPROVE decision by alice to call 't1' on tool"
                        + " 'publish', which the gate parked")
                .doesNotContain("no longer parks");
        assertThat(whenItHasDrifted)
                .as("policy moved while the call sat on somebody's desk, and the line says so")
                .contains("Applying a APPROVE decision by alice to call 't1' on tool"
                        + " 'publish', which the gate no longer parks");
    }

    /**
     * The line a park writes, which was the control mutant a whole PR reasoned from (#225).
     *
     * <p><strong>This is {@code C1}.</strong> PR #223's first mutation pass garbled this
     * line as its control, watched the control survive, and concluded that no log-line
     * mutant in this class was killable. The control was the right instinct and the
     * inference was wrong: it survived because the branch it garbles had no test, not
     * because the branch is unobservable. The test above kills the verdict line by the same
     * technique this one uses, and both were written after the inference was measured
     * rather than argued.
     *
     * <p><strong>What this line is not, said because it would be easy to overstate.</strong>
     * It is not the only place the fact reaches an operator: {@code
     * AgentWorkflowImpl.waitForAPerson} logs "Durable run waiting for a person to decide"
     * with the ticket as well as the reason, and the parked outcome is in Temporal history.
     * What makes this one worth its own test is <em>where</em> it is: a park is decided in
     * the activity, which runs in the tool worker, and the workflow line is written by the
     * workflow worker — separate processes, usually separately deployed and separately
     * paged. An operator holding only the tool worker's stream sees a run that made a call
     * and then said nothing.
     *
     * <p>A park is also the one outcome that stops a run <em>without failing anything</em>:
     * nothing threw, no activity errored, and the workflow simply waits. #58's complaint
     * was "nothing failed and nothing logged", and this is the branch where the first half
     * is true by design.
     *
     * <p>The gate's <em>reason</em> is asserted and not only the tool's name, because the
     * reason is the part an operator acts on — "which tool" is in the activity input and
     * "why" is not. Both come from {@code pending}, so a line built from the arriving
     * call rather than from what the gate settled on still passes; that is
     * {@code aParkedCallRecordsTheGatesNarrowedCallThoughNoToolReceivedIt}'s question and
     * is deliberately not re-asked here.
     */
    @Test
    void theToolWorkersOperatorIsToldWhenAGateParksACallAndWhy() {
        String whenItParks = errWhile(() -> {
            List<ToolInvocation> entered = new ArrayList<>();
            ToolActivitiesImpl activities = new ToolActivitiesImpl(
                    new SimpleToolRegistry().register(publisher(entered)),
                    (tool, invocation) -> GateResult.needsAPerson(
                            ApprovalNeeded.because("publishing outside the estate needs a"
                                    + " duty engineer"),
                            new ToolInvocation(invocation.id(), invocation.name(),
                                    Map.of("text", "/tmp/harmless.txt"))));
            assertThat(activities.executeTool(PROPOSED).parked()).isPresent();
            assertThat(entered).as("a park runs nothing").isEmpty();
        });

        assertThat(whenItParks)
                .as("a run stopped to wait for a person and said nothing about it")
                .contains("Tool 'publish' parked by the gate for a person:"
                        + " publishing outside the estate needs a duty engineer");

        // The other side of the branch, because a line that fires either way reports
        // nothing: an ordinary allowed call must not announce a park it never took.
        String whenItRuns = errWhile(() -> {
            List<ToolInvocation> entered = new ArrayList<>();
            new ToolActivitiesImpl(new SimpleToolRegistry().register(publisher(entered)),
                    ToolGate.ALLOW_ALL).executeTool(PROPOSED);
            assertThat(entered).hasSize(1);
        });

        assertThat(whenItRuns)
                .as("a call that ran was announced as waiting on a person")
                .doesNotContain("parked by the gate");
    }

    /**
     * Everything {@code body} writes to {@code System.err}, with the stream put back.
     *
     * <p>{@code slf4j-simple} resolves {@code System.err} on each write — its {@code
     * cacheOutputStream} setting is off by default — so a logger initialised long before
     * this call still lands in the buffer. Restored in a {@code finally}, because a test
     * that leaves {@code System.err} replaced takes the rest of the suite's output with it.
     */
    private static String errWhile(Runnable body) {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true,
                java.nio.charset.StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
