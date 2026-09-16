package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.ToolInvocationRecord;
import dev.agentkit.itops.runtime.AuditObserver;
import dev.agentkit.itops.runtime.OpsContext;
import dev.agentkit.itops.store.OpsStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The audit row says whether the action happened (#181).
 *
 * <h2>The measured defect</h2>
 *
 * <p>{@code AuditObserver.onToolResult} wrote {@code verdict = result.isError() ? "ERROR" :
 * "OK"} into every {@link ToolInvocationRecord}. That bit is set on all seven ways a
 * proposed call can end, five of which never reached a tool — so a call the supervisor
 * parked and a tool that blew up half way through a group change were the same row, and
 * "did this action happen" was not answerable from the record that exists to answer it.
 * The issue names this observer as the reason the gap is not cosmetic.
 *
 * <p><strong>The verdict was one bit derived from another bit that is on the same row.</strong>
 * {@link ToolInvocationRecord#error()} already carried {@code isError}, so
 * {@code verdict} added nothing at all: two columns, one fact. It now carries the framework's
 * {@link Disposition}, which is the fact the column was named for.
 */
class AuditRowSaysWhetherItHappenedTest {

    private static final AgentRun RUN = AgentRun.of("executor");

    private static final ToolInvocation CALL =
            new ToolInvocation("c1", "disable_account", Map.of("email", "leaver@acme.test"));

    /** One observer callback, and the row it wrote. */
    private static ToolInvocationRecord rowFor(Disposition disposition, ToolResult result) {
        OpsStore store = new OpsStore();
        Execution execution = store.createExecution("acme", "it-ops-agent",
                Execution.Trigger.CHAT, "manual", "Clean up the leaver.");
        AuditObserver observer =
                new AuditObserver(new OpsContext("acme", execution.id(), store), store);

        observer.onToolProposed(RUN, 1, CALL);
        observer.onToolResult(RUN, 1, CALL, CALL, result, disposition);

        List<ToolInvocationRecord> rows = store.invocations(execution.id());
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static ToolResult refusal(String reason) {
        return ToolResult.from(Provenance.FIRST_PARTY, reason).asError();
    }

    @Test
    void aParkedCallAndAToolThatBlewUpAreNotTheSameRow() {
        // The sentence from the issue, as an assertion. Both rows are errors, both name the
        // same call, and before this both said "ERROR".
        ToolInvocationRecord parked =
                rowFor(Disposition.PARKED, refusal("a person must approve this"));
        ToolInvocationRecord threw = rowFor(Disposition.THREW,
                ToolResult.error("Tool 'disable_account' failed."));

        assertThat(parked.error()).isTrue();
        assertThat(threw.error()).isTrue();
        assertThat(parked.verdict())
                .as("a call that never ran and a call that may have half-run are one row")
                .isNotEqualTo(threw.verdict());
        assertThat(parked.verdict()).isEqualTo("PARKED");
        assertThat(threw.verdict()).isEqualTo("THREW");
    }

    @Test
    void aRefusalAndAToolThatReturnedAnErrorAreNotTheSameRow() {
        ToolInvocationRecord refused =
                rowFor(Disposition.REFUSED, refusal("policy forbids this account"));
        ToolInvocationRecord errored =
                rowFor(Disposition.RAN, ToolResult.error("the directory rejected the change"));

        assertThat(refused.verdict()).isEqualTo("REFUSED");
        assertThat(errored.verdict())
                .as("the tool ran and reported a failure, which is not a governance event")
                .isEqualTo("RAN");
    }

    @Test
    void aSuccessfulCallIsStillReadableAsOne() {
        // The row an auditor reads most, and the one the old vocabulary got right. It must
        // not have become harder to read.
        ToolInvocationRecord ran = rowFor(Disposition.RAN, ToolResult.ok("account disabled"));

        assertThat(ran.error()).isFalse();
        assertThat(ran.verdict()).isEqualTo("RAN");
    }

    @Test
    void theTimelineCarriesItToo() {
        // The rows are what an auditor queries; the event stream is what a reviewer reads
        // first, and TOOL_FAILED alone cannot say whether anything happened.
        OpsStore store = new OpsStore();
        Execution execution = store.createExecution("acme", "it-ops-agent",
                Execution.Trigger.CHAT, "manual", "Clean up the leaver.");
        AuditObserver observer =
                new AuditObserver(new OpsContext("acme", execution.id(), store), store);

        observer.onToolProposed(RUN, 1, CALL);
        observer.onToolResult(RUN, 1, CALL, CALL, refusal("a person must approve this"),
                Disposition.PARKED);

        Execution.Event completion = store.events(execution.id()).stream()
                .filter(e -> e.type() == Execution.Event.Type.TOOL_FAILED)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(completion.detail()).containsEntry("disposition", "PARKED");
        // And which agent proposed it, which is the row's answer to "who" once an execution
        // delegates and `step` stops being unique across the trail (#311).
        assertThat(completion.detail()).containsEntry("agent", RUN.name());
    }
}
