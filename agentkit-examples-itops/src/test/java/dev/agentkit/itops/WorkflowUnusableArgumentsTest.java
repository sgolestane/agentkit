package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.util.Frozen;
import dev.agentkit.itops.connector.DirectoryConnector;
import dev.agentkit.itops.connector.IdentityConnector;
import dev.agentkit.itops.connector.ServiceNowConnector;
import dev.agentkit.itops.domain.Execution;
import dev.agentkit.itops.domain.Risk;
import dev.agentkit.itops.store.OpsStore;
import dev.agentkit.itops.workflow.Workflow;
import dev.agentkit.itops.workflow.WorkflowRunner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The fourth runner #246 names, and the one with no model in it at all.
 *
 * <h2>How a workflow graph reaches arguments this framework will not carry</h2>
 *
 * <p>Not through the graph. {@code Workflow.Node}'s own constructor freezes its arguments
 * through {@link Frozen#deeply}, so a workflow definition cannot hold a structure past the
 * bound — a fact worth stating, because it is what makes this reachable only through the
 * <em>other</em> door and makes the "a workflow author would not do that" reading wrong.
 *
 * <p>The other door is {@code $}-substitution. A node argument spelled {@code $name} is
 * replaced from this run's scope, and the scope starts as the caller's {@code variables} map
 * and then accumulates each step's output. Nothing freezes either. So the arguments a tool
 * is proposed are assembled at run time out of values the graph never saw, which is the same
 * reason the runner gates them at all — its own comment says a workflow author is not more
 * trusted than a model here, because the author wrote the graph before the arguments existed.
 *
 * <h2>Before and after</h2>
 *
 * <pre>
 * before -&gt; IllegalArgumentException out of invoke(), caught by the loop's escape hatch,
 *           EXECUTION_FAILED carrying "java.lang.IllegalArgumentException: tool arguments
 *           nest deeper than 100 levels", status FAILED
 * after  -&gt; the runner's ordinary vocabulary: an error Step, EXECUTION_FAILED carrying a
 *           sentence naming what was refused and what to send instead, status FAILED
 * </pre>
 *
 * <p>The status is the same on purpose. This runner has nobody to hand a refusal to and
 * nothing to reissue it — there is no next turn — so what changes is not whether the run
 * ends but whether the audit row an operator opens says something they can act on, and
 * whether the escape hatch that exists for a broken store is used for an ordinary refusal.
 */
class WorkflowUnusableArgumentsTest {

    private static final String TENANT = "acme";

    private static Map<String, Object> nested(int levels) {
        Map<String, Object> at = new LinkedHashMap<>();
        at.put("v", "x");
        for (int i = 1; i < levels; i++) {
            Map<String, Object> up = new LinkedHashMap<>();
            up.put("n", at);
            at = up;
        }
        return at;
    }

    /** One tool step whose only argument is read from the run's scope. */
    private static Workflow substitutesAVariable() {
        return new Workflow("unusable-probe", 1, "Unusable probe", "One substituted step.",
                List.of(
                        Workflow.Node.start(),
                        Workflow.Node.tool("step", "The substituted step",
                                "identity.get_group_members", Map.of("group", "$deep")),
                        Workflow.Node.end("done", "Done")),
                List.of(
                        Workflow.Edge.of("start", "step"),
                        Workflow.Edge.of("step", "done")),
                true);
    }

    private static WorkflowRunner runner(OpsStore store) {
        return new WorkflowRunner(store, new ServiceNowConnector(), new DirectoryConnector(),
                new IdentityConnector(), "agentkit-integration", Risk.HIGH);
    }

    @Test
    void aSubstitutedValuePastTheDepthCapIsRefusedThroughTheRunnersOwnVocabulary() {
        OpsStore store = new OpsStore();
        AtomicInteger gated = new AtomicInteger();
        ToolGate counting = (tool, invocation) -> {
            gated.incrementAndGet();
            return GateResult.allow();
        };

        WorkflowRunner.Result result = runner(store).run(TENANT, substitutesAVariable(),
                Map.of("deep", nested(Frozen.MAX_DEPTH + 1)), counting);

        assertThat(gated.get())
                .as("a gate judged arguments the tool never received")
                .isZero();
        assertThat(result.execution().status()).isEqualTo(Execution.Status.FAILED);
        assertThat(result.parked()).isFalse();

        List<Execution.Event> events = store.events(result.execution().id());
        List<Execution.Event.Type> types = events.stream().map(Execution.Event::type).toList();
        // TOOL_STARTED is appended after the arguments are assembled, so a refusal leaves no
        // half-open row behind — and the loop's escape hatch, which exists for a broken
        // store and for an Error, is no longer what closes an ordinary refused call.
        assertThat(types).doesNotContain(Execution.Event.Type.TOOL_STARTED);
        assertThat(types).contains(Execution.Event.Type.EXECUTION_FAILED);

        String reported = events.stream()
                .filter(e -> e.type() == Execution.Event.Type.EXECUTION_FAILED)
                .map(e -> String.valueOf(e.detail().get("error")))
                .findFirst()
                .orElseThrow();
        assertThat(reported)
                .as("the audit row still reports a stringified exception rather than a"
                        + " sentence naming what was refused")
                .doesNotContain("java.lang.IllegalArgumentException");
        assertThat(reported)
                .contains("refused before anything ran")
                .contains("No tool ran, no gate was asked")
                .contains(String.valueOf(Frozen.MAX_DEPTH));
        // The refusal reaches the caller and not only the store: outputs is what a caller
        // driving this runner reads back, and it is where the next step would have read from.
        assertThat(result.outputs().get("step")).contains("refused before anything ran");
    }

    @Test
    void aRefusalDoesNotTightenThePolicyForTheRestOfTheRun() {
        // The provenance half, which is not cosmetic on this runner: the loop asks
        // policy.lowersOn(result.provenance()) on every step's result, so a refusal left at
        // ToolResult.error's UNKNOWN default would let a call that never happened — nothing
        // resolved, nothing gated, nothing read — count as this run having read somebody
        // else's words. FIRST_PARTY is what the framework's own sentence is, and Agent's
        // equivalent branch declares it for the same reason.
        OpsStore store = new OpsStore();
        TrustFloor lowersOnAnythingUndeclared = TrustFloor.afterAnythingUndeclared(
                (tool, invocation) -> GateResult.allow(),
                (tool, invocation) -> GateResult.deny("tightened"));

        WorkflowRunner.Result result = runner(store).run(TENANT, substitutesAVariable(),
                Map.of("deep", nested(Frozen.MAX_DEPTH + 1)), lowersOnAnythingUndeclared);

        assertThat(store.events(result.execution().id()).stream()
                .map(Execution.Event::type).toList())
                .as("a call that never happened lowered the run's trust floor")
                .doesNotContain(Execution.Event.Type.TRUST_FLOOR_LOWERED);
    }

    @Test
    void aRunWhoseVariablesAreCarryableStillRunsTheStep() {
        // The control arm. Without it every assertion above is satisfied by a runner that
        // refuses everything, which is the failure direction a fail-closed change makes easy.
        OpsStore store = new OpsStore();
        AtomicInteger gated = new AtomicInteger();
        ToolGate counting = (tool, invocation) -> {
            gated.incrementAndGet();
            return GateResult.allow();
        };

        WorkflowRunner.Result result = runner(store).run(TENANT, substitutesAVariable(),
                Map.of("deep", "Production-Administrators"), counting);

        assertThat(gated.get()).isEqualTo(1);
        assertThat(result.execution().status()).isEqualTo(Execution.Status.COMPLETED);
    }
}
