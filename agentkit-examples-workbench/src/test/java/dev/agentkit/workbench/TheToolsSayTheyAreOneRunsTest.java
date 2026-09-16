package dev.agentkit.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.core.tool.DisclosingToolRegistry;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.workbench.runtime.AnswerBox;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.RunContext;
import dev.agentkit.workbench.store.WorkbenchStore;
import dev.agentkit.workbench.tools.ToolCatalog;
import org.junit.jupiter.api.Test;

/**
 * Every tool the catalog builds declares {@code Tool.boundToOneRun()} (#330), and the
 * wrapper that says so drops nothing else.
 *
 * <p>The tools close over the run's {@link RunContext} and {@link AnswerBox} — run-bound
 * state with no gate attached, which is the exact shape the declaration was added for: a
 * durable worker's registration check cannot infer it, so an undeclared catalog would
 * register clean and apply one run's audit trail and answers to every run on the queue.
 * The declaration travels on the tool so the one path that asks is told.
 *
 * <p>The second half matters as much: the wrapper is a decorator, and a decorator that
 * lost {@code sideEffects} or {@code provenance} on the way past would silently change
 * what a rehearsal refuses and what lowers a trust floor.
 */
class TheToolsSayTheyAreOneRunsTest {

    @Test
    void everyToolInTheCatalogDeclaresItAndKeepsItsOtherDeclarations() {
        WorkbenchStore store = new WorkbenchStore();
        RunContext context = new RunContext("default", "run-1", store);
        DisclosingToolRegistry registry = ToolCatalog.forRun(
                new FakeAlm(FakeAlm.ticket("IT-1", "A ticket", "A description.")),
                context, store, new Learnings(MemoryStore.inMemory(), "default"),
                AnswerBox.EMPTY);

        assertThat(registry.tools()).isNotEmpty();
        for (Tool tool : registry.tools()) {
            assertThat(tool.boundToOneRun())
                    .as("%s closes over the run's context and must say so", tool.name())
                    .isTrue();
        }
        // The declarations policy reads survived the decoration: reads stay rehearsable,
        // and third-party reads still lower a trust floor.
        Tool read = registry.find("jira.get_ticket").orElseThrow();
        assertThat(read.sideEffects()).isEqualTo(SideEffects.NONE);
        assertThat(read.provenance())
                .isEqualTo(dev.agentkit.core.tool.Provenance.THIRD_PARTY);
        Tool write = registry.find("jira.add_comment").orElseThrow();
        assertThat(write.sideEffects()).isEqualTo(SideEffects.EXTERNAL);
    }
}
