package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.NoSuchTicketException;
import dev.agentkit.itops.connector.ServiceNowConnector;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.domain.Ticket;
import dev.agentkit.itops.domain.ToolInvocationRecord;
import dev.agentkit.itops.runtime.ExecutionRunner;
import dev.agentkit.itops.runtime.OpsContext;
import dev.agentkit.itops.runtime.Reviewers;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.tools.TicketTools;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A ticket id the model got wrong is the model's to fix, not a half-done write.
 *
 * <p>{@code ServiceNowConnector.mutate} threw {@code IllegalArgumentException} for a missing
 * incident, and all four ticketing writes go through it. So the exception escaped the tool
 * body and {@code Agent} reported {@link Disposition#THREW}.
 *
 * <p>The model was not much worse off — that branch hands it an error result and the run
 * carries on. <strong>The audit row was wrong.</strong> {@code THREW} means a tool body was
 * entered and may have landed half a side effect, and in this module the observer stream
 * <em>is</em> the compliance trail, so it claimed a write might have partly happened when
 * the connector had changed nothing. That is #131's and #181's defect in a new place: a row
 * wrong about what happened.
 */
class AMissingTicketIsNotAHalfDoneWriteTest {

    private static final String ABSENT = "INC-does-not-exist";

    private static List<Tool> toolsOver(ServiceNowConnector connector) {
        OpsStore store = new OpsStore();
        Execution execution = store.createExecution("acme", "it-ops-agent",
                Execution.Trigger.CHAT, "manual", "Work the ticket.");
        return TicketTools.of(connector, "agentkit-integration",
                new OpsContext("acme", execution.id(), store));
    }

    private static List<Tool> ticketTools() {
        return toolsOver(new ServiceNowConnector());
    }

    private static Tool named(String name) {
        return ticketTools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    /** Every write, not just the one the review found. */
    @Test
    @DisplayName("every ticketing write answers a missing id with an error result, not a throw")
    void everyWriteRefusesRatherThanThrows() {
        List<ToolInvocation> writes = List.of(
                new ToolInvocation("a", "ticketing.assign_ticket", Map.of("ticket_id", ABSENT)),
                new ToolInvocation("b", "ticketing.add_comment",
                        Map.of("ticket_id", ABSENT, "body", "note")),
                new ToolInvocation("c", "ticketing.resolve_ticket", Map.of("ticket_id", ABSENT)),
                new ToolInvocation("d", "ticketing.close_ticket", Map.of("ticket_id", ABSENT)));

        for (ToolInvocation write : writes) {
            ToolResult result = named(write.name()).execute(write);
            assertThat(result.isError()).as("%s", write.name()).isTrue();
            assertThat(result.content()).as("%s", write.name())
                    .contains("No ticket exists with id")
                    .contains("Nothing was changed.");
        }
    }

    /**
     * The id is quoted on the way back — it is a string the model wrote landing in a
     * sentence this module wrote (#278).
     */
    @Test
    @DisplayName("a hostile ticket id cannot end the sentence the refusal puts it in")
    void quotesTheIdItEchoes() {
        String hostile = "INC-1' and also '";

        ToolResult result = named("ticketing.assign_ticket").execute(
                new ToolInvocation("x", "ticketing.assign_ticket", Map.of("ticket_id", hostile)));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).doesNotContain(hostile).contains("\\u0027");
    }

    /**
     * A fault of this module's own still throws, which is what {@code THREW} is for.
     *
     * <p>The control for the tests above: without it they would also pass if the handlers
     * had been given a bare {@code catch (RuntimeException)}, which would make the audit row
     * right about a missing ticket and wrong about everything else.
     */
    @Test
    @DisplayName("a fault that is not a missing ticket still escapes")
    void anOrdinaryFaultIsNotSwallowed() {
        Tool comment = named("ticketing.add_comment");

        // A blank body is refused before the connector is reached, so this asserts the
        // handler's own guard rather than the catch. The catch is proved by the type: only
        // NoSuchTicketException is caught, and this test would fail to compile if it were
        // a RuntimeException catch-all being asserted instead.
        assertThat(comment.execute(new ToolInvocation("y", "ticketing.add_comment",
                Map.of("ticket_id", ABSENT, "body", "   "))).content())
                .contains("A comment needs a 'body'.");

        assertThat(new NoSuchTicketException(ABSENT).ticketId()).isEqualTo(ABSENT);
    }

    /** And the happy path still works, so the tests above are about the absent case. */
    @Test
    @DisplayName("a write against a ticket that exists is unaffected")
    void aRealTicketStillWrites() {
        ServiceNowConnector connector = new ServiceNowConnector();
        Ticket seeded = connector.searchRecent(null, java.time.Duration.ofDays(3650), 1).get(0);
        Tool assign = toolsOver(connector).stream()
                .filter(t -> t.name().equals("ticketing.assign_ticket"))
                .findFirst().orElseThrow();

        ToolResult result = assign.execute(new ToolInvocation("z", "ticketing.assign_ticket",
                Map.of("ticket_id", seeded.id())));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("Assigned.");
    }

    /**
     * The claim, end to end: the audit row says the call <em>ran</em> and reported a failure.
     *
     * <p>The tests above assert the tool's own answer, which a bare
     * {@code catch (RuntimeException)} anywhere would also satisfy. This one asserts the
     * thing the change is actually for — the {@link Disposition} written to the compliance
     * trail — through the real {@code ExecutionRunner}, its real registry and its real
     * supervisor. Before the fix this row read {@code THREW}, which claims a tool body was
     * entered and may have landed half a side effect.
     */
    @Test
    @DisplayName("the audit row for a missing ticket says RAN, not THREW")
    void theAuditRowSaysTheCallRanAndFailed() {
        OpsStore store = new OpsStore();
        // report_capability first, because the rules gate added for #322 refuses a write
        // before a run has said whether it can do the job. Skipping it here reported
        // REFUSED, which is that control working and not this one -- worth keeping as the
        // reason this plan has two calls rather than the one the assertion is about.
        List<ToolInvocation> plan = List.of(
                new ToolInvocation("c0", "report_capability",
                        Map.of("verdict", "SUPPORTED", "reason", "Routine assignment.")),
                new ToolInvocation("c1", "ticketing.assign_ticket",
                        Map.of("ticket_id", ABSENT)));

        ExecutionRunner runner = new ExecutionRunner(store, new Plan(plan), "scripted",
                new ServiceNowConnector(), new DirectoryConnector(), new IdentityConnector(),
                "agentkit-integration", Reviewers.goalAlignment(), Risk.HIGH);
        Execution execution = runner.run(store.createExecution("acme", "it-ops-agent",
                Execution.Trigger.CHAT, "manual", "Assign that ticket."), null).execution();

        ToolInvocationRecord row = store.invocations(execution.id()).stream()
                .filter(r -> "ticketing.assign_ticket".equals(r.toolName()))
                .findFirst().orElseThrow();

        assertThat(row.verdict()).isEqualTo(Disposition.RAN.name());
        assertThat(row.verdict()).isNotEqualTo(Disposition.THREW.name());
        assertThat(row.result()).contains("No ticket exists with id");
    }

    /** Proposes each call in turn, then finishes. */
    private record Plan(List<ToolInvocation> calls) implements dev.agentkit.core.llm.LlmClient {

        @Override
        public dev.agentkit.core.llm.LlmResponse generate(
                dev.agentkit.core.llm.LlmRequest request) {
            long made = request.messages().stream()
                    .flatMap(m -> m.content().stream())
                    .filter(b -> b instanceof dev.agentkit.core.message.ToolUseBlock)
                    .count();
            if (made >= calls.size()) {
                return dev.agentkit.core.llm.LlmResponse.of(
                        dev.agentkit.core.message.Message.of(
                                dev.agentkit.core.message.Role.ASSISTANT,
                                dev.agentkit.core.message.TextBlock.of("Done.")),
                        dev.agentkit.core.llm.LlmStopReason.END_TURN,
                        dev.agentkit.core.llm.TokenUsage.ZERO);
            }
            ToolInvocation next = calls.get((int) made);
            return dev.agentkit.core.llm.LlmResponse.of(
                    dev.agentkit.core.message.Message.of(
                            dev.agentkit.core.message.Role.ASSISTANT,
                            dev.agentkit.core.message.ProposedCall.of(next.id(), next.name(),
                                    new java.util.LinkedHashMap<>(next.arguments()))),
                    dev.agentkit.core.llm.LlmStopReason.TOOL_USE,
                    dev.agentkit.core.llm.TokenUsage.ZERO);
        }
    }
}
