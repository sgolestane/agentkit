package dev.agentkit.temporal;

import dev.agentkit.core.message.Message;
import dev.agentkit.core.tool.ToolSpec;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The serializable input to the LLM activity: everything the worker needs to
 * rebuild an {@code LlmRequest} for one model turn.
 *
 * <p>The workflow owns the conversation (durable, replayed from history) and
 * passes it to the activity per turn, along with the run's model parameters and
 * advertised tool specs. Keeping this a flat record of JSON-friendly types lets
 * Temporal's default converter serialize it without custom handling beyond the
 * {@code ContentBlock} polymorphism configured in {@link DurableJson}.
 *
 * @param model        the model identifier
 * @param system       the system prompt, or {@code null} for none
 * @param conversation the running message history for this turn
 * @param tools        the advertised tool specs
 * @param maxTokens    the per-turn output token limit
 * @param options      provider-specific options
 */
public record LlmCallSpec(String model, String system, List<Message> conversation,
                          List<ToolSpec> tools, int maxTokens, Map<String, Object> options) {

    public LlmCallSpec {
        conversation = List.copyOf(conversation);
        tools = List.copyOf(tools);
        // Null-tolerant, and this is a workflow-code throw rather than an activity one (#132).
        //
        // #132 dismissed this site — "the rest of the copyOf hits are on framework-built
        // structures whose values cannot be null" — and the code says otherwise.
        // AgentConfig.Builder.option null-checks the KEY only, and AgentConfig's own
        // constructor copies with an unmodifiable LinkedHashMap precisely so a null value
        // survives. Measured:
        //
        //   AgentConfig accepted null option value: {stop=null}
        //   serialized:                             {"options":{"stop":null}}
        //   round-tripped from history:             {stop=null}
        //   LlmCallSpec THREW:                      java.lang.NullPointerException
        //
        // This record is built at AgentWorkflowImpl:206, inside the workflow method, on
        // every turn — so AgentConfig.builder("m").option("stop", null) gives a durable run
        // whose first turn fails the workflow task, which Temporal retries forever. It
        // needs no reviewer, no signal and no model, and it survives replay because the
        // null round-trips through history. A strictly worse instance than the approval one
        // this issue was opened for.
        //
        // The same idiom as AgentConfig rather than Frozen.deeply: these are an operator's
        // provider options, not a model's tool arguments, so the JSON-shape refusal would
        // reject a legitimate value that AgentConfig itself accepts.
        options = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(options, "options")));
    }
}
