package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.ToolInvocationRecord;
import dev.agentkit.itops.runtime.AuditObserver;
import dev.agentkit.itops.runtime.OpsContext;
import dev.agentkit.itops.store.OpsStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The compliance row has to name the call the supervisor settled on (#131).
 *
 * <h2>Why this is a separate file from the core test</h2>
 *
 * <p>Because the core test pins the framework and this pins the consequence. The itops
 * example is the module where the observer stream <em>is</em> the audit trail, so
 * {@code ToolInvocationRecord} is where #131 is either true or false for an auditor —
 * and the record's own javadoc has always described {@code arguments} as "the arguments
 * as sent, after any supervisor edit", which was false for exactly the runs a supervisor
 * did something to.
 *
 * <h2>Why the agent is built here rather than through ExecutionRunner</h2>
 *
 * <p>{@code ExecutionRunner} wires {@code Supervisor}, and {@code Supervisor} returns only
 * {@code GateResult.allow()} and {@code GateResult.deny(...)} — never {@code allowWith}. So
 * a run driven through the module's own entry point cannot narrow anything, and the mutant
 * that makes {@code AuditObserver} record the proposal survives the whole suite by being
 * unobservable:
 *
 * <pre>
 * # AuditObserver: effective.arguments() -&gt; proposed.arguments()
 * mvn -o -pl agentkit-examples-itops -am test   Tests run: 27, Failures: 0   SURVIVES
 * </pre>
 *
 * <p>{@code AuditObserver} is public and takes its context and store directly, so attaching
 * it to an agent with a narrowing gate measures the thing itself rather than the one gate
 * that happens to be wired today. That is also the honest scope: this pins the observer, not
 * {@code Supervisor}, which does not yet have a reason to narrow.
 */
class AuditRowShowsWhatRanTest {

    private static final String TENANT = "acme";

    /** Records what it was handed, so "did the narrowing reach the tool" is answerable. */
    private record Reader(List<String> received) implements Tool {
        public String name() {
            return "identity.get_group_members";
        }

        public String description() {
            return "lists a group's members";
        }

        public Map<String, Object> inputSchema() {
            return Map.of("type", "object");
        }

        public ToolResult execute(ToolInvocation invocation) {
            received.add(String.valueOf(invocation.arguments().get("group")));
            return ToolResult.ok("bob@example.com");
        }
    }

    /** Asks for one group, then stops. Core's FakeLlmClient is test-scoped to core. */
    private static final class AsksForOneGroup implements LlmClient {
        private boolean asked;

        @Override
        public LlmResponse generate(LlmRequest request) {
            if (asked) {
                return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of("done")),
                        LlmStopReason.END_TURN, TokenUsage.ZERO);
            }
            asked = true;
            return LlmResponse.of(Message.of(Role.ASSISTANT,
                            ProposedCall.of("c1", "identity.get_group_members",
                                    Map.of("group", "Production-Administrators"))),
                    LlmStopReason.TOOL_USE, TokenUsage.ZERO);
        }
    }

    /** A supervisor that permits the call but narrows what it reaches. */
    private static ToolGate narrowingTo(String group) {
        return (tool, invocation) -> GateResult.allowWith(
                new ToolInvocation(invocation.id(), invocation.name(), Map.of("group", group)));
    }

    private static List<ToolInvocationRecord> runWith(ToolGate gate, List<String> received) {
        OpsStore store = new OpsStore();
        Execution execution = store.createExecution(TENANT, "it-ops-agent",
                Execution.Trigger.CHAT, "manual", "List the administrators.");
        OpsContext context = new OpsContext(TENANT, execution.id(), store);

        Agent.builder(
                        new AsksForOneGroup(),
                        new SimpleToolRegistry().register(new Reader(received)),
                        AgentConfig.builder("m").maxSteps(3).build())
                .toolGate(gate)
                .observer(new AuditObserver(context, store))
                .build()
                .run(Goal.of("list the administrators"));

        return store.invocations(execution.id());
    }

    @Test
    void theRecordNamesTheNarrowedCallAndNotTheProposal() {
        List<String> received = new ArrayList<>();

        List<ToolInvocationRecord> rows = runWith(narrowingTo("Employees-All"), received);

        assertThat(received)
                .as("the gate did not narrow anything, so this test measured nothing")
                .containsExactly("Employees-All");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).arguments())
                .as("the audit row named the group the model asked for, which is the one"
                        + " thing the supervisor had ruled out")
                .isEqualTo(Map.of("group", "Employees-All"));
    }

    @Test
    void theIdempotencyKeyCoversTheCallThatRan() {
        // "What makes a repeat of this call the same call", per its own javadoc, and it is
        // a hash of the arguments. Keyed on the proposal, two runs the supervisor narrowed
        // to *different* groups would present the same key to an external API — which is
        // the direction that makes a duplicate look safe when it is a different action.
        //
        // The model asks for the same group in both runs, so the proposal is identical by
        // construction and only the narrowing differs.
        String narrowed = hashOf(runWith(narrowingTo("Employees-All"), new ArrayList<>()));
        String other = hashOf(runWith(narrowingTo("Finance Admin"), new ArrayList<>()));

        assertThat(narrowed)
                .as("two calls the supervisor narrowed to different groups shared an"
                        + " idempotency key, because both were keyed on the same proposal")
                .isNotEqualTo(other);
    }

    /** The argument hash, without the per-run execution id the key is prefixed with. */
    private static String hashOf(List<ToolInvocationRecord> rows) {
        String key = rows.get(0).idempotencyKey();
        return key.substring(key.lastIndexOf(':') + 1);
    }

    @Test
    void anUngatedCallStillRecordsWhatTheModelAsked() {
        // The common case: with nothing editing the call, the row is the proposal because
        // the proposal is what ran. The change is invisible here, which is the point.
        List<String> received = new ArrayList<>();

        List<ToolInvocationRecord> rows =
                runWith((tool, invocation) -> GateResult.allow(), received);

        assertThat(received).containsExactly("Production-Administrators");
        assertThat(rows.get(0).arguments())
                .isEqualTo(Map.of("group", "Production-Administrators"));
    }
}
