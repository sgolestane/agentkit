package dev.agentkit.core.graph;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One unit of work in an {@link AgentGraph}: it receives the graph's goal plus whatever
 * its upstream nodes produced, and returns an {@link AgentResult}.
 *
 * <p>{@code AgentResult} rather than a graph-specific type because most nodes <em>are</em>
 * agent runs, and reusing it means a node's steps and token usage roll up into the
 * graph's totals for free. A node that is not an agent — a join, a filter, a call to
 * something that isn't a model — returns {@link AgentResult#completed(String, int)} with
 * zero steps.
 *
 * <p>A node that throws is recorded as a failed node rather than taking the graph down,
 * the same way a {@code Supervisor} treats a subagent that throws. Downstream nodes then
 * see the failure through their edge conditions.
 *
 * <p><strong>A lambda node fences nothing by itself.</strong> The {@link #agent} factories
 * below compose the goal through {@link NodeInput#renderDependencies()}, which fences every
 * upstream output. A node written as {@code input -> ...} composes its own goal, so an
 * upstream output reaching a model on that path is fenced only if the node asked for
 * {@link NodeInput#fencedOutputOf(String)} rather than {@link NodeInput#outputOf(String)}
 * — see {@code NodeInput} for what the raw call was measured to send (#72).
 */
@FunctionalInterface
public interface GraphNode {

    /** Runs this node. */
    AgentResult run(NodeInput input);

    /**
     * A node that runs a fresh {@link Agent} on a goal derived from the graph's goal and
     * its dependencies' outputs.
     *
     * <p>The agent is built per invocation for the same reason {@code Subagent} does it:
     * a registry that accumulates revealed tools or a working-memory scratchpad must not
     * leak between nodes running concurrently.
     */
    static GraphNode agent(Supplier<Agent> agentFactory, GoalBuilder goalBuilder) {
        Objects.requireNonNull(agentFactory, "agentFactory");
        Objects.requireNonNull(goalBuilder, "goalBuilder");
        return input -> agentFactory.get().run(goalBuilder.build(input));
    }

    /**
     * A node that runs a fresh agent on the graph's goal, plus every successful upstream
     * output, plus a line saying what <em>this</em> node is for — the shape most pipeline
     * nodes want.
     *
     * <p>The task line matters more than it looks. Without it every node in a pipeline is
     * handed the graph's goal verbatim, so the fact-checker in a "write a briefing" graph
     * is asked to write a briefing and has to be talked out of it by its system prompt.
     *
     * @param task what this node should do with what it is given, e.g.
     *             {@code "Check every claim against the sources"}
     */
    static GraphNode agent(String task, Supplier<Agent> agentFactory) {
        Objects.requireNonNull(task, "task");
        if (task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        return agent(agentFactory, input -> composeGoal(input, task));
    }

    /**
     * A node that runs a fresh agent on the graph's goal with every successful upstream
     * output appended, and no per-node task line. Prefer
     * {@link #agent(String, Supplier)} unless the node's agent already knows its job from
     * its system prompt.
     */
    static GraphNode agent(Supplier<Agent> agentFactory) {
        return agent(agentFactory, input -> composeGoal(input, null));
    }

    private static Goal composeGoal(NodeInput input, String task) {
        StringBuilder sb = new StringBuilder(input.goal().description());
        String upstream = input.renderDependencies();
        if (!upstream.isEmpty()) {
            sb.append("\n\n").append(upstream);
        }
        if (task != null) {
            sb.append("\n\nYour task now: ").append(task);
        }
        return new Goal(sb.toString(), input.goal().parameters());
    }

    /** Derives the goal a node pursues from the graph's goal and its dependencies. */
    @FunctionalInterface
    interface GoalBuilder {
        Goal build(NodeInput input);
    }
}
