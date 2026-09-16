package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.core.reflect.CorrectionBook;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.workbench.runtime.Learnings;
import dev.agentkit.workbench.runtime.Workbench;
import dev.agentkit.workbench.store.WorkbenchStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One console's worth of wiring, so a test says what it is about rather than how to build a
 * workbench.
 */
final class Console {

    static final String TENANT = "default";
    static final String OPERATOR = "sid@example.com";
    static final String JIRA = "https://acme.atlassian.net";

    static final ConsoleTools.Deployment DEPLOYMENT =
            new ConsoleTools.Deployment(TENANT, OPERATOR, JIRA);

    final ConsoleAlm alm;
    final WorkbenchStore store = new WorkbenchStore();
    final MemoryStore memory = MemoryStore.inMemory();
    final Learnings learnings = new Learnings(memory, TENANT);
    final CorrectionBook corrections = new CorrectionBook(memory);
    final dev.agentkit.workbench.runtime.StandingApprovals trusted =
            new dev.agentkit.workbench.runtime.StandingApprovals(memory);
    final ScriptedLlm llm = new ScriptedLlm();
    final Workbench workbench;

    /** Set by a test that wants the capture tool; absent means the console has no such tool. */
    dev.agentkit.workbench.capture.EvalCaptures captures;

    private final AtomicInteger calls = new AtomicInteger();
    private List<Tool> tools;

    private final ConsoleTools.Deployment deployment;

    Console(ConsoleAlm alm) {
        this(alm, DEPLOYMENT);
    }

    /** With a deployment of its own, for the tests about what a card can and cannot say. */
    Console(ConsoleAlm alm, ConsoleTools.Deployment deployment) {
        this.alm = alm;
        this.deployment = deployment;
        // The whole workbench, corrections and standing approvals included, because the
        // behaviours this module has to preserve — a rejection teaching the next run, a
        // standing refusal binding — do not exist without them.
        this.workbench = new Workbench(store, llm, "scripted", alm, learnings, TENANT,
                corrections, trusted);
    }

    /** The surface, built once so a test's writes are visible to its later reads. */
    List<Tool> tools() {
        if (tools == null) {
            tools = ConsoleTools.of(deployment, alm, workbench, null, learnings, store,
                    captures);
        }
        return tools;
    }

    Tool tool(String name) {
        return tools().stream()
                .filter(one -> one.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No console tool named " + name
                        + "; there are " + tools().stream().map(Tool::name).toList()));
    }

    ToolResult call(String name, Object... arguments) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < arguments.length; i += 2) {
            map.put(String.valueOf(arguments[i]), arguments[i + 1]);
        }
        return tool(name).execute(new ToolInvocation(
                "call-" + calls.incrementAndGet(), name, map));
    }

    /** The text of a successful call, with the assertion that it was one. */
    String say(String name, Object... arguments) {
        ToolResult result = call(name, arguments);
        assertThat(result.isError())
                .as("%s should have succeeded, and said: %s", name, result.content())
                .isFalse();
        return result.content();
    }
}
