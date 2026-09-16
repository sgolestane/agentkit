package dev.agentkit.eval;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import java.util.List;
import java.util.Objects;

/**
 * Everything observed from running one agent over one {@link EvalCase}: the goal,
 * the final {@link AgentResult}, and the ordered trajectory of {@link ToolCall}s
 * (each carrying whether it succeeded).
 *
 * <p>A {@link Check} scores against this — including the trajectory, which the
 * final result alone does not capture. That is the difference between an
 * outcome-only check and a genuine tool-use / trajectory evaluation.
 *
 * @param goal      the case's goal; never {@code null}
 * @param result    the agent's final result; never {@code null}
 * @param toolCalls the tool calls the agent made, in order; never {@code null}
 */
public record EvalRun(Goal goal, AgentResult result, List<ToolCall> toolCalls) {

    public EvalRun {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(toolCalls, "toolCalls");
        toolCalls = List.copyOf(toolCalls);
    }

    /** The names of every tool the agent <em>requested</em>, in order (with repeats). */
    public List<String> toolNames() {
        return toolCalls.stream().map(ToolCall::name).toList();
    }

    /** The names of the tools that actually ran and returned a non-error result. */
    public List<String> succeededToolNames() {
        return toolCalls.stream().filter(ToolCall::succeeded).map(ToolCall::name).toList();
    }
}
