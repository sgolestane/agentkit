package dev.agentkit.eval;

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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** A minimal FIFO-scripted LlmClient for the eval-module tests (core's is test-scoped). */
final class ScriptedLlm implements LlmClient {

    private final Deque<LlmResponse> responses = new ArrayDeque<>();
    private final List<LlmRequest> received = new ArrayList<>();

    ScriptedLlm(LlmResponse... scripted) {
        for (LlmResponse r : scripted) {
            responses.add(r);
        }
    }

    @Override
    public synchronized LlmResponse generate(LlmRequest request) {
        received.add(request);
        if (responses.isEmpty()) {
            return text("(exhausted)");
        }
        return responses.poll();
    }

    synchronized List<LlmRequest> received() {
        return List.copyOf(received);
    }

    static LlmResponse text(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.END_TURN, TokenUsage.ZERO);
    }

    static LlmResponse toolUse(String id, String name, Map<String, Object> input) {
        // Through ProposedCall.of, which is what every provider adapter in this repository
        // does at its parse site (#277). A stand-in on the direct constructor throws where
        // every real one now carries a refusal, so it models a provider that does not
        // exist — see core's FakeLlmClient.toolUse for the whole argument.
        return LlmResponse.of(Message.of(Role.ASSISTANT, ProposedCall.of(id, name, input)),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO);
    }
}
