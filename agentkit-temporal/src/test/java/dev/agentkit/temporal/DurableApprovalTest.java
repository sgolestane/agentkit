package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.PendingApproval;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Human-in-the-loop approval on the durable path (#101).
 *
 * <p>The half that could not live in a gate. An {@code Approver} blocks a thread, which is
 * exactly what it is for in process and exactly what a Temporal activity cannot do — it
 * would burn its start-to-close timeout, fail, and be retried, paging somebody on each
 * attempt and failing the call anyway. So {@code ToolActivitiesImpl} refuses such a gate at
 * registration, and #101 asked for the capability that refusal was standing in for: the
 * gate says "a person must decide" and returns, and the <em>workflow</em> blocks on a
 * signal, where waiting is free and survives the worker dying.
 *
 * <p><strong>These tests assert on the tool.</strong> Asking the workflow whether it waited
 * is asking the control whether it worked; it passes for a run that waits and publishes
 * anyway. Every test here counts what the tool was actually entered with.
 */
class DurableApprovalTest {

    private static final String TASK_QUEUE = "agentkit-approval-test";

    private TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    // --- the world ---------------------------------------------------------------

    /** A tool that records every invocation it was actually entered with. */
    private static FunctionTool publisher(List<ToolInvocation> entered) {
        return FunctionTool.builder("publish", "Publishes text somewhere public")
                .schema(Map.of("type", "object",
                        "properties", Map.of("text", Map.of("type", "string")),
                        "required", List.of("text")))
                .sideEffects(SideEffects.EXTERNAL)
                .handler(invocation -> {
                    entered.add(invocation);
                    return ToolResult.ok("published:" + invocation.stringArgument("text"));
                })
                .build();
    }

    private static ToolGate parksPublish() {
        return ToolGates.parkForApproval(invocation -> invocation.name().equals("publish"),
                ApprovalNeeded.because("publishing needs a person")
                        .withEffect("The text becomes publicly visible."));
    }

    private static ScriptedLlm publishesThenStops() {
        return new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "publish", Map.of("text", "the payload")))
                .then(ScriptedLlm.text("done"));
    }

    private AgentWorkflow start(LlmClient llm, SimpleToolRegistry tools, ToolGate gate) {
        return start(llm, tools, gate, DurableAgentOptions.defaults());
    }

    private AgentWorkflow start(LlmClient llm, SimpleToolRegistry tools, ToolGate gate,
                                DurableAgentOptions options) {
        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
                        .setDataConverter(TemporalAgent.dataConverter())
                        .build())
                // No sticky cache, so every workflow task replays from history. A verdict
                // that only survived in a live worker's memory would be lost here.
                .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder()
                        .setWorkflowCacheSize(0)
                        .build())
                .build());
        TemporalAgent.register(env.newWorker(TASK_QUEUE), llm, tools, gate);
        env.start();
        AgentWorkflow stub = TemporalAgent.newStub(env.getWorkflowClient(), TASK_QUEUE);
        this.options = options;
        this.tools = tools;
        return stub;
    }

    private DurableAgentOptions options = DurableAgentOptions.defaults();
    private SimpleToolRegistry tools;

    /** Starts the run without blocking, so the test can signal it while it waits. */
    private CompletableFuture<AgentRunResult> begin(AgentWorkflow stub) {
        DurableAgentRun run = DurableAgentRun.of(Goal.of("publish something"),
                        AgentConfig.builder("m").maxSteps(5).build(), tools.advertisedSpecs())
                .withOptions(options);
        return WorkflowClient.execute(stub::run, run);
    }

    /** Waits until the run is parked on somebody, then returns what it is waiting on. */
    private List<PendingApproval> waitUntilParked(AgentWorkflow stub) throws Exception {
        return waitUntilParkedOtherThan(stub, "");
    }

    /**
     * As above, but ignoring a ticket the caller has already dealt with.
     *
     * <p>The query answers from workflow state, and a decided park is cleared a moment
     * after the decision lands — so polling for "anything pending" right after answering
     * one can see the old one again.
     */
    private List<PendingApproval> waitUntilParkedOtherThan(AgentWorkflow stub, String settled)
            throws Exception {
        for (int attempt = 0; attempt < 400; attempt++) {
            List<PendingApproval> pending = stub.pendingApprovals();
            if (!pending.isEmpty() && !pending.get(0).ticket().equals(settled)) {
                return pending;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("the run never parked on anything other than " + settled);
    }

    /** A verdict aimed straight at the activity, which knows nothing about tickets. */
    private static ApprovalVerdict approving(String invocationId) {
        return new ApprovalVerdict("park-1", invocationId,
                dev.agentkit.core.reliability.ApprovalDecision.Kind.APPROVE, "", null, "alice");
    }

    // --- the tests ---------------------------------------------------------------

    @Test
    void aReviewerMayClearAFieldWithoutStallingTheRunForever() throws Exception {
        // #132. ApprovalVerdict's constructor coerces null `reason` and `decidedBy` rather
        // than requiring them, and its own comment says why: "Refusing here would throw
        // inside the signal handler, which fails the workflow *task*, which Temporal
        // retries forever. The run would stall on a malformed decision rather than reject
        // it." The very next line was Map.copyOf, which throws on a null VALUE.
        //
        // So a reviewer who edits the arguments and clears an optional field — the ordinary
        // way to say "no note on this one" — hit exactly the failure the three lines above
        // were written to prevent. This is the worst of the three sites in #132 because the
        // failure is unbounded: nothing catches it and nothing gives up.
        //
        // What this test actually catches, measured rather than assumed: reverting the fix
        // fails it with a NullPointerException at the `decide` line below — CLIENT side,
        // where approveWithArguments builds the record, before any signal is sent. That is
        // loud, and on its own it means approve-with-edited-arguments simply does not work
        // for a cleared field.
        //
        // The stall the constructor's comment warns about needs the record to be built from
        // a signal payload instead, which is the hand-rolled-client case that comment names.
        // aVerdictOffTheWireWithAClearedFieldIsNotRefused below covers that half; this one
        // does not, and an earlier version of this comment claimed it did.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        AgentWorkflow stub = start(publishesThenStops(), registry, parksPublish());
        CompletableFuture<AgentRunResult> running = begin(stub);

        List<PendingApproval> pending = waitUntilParked(stub);
        // LinkedHashMap, not Map.of — Map.of rejects a null value too, so the test could
        // not express the edit it is about.
        Map<String, Object> edited = new java.util.LinkedHashMap<>();
        edited.put("text", "the redacted payload");
        edited.put("note", null);

        stub.decide(ApprovalVerdict.approveWithArguments(pending.get(0), "alice", edited));
        AgentRunResult result = running.get(30, TimeUnit.SECONDS);

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(entered).hasSize(1);
        assertThat(entered.get(0).stringArgument("text")).isEqualTo("the redacted payload");
        assertThat(entered.get(0).arguments())
                .as("the cleared field reached the tool as a null rather than being dropped,"
                        + " which would be a different call from the one approved")
                .containsEntry("note", null);
    }

    @Test
    void aVerdictOffTheWireWithAClearedFieldIsNotRefused() throws Exception {
        // The other half of #132's durable case, and the one with no floor under it.
        //
        // This is the path a signal takes: Jackson builds the record inside the workflow, so
        // a throw here fails the workflow TASK, which Temporal retries forever. There is no
        // client to catch it and nothing that gives up — the run stalls rather than
        // rejecting the decision, which is precisely what the constructor's own coercion of
        // `reason` and `decidedBy` exists to prevent, and what the next line then undid.
        //
        // Deserialized from JSON rather than constructed, because construction is the other
        // test and because JSON null is the input the whole issue is about.
        ObjectMapper mapper = DurableJson.objectMapper();
        String signalPayload = """
                {"ticket":"park-1","invocationId":"t1","kind":"APPROVE_WITH_ARGUMENTS",
                 "reason":"","arguments":{"text":"redacted","note":null},
                 "decidedBy":"alice"}""";

        ApprovalVerdict verdict = mapper.readValue(signalPayload, ApprovalVerdict.class);

        assertThat(verdict.arguments())
                .as("a signal carrying a JSON null threw inside the handler, and a throw"
                        + " there is retried forever rather than reported")
                .containsEntry("note", null)
                .containsEntry("text", "redacted");
    }

    @Test
    void aLargeVerdictOffTheWireIsNotRefusedEither() throws Exception {
        // The first attempt at #132 used Frozen.deeply here, arguing that its bounds
        // "cannot fire on a signal payload". That was reasoned, not measured, and it was
        // false: Frozen.deeply refuses beyond 100,000 expanded values, and a signal carrying
        // 100,100 arguments is 1,290,407 bytes of JSON — inside Temporal's default blob
        // limit. So the fix for a forever-retry stall reintroduced the same forever-retry
        // stall, at a payload size the transport allows. Measured on the way in:
        //
        //     1,000 args (   11,007 bytes): constructed
        //    99,000 args (1,276,007 bytes): constructed
        //   100,100 args (1,290,407 bytes): THREW IllegalArgumentException
        //
        // A cliff at a size the wire permits is not a defence. The node budget is there for
        // shared or cyclic structures, and a parser produces neither — measured, Jackson
        // does not even share identical subtrees, so a parsed document cannot expand beyond
        // its own size. deeplyFromWire drops that budget and keeps everything else.
        //
        // 100,100 rather than a round number because the bound is 100,000 and this test is
        // about the far side of it. It parses in well under a second.
        ObjectMapper mapper = DurableJson.objectMapper();
        StringBuilder arguments = new StringBuilder("{");
        for (int i = 0; i < 100_100; i++) {
            if (i > 0) {
                arguments.append(',');
            }
            arguments.append("\"k").append(i).append("\":\"v\"");
        }
        arguments.append('}');
        String signalPayload = "{\"ticket\":\"park-1\",\"invocationId\":\"t1\","
                + "\"kind\":\"APPROVE_WITH_ARGUMENTS\",\"reason\":\"\",\"arguments\":"
                + arguments + ",\"decidedBy\":\"alice\"}";

        ApprovalVerdict verdict = mapper.readValue(signalPayload, ApprovalVerdict.class);

        assertThat(verdict.arguments())
                .as("a signal inside Temporal's blob limit was refused, and a refusal in a"
                        + " signal handler is retried forever rather than reported")
                .hasSize(100_100);
    }

    @Test
    void theSameArgumentsAreBoundedThroughTheFactoryAndNotThroughTheWire() {
        // The other half of the split, and it took two attempts to write a test that says
        // anything. The first asserted that a String[] is refused through the factory — but
        // the constructor refuses non-JSON shapes too, so dropping the factory's check
        // entirely left that test green. It pinned the constructor while claiming to pin
        // the factory.
        //
        // What the factory ACTUALLY adds over the constructor is the node budget, so that
        // is what this asserts: identical arguments, accepted through the wire and refused
        // through the factory. Same data, different door, different answer — which is the
        // whole design, stated as a measurement.
        //
        // The asymmetry is deliberate and is not "the wire is trusted more". It is that the
        // two callers can act on being told: an author gets a stack trace and fixes the
        // code, while Jackson-inside-a-signal-handler gets a workflow task failure that
        // Temporal retries forever.
        PendingApproval parked = new PendingApproval(
                new ToolInvocation("t1", "publish", Map.of("text", "x")),
                ApprovalNeeded.because("publishing needs a person"), "park-1");
        Map<String, Object> tooMany = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 100_100; i++) {
            tooMany.put("k" + i, "v");
        }

        assertThatThrownBy(() ->
                ApprovalVerdict.approveWithArguments(parked, "alice", tooMany))
                .as("an author's own oversized or shared structure was accepted silently,"
                        + " where a stack trace is exactly what they can act on")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100000");

        // And the identical map through the constructor, which is the wire's door.
        //
        // The claim is about the DOOR, and an earlier version of this message overreached
        // past it: "the door a signal comes through must not refuse anything the transport
        // was willing to carry" reads as though the call then runs. It does not. The
        // constructor accepts, and asDecision turns the edit into a denial with a stated
        // reason a moment later — which is the point, because the alternative at this door
        // is a throw inside a signal handler that Temporal retries forever.
        // anApprovalTooBigToApplyDeniesRatherThanQuietlyDoingNothing pins what actually
        // happens next; this pins only that nothing throws here.
        assertThat(new ApprovalVerdict("park-1", "t1",
                dev.agentkit.core.reliability.ApprovalDecision.Kind.APPROVE_WITH_ARGUMENTS,
                "", tooMany, "alice").arguments())
                .as("the door a signal comes through refused a payload the transport was"
                        + " willing to carry, and a refusal there is retried forever")
                .hasSize(100_100);
    }

    @Test
    void anApprovalTooBigToApplyDeniesRatherThanQuietlyDoingNothing() throws Exception {
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        ScriptedLlm llm = publishesThenStops();
        AgentWorkflow stub = start(llm, registry, parksPublish());
        CompletableFuture<AgentRunResult> running = begin(stub);
        List<PendingApproval> pending = waitUntilParked(stub);

        Map<String, Object> tooMany = new java.util.LinkedHashMap<>();
        tooMany.put("text", "the redacted payload");
        for (int i = 0; i < 100_100; i++) {
            tooMany.put("k" + i, "v");
        }
        stub.decide(new ApprovalVerdict(pending.get(0).ticket(),
                pending.get(0).invocationId(),
                dev.agentkit.core.reliability.ApprovalDecision.Kind.APPROVE_WITH_ARGUMENTS,
                "", tooMany, "alice"));
        AgentRunResult result = running.get(60, TimeUnit.SECONDS);

        assertThat(entered)
                .as("the tool must not run with arguments nobody could apply, and it must"
                        + " especially not run with the arguments the reviewer replaced")
                .isEmpty();
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        // The oracle is what the model was told, because the failure this pins is silence.
        // Measured before the fallback existed: stopReason=COMPLETED entered=0 error=[] —
        // the approval recorded, the tool never run, and nothing anywhere saying so.
        assertThat(lastToolResultText(llm))
                .as("an approval that could not be applied produced no explanation anywhere,"
                        + " which is the silent no-op #132 was opened about")
                .contains("could not be applied")
                .contains("Nothing ran");
    }

    /** What the model saw come back from the tool on its last turn. */
    private static String lastToolResultText(ScriptedLlm llm) {
        return llm.requests().stream()
                .flatMap(request -> request.messages().stream())
                .flatMap(message -> message.content().stream())
                .filter(block -> block instanceof dev.agentkit.core.message.ToolResultBlock)
                .map(block -> ((dev.agentkit.core.message.ToolResultBlock) block).content())
                .reduce((first, second) -> second)
                .orElse("");
    }

    @Test
    void aNullProviderOptionDoesNotStallTheFirstTurn() throws Exception {
        // #132's own sweep dismissed this site — "the rest of the copyOf hits are on
        // framework-built structures whose values cannot be null" — and the code disagrees.
        // AgentConfig.Builder.option null-checks the key only, and AgentConfig's constructor
        // copies null-tolerantly on purpose, so a null option value is legal, serializes,
        // and round-trips through history.
        //
        // LlmCallSpec is then built at AgentWorkflowImpl:206 inside the WORKFLOW method, so
        // a Map.copyOf there fails the workflow task on the very first turn, and Temporal
        // retries a failed workflow task forever. No reviewer, no signal, no model — a
        // strictly worse instance than the approval path this issue was opened for, and it
        // survives replay because the null is in history.
        //
        // The oracle is that the run finishes. A stall is not an exception anybody catches;
        // it is a run that never returns.
        SimpleToolRegistry registry = new SimpleToolRegistry();
        AgentWorkflow stub = start(new ScriptedLlm().then(ScriptedLlm.text("done")), registry,
                ToolGates.allowAll());
        DurableAgentRun run = DurableAgentRun.of(Goal.of("say something"),
                AgentConfig.builder("m").maxSteps(2).option("stop", null).build(),
                registry.advertisedSpecs());

        AgentRunResult result = WorkflowClient.execute(stub::run, run).get(30, TimeUnit.SECONDS);

        assertThat(result.stopReason())
                .as("a null provider option failed the workflow task on the first turn,"
                        + " which Temporal retries forever")
                .isEqualTo(StopReason.COMPLETED);
    }

    @Test
    void aParkedCallWaitsAndTheToolDoesNotRunUntilSomebodySaysSo() throws Exception {
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        AgentWorkflow stub = start(publishesThenStops(), registry, parksPublish());
        CompletableFuture<AgentRunResult> running = begin(stub);

        List<PendingApproval> pending = waitUntilParked(stub);
        assertThat(entered)
                .as("nothing may run while the question is open — a run that waits and"
                        + " publishes anyway is what asserting on the workflow would miss")
                .isEmpty();

        stub.decide(ApprovalVerdict.approve(pending.get(0), "alice"));
        AgentRunResult result = running.get(30, TimeUnit.SECONDS);

        assertThat(entered).hasSize(1);
        assertThat(entered.get(0).stringArgument("text")).isEqualTo("the payload");
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
    }

    @Test
    void theQueryNamesTheCallAndTheReasonWhileItWaits() throws Exception {
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        AgentWorkflow stub = start(publishesThenStops(), registry, parksPublish());
        CompletableFuture<AgentRunResult> running = begin(stub);

        PendingApproval pending = waitUntilParked(stub).get(0);
        assertThat(pending.ticket())
                .as("the handle a decision names is the runner's, not the model's")
                .isEqualTo("park-1");

        assertThat(pending.toolName()).isEqualTo("publish");
        assertThat(pending.invocationId()).isEqualTo("t1");
        assertThat(pending.why().reason()).contains("needs a person");
        assertThat(pending.why().effect()).isNotBlank();
        assertThat(pending.invocation().arguments())
                .as("a reviewer decides on the arguments, so they cross the wire verbatim")
                .containsEntry("text", "the payload");

        stub.decide(ApprovalVerdict.approve(pending, "alice"));
        running.get(30, TimeUnit.SECONDS);
    }

    @Test
    void theQueryIsEmptyOnceTheQuestionIsAnswered() throws Exception {
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        AgentWorkflow stub = start(publishesThenStops(), registry, parksPublish());
        CompletableFuture<AgentRunResult> running = begin(stub);

        stub.decide(ApprovalVerdict.approve(waitUntilParked(stub).get(0), "alice"));
        running.get(30, TimeUnit.SECONDS);

        assertThat(stub.pendingApprovals())
                .as("a console still listing it would offer a decision that can no longer"
                        + " be applied")
                .isEmpty();
    }

    @Test
    void aDeniedCallBecomesAnErrorTheModelReactsToAndTheRunCarriesOn() throws Exception {
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        AgentWorkflow stub = start(publishesThenStops(), registry, parksPublish());
        CompletableFuture<AgentRunResult> running = begin(stub);

        stub.decide(ApprovalVerdict.deny(waitUntilParked(stub).get(0), "alice", "not this week"));
        AgentRunResult result = running.get(30, TimeUnit.SECONDS);

        assertThat(entered).isEmpty();
        assertThat(result.stopReason())
                .as("a person saying no is an answer the model adapts to, which is what a"
                        + " denial has always been on both runners — not a failed run")
                .isEqualTo(StopReason.COMPLETED);
        assertThat(result.output()).isEqualTo("done");
    }

    @Test
    void anEditedApprovalIsWhatActuallyRuns() throws Exception {
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        AgentWorkflow stub = start(publishesThenStops(), registry, parksPublish());
        CompletableFuture<AgentRunResult> running = begin(stub);

        stub.decide(ApprovalVerdict.approveWithArguments(waitUntilParked(stub).get(0), "alice",
                Map.of("text", "a narrower payload")));
        running.get(30, TimeUnit.SECONDS);

        assertThat(entered).hasSize(1);
        assertThat(entered.get(0).stringArgument("text"))
                .as("dropping the edit is how an editing approver becomes a rubber stamp,"
                        + " which this repository has shipped twice in process")
                .isEqualTo("a narrower payload");
    }

    @Test
    void anApprovalCannotWidenWhatPolicyAllows() throws Exception {
        // The security property. A gate that denies outright still denies with a signed
        // approval in hand, because the gate is re-evaluated when the decision comes back
        // and a verdict is read only where the gate is still asking.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        ToolGate parkThenRefuse = new ToolGate() {
            private boolean asked;

            @Override
            public GateResult evaluate(dev.agentkit.core.tool.Tool tool, ToolInvocation invocation) {
                if (!asked) {
                    asked = true;
                    return GateResult.needsAPerson(ApprovalNeeded.because("needs a person"));
                }
                // The policy changed while the call sat on somebody's desk. The policy that
                // applies is this one, not the one that parked it.
                return GateResult.deny("publishing has since been switched off entirely");
            }
        };
        AgentWorkflow stub = start(publishesThenStops(), registry, parkThenRefuse);
        CompletableFuture<AgentRunResult> running = begin(stub);

        stub.decide(ApprovalVerdict.approve(waitUntilParked(stub).get(0), "alice"));
        AgentRunResult result = running.get(30, TimeUnit.SECONDS);

        assertThat(entered)
                .as("an approval answers the question policy asked; it does not overrule"
                        + " policy that has stopped asking and started refusing")
                .isEmpty();
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
    }

    @Test
    void anApprovalForAnotherCallDoesNotRunThisOne() throws Exception {
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        AgentWorkflow stub = start(publishesThenStops(), registry, parksPublish(),
                DurableAgentOptions.defaults().withApprovalTimeout(2));
        CompletableFuture<AgentRunResult> running = begin(stub);

        PendingApproval parked = waitUntilParked(stub).get(0);
        // Answering a ticket this run is not waiting on. It is dropped outright, so the run
        // times out rather than spending somebody else's approval here.
        stub.decide(new ApprovalVerdict("park-99", parked.invocationId(),
                dev.agentkit.core.reliability.ApprovalDecision.Kind.APPROVE, "", null, "alice"));
        env.sleep(Duration.ofSeconds(5));
        AgentRunResult result = running.get(30, TimeUnit.SECONDS);

        assertThat(entered).isEmpty();
        assertThat(result.stopReason()).isEqualTo(StopReason.AWAITING_APPROVAL);
    }

    @Test
    void nobodyAnsweringEndsTheRunRatherThanTellingTheModelItWasRefused() throws Exception {
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        AgentWorkflow stub = start(publishesThenStops(), registry, parksPublish(),
                DurableAgentOptions.defaults().withApprovalTimeout(2));
        CompletableFuture<AgentRunResult> running = begin(stub);

        waitUntilParked(stub);
        env.sleep(Duration.ofSeconds(5));
        AgentRunResult result = running.get(30, TimeUnit.SECONDS);

        assertThat(entered).isEmpty();
        assertThat(result.stopReason())
                .as("nobody denied anything, so telling the model it was refused would be a"
                        + " confident falsehood about a question still open — and it would"
                        + " propose the same action, park again, and page somebody who has"
                        + " already not answered once")
                .isEqualTo(StopReason.AWAITING_APPROVAL);
    }

    @Test
    void aGateThatParksIsAcceptedWhereOneThatBlocksIsRefused() {
        // The refusal at registration is about a gate that *waits*, and a parking gate does
        // not: it returns at once and leaves the waiting to the workflow. If parking were
        // caught by that guard, the one shape built for this runner would be banned from it.
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(new ArrayList<>()));

        assertThat(parksPublish().waitsForAHuman()).isFalse();
        // Constructing it is the assertion: the constructor throws for a gate that waits.
        ToolActivitiesImpl activities = new ToolActivitiesImpl(registry, parksPublish());
        assertThat(activities).isNotNull();
    }

    @Test
    void anApprovalHandedStraightToTheActivityMustAnswerTheCallItIsGiven() {
        // The workflow cannot produce this: it looks a verdict up by the parked call's own
        // id, so the two always match. ToolActivities is a public interface registered on a
        // worker, though, and any workflow can invoke it — so the check defends a boundary
        // even though this repository's own workflow never crosses it wrongly.
        //
        // Found by mutation: deleting the check left all ninety-two tests green.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        ToolActivitiesImpl activities = new ToolActivitiesImpl(registry, parksPublish());

        ToolOutcome result = activities.resumeTool(
                new ToolInvocation("t1", "publish", Map.of("text", "the payload")),
                new ApprovalVerdict("park-1", "a-completely-different-call",
                        dev.agentkit.core.reliability.ApprovalDecision.Kind.APPROVE, "", null,
                        "alice"));

        assertThat(entered)
                .as("an approval for one action must not be spent on another")
                .isEmpty();
        assertThat(result.result().isError()).isTrue();
        assertThat(result.result().content()).contains("does not answer this call");
    }

    @Test
    void anApprovalThatDoesAnswerTheCallRunsIt() {
        // The positive control for the test above. Without it, "the tool did not run" would
        // pass for an activity that never runs anything.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        ToolActivitiesImpl activities = new ToolActivitiesImpl(registry, parksPublish());

        ToolOutcome result = activities.resumeTool(
                new ToolInvocation("t1", "publish", Map.of("text", "the payload")),
                approving("t1"));

        assertThat(entered).hasSize(1);
        assertThat(result.result().isError()).isFalse();
    }

    @Test
    void anUndecidedCallIsParkedRatherThanRunWhenTheActivityIsAskedDirectly() {
        // The other half of the same boundary: executeTool has no verdict, so it must park
        // rather than fall through to the tool.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        ToolActivitiesImpl activities = new ToolActivitiesImpl(registry, parksPublish());

        ToolOutcome outcome = activities.executeTool(
                new ToolInvocation("t1", "publish", Map.of("text", "x")));

        assertThat(entered).isEmpty();
        assertThat(outcome.parked()).isPresent();
        assertThat(outcome.parked().orElseThrow().invocationId()).isEqualTo("t1");
    }

    // --- what composition does, which is where the first version was wrong -----------

    @Test
    void anApprovalCannotCarryACallPastAGateOrderedAfterTheParkingOne() {
        // The hole the first version of this change shipped, through the runner that would
        // have executed it. Measured then: parked, approved, and the tool RAN with
        // 5,000,000 — because allOf returned the park immediately, so the resume
        // re-evaluated the composite, short-circuited at the same parking member, and the
        // denying member was never reached. The javadoc asserted the opposite in as many
        // words.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        ToolActivitiesImpl activities = new ToolActivitiesImpl(registry, ToolGates.allOf(
                parksPublish(),
                (tool, invocation) -> GateResult.deny("publishing is switched off entirely")));

        ToolOutcome first = activities.executeTool(
                new ToolInvocation("t1", "publish", Map.of("text", "the payload")));
        ToolOutcome after = activities.resumeTool(
                new ToolInvocation("t1", "publish", Map.of("text", "the payload")),
                approving("t1"));

        assertThat(entered)
                .as("an approval must not carry a call past a member that refuses it")
                .isEmpty();
        assertThat(first.parked())
                .as("and nobody is even asked, since the answer could not have mattered")
                .isEmpty();
        assertThat(after.result().isError()).isTrue();
    }

    @Test
    void aReviewerSeesAndApprovesTheCallThatActuallyRuns() {
        // The other half of the same defect. Measured before the fix: allOf(narrowTo100k,
        // park) showed the reviewer the proposed 5,000,000 and then ran the tool with
        // 5,000,000, losing the policy's narrowing at both ends — #104's failure arriving
        // through a new door.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        ToolActivitiesImpl activities = new ToolActivitiesImpl(registry, ToolGates.allOf(
                (tool, invocation) -> GateResult.allowWith(new ToolInvocation(
                        invocation.id(), invocation.name(), Map.of("text", "narrowed"))),
                parksPublish()));
        ToolInvocation proposed = new ToolInvocation("t1", "publish", Map.of("text", "the payload"));

        ToolOutcome parked = activities.executeTool(proposed);
        activities.resumeTool(proposed, approving("t1"));

        assertThat(parked.parked().orElseThrow().invocation().arguments())
                .as("shown the call that will run, not the one the model proposed")
                .containsEntry("text", "narrowed");
        assertThat(entered).hasSize(1);
        assertThat(entered.get(0).stringArgument("text")).isEqualTo("narrowed");
    }

    @Test
    void aRefusalStandsEvenAfterThePolicyStopsAsking() {
        // Everything else here reads the gate first, because an approval must not widen
        // what policy allows. A refusal is never a widening, so it is honoured before the
        // gate is consulted — otherwise a policy relaxed while the call sat on somebody's
        // desk would discard their "no" and run the tool.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        ToolActivitiesImpl activities = new ToolActivitiesImpl(registry, ToolGate.ALLOW_ALL);

        ToolOutcome result = activities.resumeTool(
                new ToolInvocation("t1", "publish", Map.of("text", "x")),
                new ApprovalVerdict("park-1", "t1",
                        dev.agentkit.core.reliability.ApprovalDecision.Kind.DENY,
                        "absolutely not", null, "alice"));

        assertThat(entered).isEmpty();
        assertThat(result.result().isError()).isTrue();
        assertThat(result.result().content()).contains("absolutely not");
    }

    @Test
    void aTimedOutRunSaysWhatExpired() throws Exception {
        // Otherwise the operator has an AWAITING_APPROVAL with an empty message and an
        // empty query — the workflow has completed, so it answers no queries — and has to
        // read Temporal history to find out which call it was.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        AgentWorkflow stub = start(publishesThenStops(), registry, parksPublish(),
                DurableAgentOptions.defaults().withApprovalTimeout(2));
        CompletableFuture<AgentRunResult> running = begin(stub);

        waitUntilParked(stub);
        env.sleep(Duration.ofSeconds(5));
        AgentRunResult result = running.get(30, TimeUnit.SECONDS);

        assertThat(result.stopReason()).isEqualTo(StopReason.AWAITING_APPROVAL);
        assertThat(result.awaiting()).hasSize(1);
        assertThat(result.awaiting().get(0).toolName()).isEqualTo("publish");
        assertThat(result.awaiting().get(0).why().reason()).contains("needs a person");
    }

    // --- what an approval is spent on -----------------------------------------------

    @Test
    void oneApprovalIsNotSpentOnALaterCallThatReusesTheModelsCallId() throws Exception {
        // The laundering primitive the first version shipped. Verdicts were kept for the
        // life of the run, keyed on the model's own tool-call id, so a model that got one
        // approval could reuse that id to carry anything through the same gate.
        //
        // Measured then: publish{benign} as t1 was approved and ran; publish{NOBODY-
        // APPROVED-THIS} — also t1 — parked, the await returned immediately on the stale
        // verdict, and it ran too. It never appeared in pendingApprovals(), the worker
        // logged an approval by a person who never saw it, and the run reported COMPLETED.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("t1", "publish", Map.of("text", "benign")))
                .then(ScriptedLlm.toolUse("t1", "publish", Map.of("text", "NOBODY-APPROVED-THIS")))
                .then(ScriptedLlm.text("done"));
        AgentWorkflow stub = start(llm, registry, parksPublish(),
                DurableAgentOptions.defaults().withApprovalTimeout(2));
        CompletableFuture<AgentRunResult> running = begin(stub);

        PendingApproval first = waitUntilParked(stub).get(0);
        stub.decide(ApprovalVerdict.approve(first, "alice"));
        // The second call parks on its own ticket. Nobody answers it.
        env.sleep(Duration.ofSeconds(5));
        AgentRunResult result = running.get(30, TimeUnit.SECONDS);

        assertThat(entered)
                .as("exactly one publish — the one alice actually looked at")
                .hasSize(1);
        assertThat(entered.get(0).stringArgument("text")).isEqualTo("benign");
        assertThat(result.stopReason())
                .as("the second call was still outstanding when the deadline passed")
                .isEqualTo(StopReason.AWAITING_APPROVAL);
        assertThat(result.awaiting().get(0).invocation().stringArgument("text"))
                .isEqualTo("NOBODY-APPROVED-THIS");
    }

    @Test
    void aDecisionForAParkThatHasNotHappenedYetIsDropped() throws Exception {
        // The same defect from the other end. An early verdict used to be kept, on the
        // argument that it "cannot make anything happen that policy would not" — it could:
        // it ran a later park the moment it was raised, without the call ever appearing in
        // pendingApprovals() for anybody to look at.
        //
        // A ticket is learned by querying, and the query lists only what is outstanding, so
        // a decision for anything else is a mistake or an attempt. Dropped either way.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("a", "publish", Map.of("text", "first")))
                .then(ScriptedLlm.toolUse("b", "publish", Map.of("text", "second")))
                .then(ScriptedLlm.text("done"));
        AgentWorkflow stub = start(llm, registry, parksPublish(),
                DurableAgentOptions.defaults().withApprovalTimeout(2));
        CompletableFuture<AgentRunResult> running = begin(stub);

        PendingApproval one = waitUntilParked(stub).get(0);
        // Answering park-2 while park-1 is the outstanding one. It has not been raised, so
        // it is not a decision about anything yet.
        stub.decide(new ApprovalVerdict("park-2", "b",
                dev.agentkit.core.reliability.ApprovalDecision.Kind.APPROVE, "", null, "mallory"));
        stub.decide(ApprovalVerdict.approve(one, "alice"));
        env.sleep(Duration.ofSeconds(5));
        AgentRunResult result = running.get(30, TimeUnit.SECONDS);

        assertThat(entered)
                .as("only the call somebody actually looked at")
                .hasSize(1);
        assertThat(entered.get(0).stringArgument("text")).isEqualTo("first");
        assertThat(result.stopReason()).isEqualTo(StopReason.AWAITING_APPROVAL);
    }

    @Test
    void aDecisionIsSpentWhenItIsUsed() throws Exception {
        // Two parks, one approval each. The second must not be satisfied by the first's
        // verdict — which is what clearing the map alongside pending buys, and also what
        // keeps it from growing for the life of a run.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        ScriptedLlm llm = new ScriptedLlm()
                .then(ScriptedLlm.toolUse("a", "publish", Map.of("text", "first")))
                .then(ScriptedLlm.toolUse("b", "publish", Map.of("text", "second")))
                .then(ScriptedLlm.text("done"));
        AgentWorkflow stub = start(llm, registry, parksPublish());
        CompletableFuture<AgentRunResult> running = begin(stub);

        PendingApproval one = waitUntilParked(stub).get(0);
        assertThat(one.ticket()).isEqualTo("park-1");
        stub.decide(ApprovalVerdict.approve(one, "alice"));

        PendingApproval two = waitUntilParkedOtherThan(stub, "park-1").get(0);
        assertThat(two.ticket())
                .as("a second park is a second question, with its own handle")
                .isEqualTo("park-2");
        stub.decide(ApprovalVerdict.approve(two, "alice"));
        AgentRunResult result = running.get(30, TimeUnit.SECONDS);

        assertThat(entered).hasSize(2);
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
    }

    // --- what a hand-rolled payload does --------------------------------------------

    @Test
    void aRefusalWithNothingWrittenInItStillSaysSomething() {
        // ApprovalVerdict's javadoc defends its coercions as protection against "an older
        // or hand-rolled client that omits a field". Both survived mutation: deleting them
        // left the temporal suite green, because no test constructed such a payload.
        // Without this one, a blank refusal reaches the model as an empty error.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        ToolActivitiesImpl activities = new ToolActivitiesImpl(registry, parksPublish());

        ToolOutcome result = activities.resumeTool(
                new ToolInvocation("t1", "publish", Map.of("text", "x")),
                new ApprovalVerdict("park-1", "t1",
                        dev.agentkit.core.reliability.ApprovalDecision.Kind.DENY, null, null,
                        null));

        assertThat(entered).isEmpty();
        assertThat(result.result().isError()).isTrue();
        assertThat(result.result().content()).isNotBlank();
    }

    @Test
    void anEditWithNoArgumentsApprovesRatherThanFailing() {
        // The other coercion. Without it the null map reaches
        // ApprovalDecision.approveWithArguments and the NPE is swallowed into a generic
        // "Tool 'publish' failed" — a decision somebody made, lost to a missing field.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        ToolActivitiesImpl activities = new ToolActivitiesImpl(registry, parksPublish());

        ToolOutcome result = activities.resumeTool(
                new ToolInvocation("t1", "publish", Map.of("text", "x")),
                new ApprovalVerdict("park-1", "t1",
                        dev.agentkit.core.reliability.ApprovalDecision.Kind.APPROVE_WITH_ARGUMENTS,
                        "", null, "alice"));

        assertThat(entered)
                .as("an approval with no edit in it is still an approval, for a call the"
                        + " gate is about to re-check anyway")
                .hasSize(1);
        assertThat(result.result().isError()).isFalse();
    }

    @Test
    void aNullSignalIsIgnoredRatherThanStallingTheRun() throws Exception {
        // A signal handler that throws fails the workflow task, which Temporal retries
        // forever: the run would stall rather than reject the signal. Untested until now.
        List<ToolInvocation> entered = new ArrayList<>();
        SimpleToolRegistry registry = new SimpleToolRegistry().register(publisher(entered));
        AgentWorkflow stub = start(publishesThenStops(), registry, parksPublish());
        CompletableFuture<AgentRunResult> running = begin(stub);

        PendingApproval parked = waitUntilParked(stub).get(0);
        stub.decide(null);
        stub.decide(ApprovalVerdict.approve(parked, "alice"));

        assertThat(running.get(30, TimeUnit.SECONDS).stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(entered).hasSize(1);
    }
}
