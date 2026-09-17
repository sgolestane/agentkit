package dev.agentkit.examples.onboarding;

import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.OneLine;
import java.io.PrintStream;
import java.util.Objects;

/** Prints each tool call as the executor makes it: {@code ok:} for a result, {@code !!} for an error. */
public final class ToolCallPrinter implements AgentObserver {

    private final PrintStream out;

    public ToolCallPrinter(PrintStream out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public void onToolResult(AgentRun run, int step, ToolInvocation proposed, ToolInvocation effective,
                             ToolResult result, Disposition disposition) {
        out.println("  -> " + effective.name() + " " + effective.arguments()
                + (result.isError() ? "  !! " : "  ok: ") + OneLine.of(result.content()));
    }
}
