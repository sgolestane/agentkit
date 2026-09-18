package dev.agentkit.acme;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** A model that says what it is told, in order, and keeps what it was sent. */
public final class ScriptedModel implements LlmClient {

    public final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private final Deque<LlmResponse> script;

    public ScriptedModel(LlmResponse... responses) {
        this.script = new ArrayDeque<>(List.of(responses));
    }

    @Override
    public synchronized LlmResponse generate(LlmRequest request) {
        requests.add(request);
        if (script.isEmpty()) {
            throw new IllegalStateException("The script ran out after " + requests.size() + " calls");
        }
        return script.poll();
    }

    public static LlmResponse text(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)), LlmStopReason.END_TURN, TokenUsage.ZERO);
    }

    public static LlmResponse toolUse(String tool, Map<String, Object> input) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, ProposedCall.of("c-" + tool, tool, input)),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO);
    }
}
