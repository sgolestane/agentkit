package dev.agentkit.workbench.runtime;

import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Objects;

/**
 * One run, more than one witness: {@code Agent.Builder.observer} holds a single observer,
 * and a run has the audit trail to write plus whatever extra witness a caller wired.
 * Every callback is overridden — they are all defaults on {@link AgentObserver}, so a
 * forwarder that missed one would silently stop forwarding it.
 */
public final class Observers implements AgentObserver {

    private final List<AgentObserver> all;

    private Observers(List<AgentObserver> all) {
        this.all = List.copyOf(all);
    }

    /** Everything in {@code observers}, told in order, on every callback. */
    public static AgentObserver of(AgentObserver... observers) {
        Objects.requireNonNull(observers, "observers");
        for (AgentObserver observer : observers) {
            Objects.requireNonNull(observer, "observer");
        }
        return new Observers(List.of(observers));
    }

    @Override
    public void onStart(AgentRun run, Goal goal) {
        for (AgentObserver observer : all) {
            observer.onStart(run, goal);
        }
    }

    @Override
    public void onTextDelta(AgentRun run, int step, String delta) {
        for (AgentObserver observer : all) {
            observer.onTextDelta(run, step, delta);
        }
    }

    @Override
    public void onModelResponse(AgentRun run, int step, LlmResponse response) {
        for (AgentObserver observer : all) {
            observer.onModelResponse(run, step, response);
        }
    }

    @Override
    public void onToolProposed(AgentRun run, int step, ToolInvocation invocation) {
        for (AgentObserver observer : all) {
            observer.onToolProposed(run, step, invocation);
        }
    }

    @Override
    public void onToolResult(AgentRun run, int step, ToolInvocation proposed,
                             ToolInvocation effective, ToolResult result,
                             Disposition disposition) {
        for (AgentObserver observer : all) {
            observer.onToolResult(run, step, proposed, effective, result, disposition);
        }
    }

    @Override
    public void onFinish(AgentRun run, AgentResult result) {
        for (AgentObserver observer : all) {
            observer.onFinish(run, result);
        }
    }
}
