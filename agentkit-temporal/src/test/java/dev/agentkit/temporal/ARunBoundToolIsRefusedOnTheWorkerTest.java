package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A tool that belongs to one run must not register on a worker that serves every run (#328).
 *
 * <p>The two declarations this check already asked about are both about a <em>gate</em> a
 * tool holds. An {@code AgentScope}-backed tool holds no gate at all — it closes over one
 * run's outstanding work and its {@code AgentRun} — so it would have registered clean and
 * handed every run on the task queue a stranger's scope.
 *
 * <p>That is #313's shape one level along: a hazard nothing can infer, reaching the durable
 * path through a door the existing check cannot see.
 */
class ARunBoundToolIsRefusedOnTheWorkerTest {

    private static Tool declaring(boolean boundToOneRun) {
        FunctionTool inner = FunctionTool.builder("scoped", "holds a run's state")
                .schema(Map.of("type", "object", "properties", Map.of()))
                .readOnly()
                .handler(invocation -> ToolResult.ok("ran"))
                .build();
        return new Tool() {
            @Override public String name() { return inner.name(); }
            @Override public String description() { return inner.description(); }
            @Override public Map<String, Object> inputSchema() { return inner.inputSchema(); }
            @Override public ToolResult execute(ToolInvocation i) { return inner.execute(i); }
            @Override public boolean boundToOneRun() { return boundToOneRun; }
        };
    }

    @Test
    @DisplayName("a tool that says it belongs to one run is refused at registration")
    void aRunBoundToolIsRefused() {
        assertThatThrownBy(() -> new ToolActivitiesImpl(
                new SimpleToolRegistry(List.of(declaring(true))), ToolGates.allowAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'scoped'")
                .hasMessageContaining("one run")
                .hasMessageContaining("Tool.boundToOneRun()");
    }

    /** The control: the identical tool declaring nothing registers, so the test is about the bit. */
    @Test
    @DisplayName("the same tool that does not declare it registers")
    void theSameToolWithoutTheDeclarationRegisters() {
        assertThatCode(() -> new ToolActivitiesImpl(
                new SimpleToolRegistry(List.of(declaring(false))), ToolGates.allowAll()))
                .doesNotThrowAnyException();
    }
}
