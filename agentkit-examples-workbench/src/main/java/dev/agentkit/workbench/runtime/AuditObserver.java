package dev.agentkit.workbench.runtime;

import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.OneLine;
import dev.agentkit.workbench.domain.Run;
import java.util.Map;

/**
 * Turns the agent loop's callbacks into the platform's audit trail.
 *
 * <p>Everything here is derived from what the runtime observed, never from what the model
 * said about itself: "what did it call, with what arguments, and what came back" is
 * answerable only from the outside. {@code onToolProposed} fires before the gate runs, so a
 * proposal appears here even when the supervisor goes on to refuse it — a refused action is
 * exactly what an incident review wants to see. The completion row records the
 * {@link Disposition}, because "did this action happen" is a seven-way answer, not a bit.
 */
public final class AuditObserver implements AgentObserver {

    private final RunContext context;

    public AuditObserver(RunContext context) {
        this.context = context;
    }

    @Override
    public void onStart(AgentRun run, Goal goal) {
        context.event(Run.Event.Type.RUN_STARTED,
                Map.of("agent", run.name(), "run", run.id()));
    }

    @Override
    public void onToolProposed(AgentRun run, int step, ToolInvocation invocation) {
        context.event(Run.Event.Type.TOOL_STARTED,
                Map.of("step", step, "tool", invocation.name(),
                        "proposedArguments", invocation.arguments()));
    }

    @Override
    public void onToolResult(AgentRun run, int step, ToolInvocation proposed,
                             ToolInvocation effective, ToolResult result,
                             Disposition disposition) {
        context.event(result.isError()
                        ? Run.Event.Type.TOOL_FAILED
                        : Run.Event.Type.TOOL_COMPLETED,
                Map.of("step", step, "tool", effective.name(),
                        "arguments", effective.arguments(),
                        "disposition", disposition.name(),
                        "result", Cut.to(OneLine.of(result.content() == null ? ""
                                : result.content()), 400)));
    }

    // No onFinish override: the closing row is written by Workbench, which knows the
    // durable status a stop reason maps to.
}
