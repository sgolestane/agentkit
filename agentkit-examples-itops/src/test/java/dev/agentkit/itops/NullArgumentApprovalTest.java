package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.ServiceNowConnector;
import dev.agentkit.itops.domain.ApprovalRequest;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.domain.ToolInvocationRecord;
import dev.agentkit.itops.runtime.ExecutionRunner;
import dev.agentkit.itops.runtime.Reviewers;
import dev.agentkit.itops.runtime.OpsScheduler;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A JSON {@code null} in a tool argument is legal, and it used to switch the approval
 * machinery off (#132).
 *
 * <h2>The shape</h2>
 *
 * <p>{@code Map.copyOf} rejects a null <em>value</em> with a {@link NullPointerException}.
 * {@code ToolInvocation} and {@code ToolUseBlock} both document that argument values may be
 * null, because JSON {@code null} is a legal value and a model emits one whenever it means
 * "this field is empty". Three records on the path from a proposed call to a recorded one
 * used {@code Map.copyOf}, so a legal input took each of them out — in three different
 * directions:
 *
 * <ul>
 *   <li>{@code ApprovalRequest}, built inside the supervisor's park. The throw was caught by
 *       {@code Agent.runTool} as an ordinary tool failure, so <strong>no approval was
 *       raised</strong> and the model was told the tool had failed — something it can route
 *       around.</li>
 *   <li>{@code ToolInvocationRecord}, built from an observer callback that
 *       {@code Agent.executeTools} makes <em>outside</em> that catch, and <em>after</em> the
 *       tool has run. Measured: the tool ran, no audit row was written,
 *       {@code Agent.run} threw instead of returning, and {@code onFinish} never fired.</li>
 *   <li>{@code ApprovalVerdict} on the durable path, whose own constructor coerces two other
 *       fields specifically because "refusing here would throw inside the signal handler,
 *       which fails the workflow task, which Temporal retries forever" — and then threw on a
 *       null value three lines later. That one is pinned in {@code agentkit-temporal}.</li>
 * </ul>
 *
 * <h2>What is asserted</h2>
 *
 * <p>The end-to-end test asserts on the <strong>store</strong>: an {@code ApprovalRequest}
 * exists and is {@code PENDING}. Not that the run parked, and not that some method did not
 * throw — a control is engaged when there is a row a human can act on, and "the runner
 * reported parking" is what a runner that parks and then loses the request also reports.
 */
class NullArgumentApprovalTest {

    private static final String TENANT = "acme";

    /**
     * A model that declares a capability and then proposes exactly one irreversible call,
     * with one field left empty.
     *
     * <p>The declaration is a precondition rather than a flourish: {@code RunRules} refuses
     * anything that changes something until {@code report_capability} has run, so a stand-in
     * that opened with the deletion would be denied one gate before the approval machinery
     * this test is about, and would measure that instead.
     */
    private record ProposesADeletionWithANullField(String field) implements LlmClient {
        @Override
        public LlmResponse generate(LlmRequest request) {
            long calls = request.messages().stream()
                    .flatMap(m -> m.content().stream())
                    .filter(block -> block instanceof ToolUseBlock)
                    .count();
            if (calls == 0) {
                return LlmResponse.of(Message.of(Role.ASSISTANT,
                                ProposedCall.of("call-0", "report_capability",
                                        Map.of("verdict", "SUPPORTED",
                                                "reason", "Account lifecycle is mine."))),
                        LlmStopReason.TOOL_USE, TokenUsage.ZERO);
            }
            if (calls > 1) {
                return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of("Stopping.")),
                        LlmStopReason.END_TURN, TokenUsage.ZERO);
            }
            // LinkedHashMap, not Map.of — Map.of cannot hold a null value either, so the
            // test would fail to express the input it is about.
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("email", "alice@example.com");
            arguments.put(field, null);
            return LlmResponse.of(
                    Message.of(Role.ASSISTANT,
                            ProposedCall.of("call-1", "identity.delete_user", arguments)),
                    LlmStopReason.TOOL_USE, TokenUsage.ZERO);
        }
    }

    @Test
    void anIrreversibleCallCarryingANullArgumentStillRaisesAnApproval() {
        OpsStore store = new OpsStore();
        ServiceNowConnector tickets = new ServiceNowConnector();
        IdentityConnector identity = new IdentityConnector();
        ExecutionRunner runner = new ExecutionRunner(store,
                new ProposesADeletionWithANullField("reason"), "scripted", tickets,
                new DirectoryConnector(), identity, "agentkit-integration",
                Reviewers.goalAlignment(), Risk.HIGH);
        Execution execution = store.createExecution(TENANT, "it-ops-agent",
                Execution.Trigger.CHAT, "manual", "Delete the account alice@example.com.");

        runner.run(execution, null);

        // The oracle is the store, not the runner's account of itself.
        List<ApprovalRequest> raised = store.approvals(TENANT);
        assertThat(raised)
                .as("a null argument value took the approval machinery out: measured before"
                        + " this, Map.copyOf threw inside park() and Agent.runTool reported"
                        + " an ordinary tool failure with no approval anywhere")
                .hasSize(1);
        ApprovalRequest request = raised.get(0);
        assertThat(request.toolName()).isEqualTo("identity.delete_user");
        assertThat(request.state()).isEqualTo(ApprovalRequest.State.PENDING);
        assertThat(request.reversible()).isFalse();
        // The null survived into the record a reviewer is shown, rather than being dropped
        // or spelled "null". A reviewer deciding on arguments that are not the proposed
        // ones is the failure this whole path exists to avoid.
        assertThat(request.arguments()).containsEntry("reason", null);
        assertThat(request.arguments()).containsEntry("email", "alice@example.com");

        // And the account is untouched, which is what "parked" has to mean.
        assertThat(identity.findUser("alice@example.com")).isPresent();
    }

    @Test
    void theAuditRowSurvivesANullArgumentToo() {
        // The other direction, and the one that is not caught anywhere: this record is
        // built from an observer callback that runs after the tool has, outside the catch
        // in runTool. Constructed directly because reaching it end-to-end needs a tool
        // below the approval threshold, which is a different scenario from the one above.
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("email", "alice@example.com");
        arguments.put("reason", null);

        // The assertion message says what THIS test observes, which is a constructor that
        // does not throw. The escape above is what that throw caused when it happened in
        // AuditObserver, and it is recorded in the comment rather than the message because
        // this test cannot see it: a change that reintroduced the escape by another route
        // would leave this green, and a message claiming otherwise would make that look
        // like coverage. Writing the below-threshold end-to-end case would fix that
        // properly and is the honest thing to want here.
        // Since #170 the row carries the invocation rather than a name and a map, so the
        // null now has to survive two constructors instead of one. That is the same
        // assertion made against a longer path, not a weaker one: ToolInvocation freezes
        // with Frozen.deeply for exactly the reason this record did.
        ToolInvocation call = new ToolInvocation("toolu-1", "identity.suspend_user", arguments);
        assertThatCode(() -> new ToolInvocationRecord("call-1", "exec-1", call,
                Risk.HIGH, Risk.HIGH, "OK", null,
                "idem-1", Instant.now(), Instant.now(), false, "done"))
                .as("an audit row could not be built for a call carrying a null argument")
                .doesNotThrowAnyException();

        ToolInvocationRecord record = new ToolInvocationRecord("call-1", "exec-1", call,
                Risk.HIGH, Risk.HIGH, "OK", null,
                "idem-1", Instant.now(), Instant.now(), false, "done");
        assertThat(record.arguments()).containsEntry("reason", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void whatTheReviewerIsShownCannotChangeAfterItIsRecorded() {
        // This started out as "a null nested inside an argument is carried through too",
        // justified by a comment claiming a shallow null-tolerant copy "would pass the
        // tests above and still lose this". Measured: it does not. A shallow copy keeps the
        // reference to the inner map, so nested nulls survive it, and all four tests here
        // passed against one. The test proved nothing the two above had not.
        //
        // What Frozen.deeply actually buys over a shallow copy is the snapshot, and that is
        // worth a test in this class specifically: an ApprovalRequest is the record a human
        // decides from. If its values are shared with whatever the gate chain is still
        // holding, the reviewer can be shown one thing and the tool can run with another —
        // which is exactly the substitution Frozen's own javadoc was written for.
        //
        // Nested nulls are still asserted, because they are the input the issue is about
        // and a naive Map.copyOf-per-level fix would drop them.
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("displayName", null);
        List<Object> tags = new java.util.ArrayList<>(java.util.Arrays.asList("a", null));
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("profile", inner);
        arguments.put("tags", tags);

        // The mutable map still goes in at the top of the chain — it is now ToolInvocation
        // that takes the snapshot, and the record inherits it. #170 made that inheritance
        // the only option: there is no longer a constructor that accepts a bare map, so a
        // caller cannot build this record around a reference someone else still holds.
        ApprovalRequest request = new ApprovalRequest("apr-1", TENANT, "exec-1",
                new ToolInvocation("toolu-1", "identity.delete_user", arguments),
                Risk.DESTRUCTIVE, "because", "deletes",
                false, List.of(), ApprovalRequest.State.PENDING, Instant.now(),
                null, null, null);

        // The caller mutates its copy after the record exists. A snapshot does not move.
        inner.put("displayName", "Alice The Administrator");
        arguments.put("profile", Map.of("displayName", "someone else entirely"));

        Map<String, Object> carried = request.arguments();
        assertThat((Map<String, Object>) carried.get("profile"))
                .as("the arguments a reviewer is shown changed after they were recorded")
                .containsEntry("displayName", null);
        assertThat((List<Object>) carried.get("tags"))
                .containsExactly("a", (Object) null);
    }

    @Test
    void configurationHoldingANullIsLoadableToo() {
        // The rest of the sweep #132 asked for. These two are operator-supplied rather than
        // model-supplied, so the consequence is a config file that will not load rather
        // than a control that silently disengages — lower severity, same defect. A workflow
        // node's arguments end up in a ToolInvocation, where null is legal at both ends;
        // only the copy in the middle disagreed.
        Map<String, Object> nodeArguments = new LinkedHashMap<>();
        nodeArguments.put("email", "alice@example.com");
        nodeArguments.put("note", null);

        Workflow.Node node = new Workflow.Node("n1", Workflow.Node.Type.TOOL, "Suspend",
                "identity.suspend_user", nodeArguments, null, null);
        assertThat(node.arguments()).containsEntry("note", null);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scope", null);
        OpsScheduler.Schedule schedule = new OpsScheduler.Schedule("nightly", "0 2 * * *",
                Duration.ofHours(24), "it-ops-agent", input, true);
        assertThat(schedule.input()).containsEntry("scope", null);
    }

    @Test
    void theFrameworkReallyDoesAllowThisAndItIsNotJustOurRecordsBeingStrict() {
        // The positive control for the premise. Without it, every assertion above is a test
        // of a scenario the framework might have rejected upstream anyway — in which case
        // none of this was a control failing on a legal input, and the issue was wrong.
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("email", null);

        ToolInvocation invocation =
                new ToolInvocation("call-1", "identity.delete_user", arguments);

        assertThat(invocation.arguments()).containsEntry("email", null);
    }
}
