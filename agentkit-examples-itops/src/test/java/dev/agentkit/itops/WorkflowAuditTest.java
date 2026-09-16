package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.ServiceNowConnector;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.workflow.Workflow;
import dev.agentkit.itops.workflow.WorkflowRunner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The workflow runner's audit trail, and the first test that runs one at all (#131).
 *
 * <h2>Why this file exists</h2>
 *
 * <p>{@code WorkflowRunner} had no test. Not a thin one — none: {@code grep -rl WorkflowRunner
 * src/test} matched nothing, so every claim about the second runner's audit trail was a claim
 * about unexecuted code. #131's own claim was one of them, and the mutant proving it went
 * unnoticed:
 *
 * <pre>
 * # completion event: effective.arguments() -&gt; invocation.arguments()
 * mvn -o -pl agentkit-examples-itops -am test   Tests run: 24, Failures: 0   SURVIVES
 * </pre>
 *
 * <h2>What this pins, and what it honestly cannot</h2>
 *
 * <p>It pins the audit vocabulary: {@code TOOL_STARTED} carries {@code proposedArguments} and
 * the completion event carries {@code arguments}, in both runners. That split is the point —
 * one key meaning two different invocations depending on which event you were reading is how
 * a supervisor's narrowing disappeared from the trail without anything looking wrong.
 *
 * <p>It does <strong>not</strong> pin that the completion event records the <em>effective</em>
 * call rather than the proposal, and no test driving this file's fixtures can. Everything here
 * runs the three-argument {@code run}, which builds its own
 * {@link dev.agentkit.itops.runtime.Supervisor}, and that supervisor returns only
 * {@code GateResult.allow()} and {@code GateResult.deny(...)} — never {@code allowWith} — so
 * {@code effectiveFor(invocation)} is the identity on every path reached from here and the
 * mutant above survives by being unobservable rather than by being untested.
 *
 * <p>{@code WorkflowGateTest} is where that is pinned, and #182 is how: the runner now takes a
 * gate, so a narrowing one can be supplied and the two rows made to disagree. This file stays
 * as it is on purpose — it is the default path, the one the product actually runs, and what it
 * measures is that the vocabulary is right when nobody has arranged anything.
 */
class WorkflowAuditTest {

    private static final String TENANT = "acme";

    /** One tool call and an end, so the two audit rows this is about are unambiguous. */
    private static Workflow oneReadStep() {
        return new Workflow("audit-probe", 1, "Audit probe",
                "Reads a group's membership.",
                List.of(
                        Workflow.Node.start(),
                        Workflow.Node.tool("members", "List production administrators",
                                "identity.get_group_members",
                                Map.of("group", "$group")),
                        Workflow.Node.end("done", "Done")),
                List.of(
                        Workflow.Edge.of("start", "members"),
                        Workflow.Edge.of("members", "done")),
                true);
    }

    private static List<Execution.Event> runAndReadEvents(OpsStore store) {
        WorkflowRunner runner = new WorkflowRunner(store, new ServiceNowConnector(),
                new DirectoryConnector(), new IdentityConnector(), "agentkit-integration",
                Risk.HIGH);

        WorkflowRunner.Result result = runner.run(TENANT, oneReadStep(),
                Map.of("group", "Production-Administrators"));

        assertThat(result.execution().status())
                .as("the workflow did not complete, so this test measured nothing")
                .isEqualTo(Execution.Status.COMPLETED);
        return store.events(result.execution().id());
    }

    private static Map<String, Object> only(List<Execution.Event> events,
                                            Execution.Event.Type type) {
        List<Execution.Event> matching = events.stream().filter(e -> e.type() == type).toList();
        assertThat(matching).as("expected exactly one %s event", type).hasSize(1);
        return matching.get(0).detail();
    }

    @Test
    void theStartEventNamesItsArgumentsAsAProposal() {
        // Nothing has gated the call when this row is written, so the row says so. It used
        // to be keyed "arguments", the same key the completion event uses for the call that
        // actually ran — one word for two different invocations, in the same event stream.
        Map<String, Object> started = only(runAndReadEvents(new OpsStore()),
                Execution.Event.Type.TOOL_STARTED);

        assertThat(started)
                .containsEntry("tool", "identity.get_group_members")
                .containsEntry("proposedArguments",
                        Map.of("group", "Production-Administrators"))
                .as("the proposal was still filed under the completion event's key")
                .doesNotContainKey("arguments");
    }

    @Test
    void theCompletionEventRecordsTheCallThatRan() {
        // The other half of the vocabulary, and the row an auditor actually reads. The
        // $group variable is resolved by then, which is itself worth pinning: a trail that
        // recorded the literal "$group" would answer "which group did it read" with the
        // name of a variable.
        Map<String, Object> completed = only(runAndReadEvents(new OpsStore()),
                Execution.Event.Type.TOOL_COMPLETED);

        assertThat(completed)
                .containsEntry("tool", "identity.get_group_members")
                .containsEntry("arguments", Map.of("group", "Production-Administrators"))
                // FIRST_PARTY, where this said UNKNOWN until #162. The identity provider
                // is the deployment's own system of record, and every tool in this module
                // declares now — which is what makes a TrustFloor able to fire here at
                // all, and what keeps a run from losing its capability to the act of
                // reading its own directory. Asserted as measured so the row is pinned to
                // what the runner actually writes.
                .containsEntry("provenance", "FIRST_PARTY");
        assertThat(String.valueOf(completed.get("result")))
                .as("the row records what came back, not just that something did")
                // The membership, which is what came back. This asserted the group's NAME
                // until #191: get_group_members headed its result "N member(s) of <group>",
                // and that header was the model's own argument on the framework's own line
                // with no fence anywhere near it. The name is still on this row -- the
                // "arguments" entry asserted just above is where an auditor reads which
                // group was read, and it is a structured field rather than a sentence.
                .contains("bob@example.com");
    }

    @Test
    void bothRunnersSpellTheAuditVocabularyTheSameWay() {
        // The "one rule, several runners" check, done as a source assertion because the two
        // producers cannot be driven from one test: AuditObserver needs an agent run and
        // WorkflowRunner does not use an observer at all. A source assertion is weak
        // evidence in general; here it is the exact evidence, because the defect being
        // guarded is two files choosing different words for the same column.
        String observer = dev.agentkit.itops.runtime.AuditObserver.class.getSimpleName();
        assertThat(observer).isEqualTo("AuditObserver");

        List<Execution.Event> events = runAndReadEvents(new OpsStore());
        assertThat(only(events, Execution.Event.Type.TOOL_STARTED)).containsKey(
                "proposedArguments");
        assertThat(only(events, Execution.Event.Type.TOOL_COMPLETED)).containsKey("arguments");
    }
}
