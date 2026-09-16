package dev.agentkit.workbench;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A model whose turns are written down: each {@code generate} hands back the next scripted
 * response, and the last text response repeats if the loop asks again. Tool-call ids are
 * minted per response so no turn reuses one.
 */
final class ScriptedLlm implements LlmClient {

    private final ConcurrentLinkedQueue<Step> steps = new ConcurrentLinkedQueue<>();
    private final AtomicInteger ids = new AtomicInteger();
    private volatile Step last = new Step(null, null, "Done.");

    private record Step(String tool, Map<String, Object> arguments, String text) {}

    ScriptedLlm proposes(String tool, Map<String, Object> arguments) {
        steps.add(new Step(tool, arguments, null));
        return this;
    }

    ScriptedLlm says(String text) {
        steps.add(new Step(null, null, text));
        return this;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        Step step = steps.poll();
        if (step == null) {
            step = last;
        } else if (step.text() != null) {
            last = step;
        }
        if (step.tool() != null) {
            return LlmResponse.of(Message.of(Role.ASSISTANT,
                            ProposedCall.of("call-" + ids.incrementAndGet(), step.tool(),
                                    step.arguments())),
                    LlmStopReason.TOOL_USE, TokenUsage.ZERO);
        }
        return LlmResponse.of(Message.assistant(step.text()), LlmStopReason.END_TURN,
                TokenUsage.ZERO);
    }

    static Map<String, Object> args(Object... pairs) {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    static List<String> none() {
        return List.of();
    }
}
