package dev.agentkit.examples.planexecute;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.planning.LlmPlanner;
import dev.agentkit.core.planning.PlanExecution;
import dev.agentkit.core.planning.PlanningAgent;
import dev.agentkit.core.tool.ToolRegistry;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Plans a goal once, then carries out each step with a fresh agent: {@link PlanningAgent}, wired from
 * two system prompts, a model, a tool registry and an observer.
 *
 * <p>Nothing here knows what the goal is about. Whatever a use case needs to say — a policy with
 * conditions, the facts of the case — goes in the {@link Goal}, and how the planner and the executor
 * should treat it goes in their prompts. The planner makes one tool-free call and returns an ordered
 * plan; each step then runs on a new {@link Agent} with a new registry from {@code tools}, so nothing a
 * step discovers leaks into the next, and every step reports to the same {@code observer}.
 */
public final class PlanExecuteAgent {

    /** The most loop turns an executor spends on one step. */
    public static final int MAX_STEPS_PER_STEP = 8;

    /** The most tokens the planner or an executor turn may produce. */
    public static final int MAX_TOKENS = 1024;

    private final LlmClient llm;
    private final String model;
    private final String plannerPrompt;
    private final String executorPrompt;
    private final Supplier<? extends ToolRegistry> tools;
    private final AgentObserver observer;

    /**
     * @param llm            the model client
     * @param model          the model id, for planning and execution
     * @param plannerPrompt  the planner's system prompt
     * @param executorPrompt the system prompt every step runs with
     * @param tools          builds the registry for one step; called once per step
     * @param observer       sees every step's tool calls; {@link AgentObserver#NONE} if nothing needs to
     */
    public PlanExecuteAgent(LlmClient llm, String model, String plannerPrompt, String executorPrompt,
                            Supplier<? extends ToolRegistry> tools, AgentObserver observer) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.model = Objects.requireNonNull(model, "model");
        this.plannerPrompt = Objects.requireNonNull(plannerPrompt, "plannerPrompt");
        this.executorPrompt = Objects.requireNonNull(executorPrompt, "executorPrompt");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /** Plans {@code goal}, then carries out the plan. */
    public PlanExecution run(Goal goal) {
        Objects.requireNonNull(goal, "goal");
        LlmPlanner planner = new LlmPlanner(llm, model, plannerPrompt, MAX_TOKENS);
        AgentConfig executor = AgentConfig.builder(model)
                .systemPrompt(executorPrompt)
                .maxSteps(MAX_STEPS_PER_STEP)
                .maxTokens(MAX_TOKENS)
                .build();
        return new PlanningAgent(planner, () -> Agent.builder(llm, tools.get(), executor)
                .observer(observer)
                .build())
                .run(goal);
    }
}
