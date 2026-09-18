package dev.agentkit.host;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** A model that says what the test told it to, and keeps what it was sent. */
final class ScriptedLlm implements LlmClient {

    private final Deque<LlmResponse> scripted = new ArrayDeque<>();
    private final List<LlmRequest> received = new ArrayList<>();

    ScriptedLlm(LlmResponse... responses) {
        scripted.addAll(List.of(responses));
    }

    @Override
    public synchronized LlmResponse generate(LlmRequest request) {
        received.add(request);
        if (scripted.isEmpty()) {
            throw new LlmException("ScriptedLlm exhausted after " + received.size() + " calls");
        }
        return scripted.poll();
    }

    synchronized List<LlmRequest> received() {
        return List.copyOf(received);
    }

    static LlmResponse text(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)), LlmStopReason.END_TURN, TokenUsage.ZERO);
    }

    static LlmResponse toolUse(String id, String name, Map<String, Object> input) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, ProposedCall.of(id, name, input)), LlmStopReason.TOOL_USE,
                TokenUsage.ZERO);
    }
}
