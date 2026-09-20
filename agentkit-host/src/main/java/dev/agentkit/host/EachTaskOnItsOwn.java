package dev.agentkit.host;

import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.ContentBlock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A message that holds several of a plan-execute agent's tasks — its form filled in and pasted once for each — carried
 * out as that many tasks, one after another, in the one turn. Each is its own form task: checked, planned (or its
 * settled plan reused) and carried out on its own, so one refused or failed does not stop the others, and each plan
 * is shown for the task it is for. The answer has a section for each, headed by the task's label.
 */
final class EachTaskOnItsOwn implements ChatRuntime.Runner {

    /** The most tasks one message may hold. */
    static final int MAX_TASKS = 10;

    private final List<Map<String, Object>> inputs;
    private final Function<Map<String, Object>, String> label;
    private final Function<Map<String, Object>, String> request;
    private final BiFunction<Map<String, Object>, String, ChatRuntime.Runner> runner;

    /**
     * @param inputs  each task's input, in the order the message gives them
     * @param label   how the person tells a task apart
     * @param request a task's request, as the form makes it
     * @param runner  the runner for one task, with its label
     */
    EachTaskOnItsOwn(List<Map<String, Object>> inputs, Function<Map<String, Object>, String> label,
                     Function<Map<String, Object>, String> request,
                     BiFunction<Map<String, Object>, String, ChatRuntime.Runner> runner) {
        this.inputs = List.copyOf(inputs);
        this.label = Objects.requireNonNull(label, "label");
        this.request = Objects.requireNonNull(request, "request");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    @Override
    public AgentResult run(Goal goal, List<ContentBlock> alsoSent) {
        if (inputs.size() > MAX_TASKS) {
            return AgentResult.completed("Nothing was done: a message may hold at most " + MAX_TASKS + " tasks, and "
                    + "this one holds " + inputs.size() + ". Send them in smaller groups.", 0, TokenUsage.ZERO);
        }
        StringBuilder answer = new StringBuilder();
        TokenUsage usage = TokenUsage.ZERO;
        int steps = 0;
        for (Map<String, Object> input : inputs) {
            String named = label.apply(input);
            AgentResult result;
            try {
                result = runner.apply(input, named).run(Goal.of(request.apply(input)), alsoSent);
            } catch (RuntimeException failed) {
                // One task failing to start is that task's outcome; the others are still carried out.
                result = AgentResult.failed(failed, "It could not be started: " + failed.getMessage(), 0,
                        TokenUsage.ZERO);
            }
            usage = usage.plus(result.usage());
            steps += result.steps();
            String said = result.output() == null || result.output().isBlank()
                    ? "It ended without an answer (" + result.stopReason().name().toLowerCase(java.util.Locale.ROOT)
                            .replace('_', ' ') + ")."
                    : result.output().strip();
            answer.append("### ").append(named).append("\n\n").append(said).append("\n\n");
        }
        return AgentResult.completed(answer.toString().strip(), steps, usage);
    }
}
