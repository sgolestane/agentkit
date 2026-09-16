package dev.agentkit.temporal;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.reliability.TokenBudget;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.tool.ToolSpec;
import java.util.List;
import java.util.Objects;

/**
 * The complete, serializable input to an {@link AgentWorkflow}: the goal, the run
 * configuration, the advertised tool specs, and the activity tuning.
 *
 * <p>This is the durable analogue of constructing an in-process {@code Agent}
 * from {@code (config, tools)} and calling {@code run(goal)} — but the model
 * client and the tool implementations live in the worker's activity
 * implementations, not here. The workflow holds only what it can replay
 * deterministically.
 *
 * @param goal    the objective to pursue
 * @param config  the model/step configuration (its {@code maxSteps} bounds the loop)
 * @param tools   the tool specs advertised to the model each turn
 * @param options activity timeouts and retry limits
 * @param budget  an optional cumulative token/cost cap enforced by the workflow, or
 *                {@code null} for an uncapped run. This is how a durable run is
 *                budgeted: a {@code BudgetLlmClient} must not be used instead, because
 *                its tally is instance state shared by every workflow the worker
 *                serves (see {@link TemporalAgent#register}).
 */
public record DurableAgentRun(Goal goal, AgentConfig config, List<ToolSpec> tools,
                              DurableAgentOptions options,
                              @JsonInclude(JsonInclude.Include.NON_NULL) TokenBudget budget) {

    public DurableAgentRun {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(options, "options");
        tools = List.copyOf(tools);
        // `budget` is a component added after the initial release and is deliberately
        // nullable, for compatibility in BOTH directions:
        //   older payload -> newer code: input persisted before `budget` existed has no
        //     such field, and Jackson deserializes records through this canonical
        //     constructor, so null-tolerance keeps in-flight runs replayable (absent ==
        //     uncapped);
        //   newer payload -> older worker: @JsonInclude(NON_NULL) omits the field
        //     entirely for uncapped runs, so their payloads stay byte-identical to
        //     pre-`budget` ones and a not-yet-upgraded worker still reads them. (A
        //     worker that predates this field cannot read a *budgeted* payload — deploy
        //     workers before clients start setting budgets.)
    }

    /** Builds an uncapped run with default activity options and no tools. */
    public static DurableAgentRun of(Goal goal, AgentConfig config) {
        return of(goal, config, List.of());
    }

    /** Builds an uncapped run with default activity options. */
    public static DurableAgentRun of(Goal goal, AgentConfig config, List<ToolSpec> tools) {
        return new DurableAgentRun(goal, config, tools, DurableAgentOptions.defaults(), null);
    }

    /** This run with {@code options} applied, leaving everything else unchanged. */
    public DurableAgentRun withOptions(DurableAgentOptions options) {
        return new DurableAgentRun(goal, config, tools,
                Objects.requireNonNull(options, "options"), budget);
    }

    /** This run with {@code budget} applied, leaving everything else unchanged. */
    public DurableAgentRun withBudget(TokenBudget budget) {
        return new DurableAgentRun(goal, config, tools, options,
                Objects.requireNonNull(budget, "budget"));
    }
}
