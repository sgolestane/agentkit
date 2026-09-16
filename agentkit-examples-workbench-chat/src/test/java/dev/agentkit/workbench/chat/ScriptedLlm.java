package dev.agentkit.workbench.chat;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.llm.TokenUsage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A model whose turns are written down.
 *
 * <p>The dashboard module has one of these in its test sources, where it cannot be reached
 * from here. Copied rather than published as a test-jar because this module's card says that
 * module is not modified, and attaching a test-jar to it is a modification — a forty-line
 * fixture is the cheaper of the two.
 */
final class ScriptedLlm implements LlmClient {

    private final ConcurrentLinkedQueue<Step> steps = new ConcurrentLinkedQueue<>();
    private final AtomicInteger ids = new AtomicInteger();
    private volatile Step last = new Step(null, null, "Done.");

    /**
     * What the last request actually carried as its system prompt.
     *
     * <p>Here because a prompt tested in isolation and a prompt the agent was built with are
     * two different claims, and only the second one matters. Both mutations that survived the
     * first pass over this module — building the agent with an empty prompt, and building it
     * for the wrong ticket system — left every other test green.
     */
    private volatile String lastSystem = "";

    private record Step(String tool, Map<String, Object> arguments, String text) {}

    ScriptedLlm proposes(String tool, Map<String, Object> arguments) {
        steps.add(new Step(tool, arguments, null));
        return this;
    }

    ScriptedLlm says(String text) {
        steps.add(new Step(null, null, text));
        return this;
    }

    /** The system prompt the agent last sent, or empty if it sent none. */
    String lastSystem() {
        return lastSystem;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        lastSystem = request.system().orElse("");
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
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
