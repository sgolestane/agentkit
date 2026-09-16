package dev.agentkit.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Tool#boundTo} is tool-authored code on the dispatch path, and a fault in it costs
 * the parent link and nothing else (#317).
 *
 * <p>Written because the first draft of that seam called {@code boundTo} one statement
 * before {@code entered} is set, so a throw fell into {@code runTool}'s catch. Measured on
 * that draft, a tool whose {@code boundTo} threw:
 *
 * <pre>
 * disposition = GATE_FAILED
 * the tool     = never ran
 * the log      = "this deployment's policy code is failing"
 * </pre>
 *
 * <p>All three wrong, and the loudest sends an operator to audit a gate that did nothing.
 * A tool that worked before the seam existed does not stop working because its author got
 * the new method wrong.
 */
class ABindingFaultCostsOnlyTheLinkTest {

    /** A tool whose {@code boundTo} misbehaves in the way named, delegating the rest. */
    private static Tool bindingBadly(Function<AgentRun, Tool> boundTo) {
        FunctionTool inner = FunctionTool.builder("probe", "Answers, if it is allowed to")
                .schema(Map.of("type", "object", "properties", Map.of()))
                .readOnly()
                .handler(invocation -> ToolResult.ok("ran"))
                .build();
        return new Tool() {
            @Override public String name() { return inner.name(); }
            @Override public String description() { return inner.description(); }
            @Override public Map<String, Object> inputSchema() { return inner.inputSchema(); }
            @Override public ToolResult execute(ToolInvocation invocation) {
                return inner.execute(invocation);
            }
            @Override public Tool boundTo(AgentRun run) { return boundTo.apply(run); }
        };
    }

    private static Disposition dispositionOf(Tool tool, List<String> output) {
        List<Disposition> seen = new ArrayList<>();
        Agent agent = Agent.builder(
                        new FakeLlmClient(
                                FakeLlmClient.toolUse("t1", "probe", Map.of()),
                                FakeLlmClient.text("done")),
                        new SimpleToolRegistry(List.of(tool)),
                        AgentConfig.builder("m").maxSteps(4).build())
                .observer(new AgentObserver() {
                    @Override
                    public void onToolResult(AgentRun run, int step, ToolInvocation proposed,
                                             ToolInvocation effective, ToolResult result,
                                             Disposition disposition) {
                        seen.add(disposition);
                        output.add(result.content());
                    }
                })
                .build();
        agent.run(Goal.of("probe it"));
        return seen.get(0);
    }

    @Test
    @DisplayName("a boundTo that throws does not stop the tool running, and is not a gate failure")
    void aThrowingBoundToRunsUnbound() {
        List<String> output = new ArrayList<>();
        Disposition disposition = dispositionOf(
                bindingBadly(run -> {
                    throw new IllegalStateException("bind blew up");
                }), output);

        assertThat(disposition).isEqualTo(Disposition.RAN);
        assertThat(disposition).isNotEqualTo(Disposition.GATE_FAILED);
        assertThat(output).containsExactly("ran");
    }

    /** Same answer for the other way an implementation declines to answer. */
    @Test
    @DisplayName("a boundTo that returns null runs unbound rather than failing the call")
    void aNullReturningBoundToRunsUnbound() {
        List<String> output = new ArrayList<>();
        Disposition disposition = dispositionOf(bindingBadly(run -> null), output);

        assertThat(disposition).isEqualTo(Disposition.RAN);
        assertThat(output).containsExactly("ran");
    }

    /** The control: a well-behaved tool is unaffected by any of this. */
    @Test
    @DisplayName("a tool that binds normally still runs, so the tests above are about the fault")
    void aWellBehavedBoundToIsUnaffected() {
        List<String> output = new ArrayList<>();
        assertThat(dispositionOf(bindingBadly(run -> bindingBadly(r -> null)), output))
                .isEqualTo(Disposition.RAN);
        assertThat(output).containsExactly("ran");
    }
}
