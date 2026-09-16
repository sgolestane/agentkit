package dev.agentkit.chat;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.StreamHandler;
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

/**
 * A model that says what the test told it to, and that really streams.
 *
 * <p>The core has a {@code FakeLlmClient} and it is in that module's <em>test</em> sources, so
 * it is not on this module's classpath. Rewritten here rather than published as a test-jar,
 * which is the same call {@code agentkit-examples-workbench} made with its own {@code ScriptedLlm}
 * — a shared test double is a shared thing to keep in step, and these are twenty lines.
 *
 * <p>The streaming override is the part that matters. The default
 * {@link LlmClient#generate(LlmRequest, StreamHandler)} emits the whole answer as one delta,
 * so a test written against it would pass on a console that does not stream at all. This
 * emits one fragment per word, which is what an adapter's server-sent-event stream looks like
 * from here.
 */
final class ScriptedLlm implements LlmClient {

    private final Deque<LlmResponse> scripted = new ArrayDeque<>();
    private final List<LlmRequest> received = new ArrayList<>();

    ScriptedLlm(LlmResponse... responses) {
        for (LlmResponse response : responses) {
            scripted.add(response);
        }
    }

    @Override
    public synchronized LlmResponse generate(LlmRequest request) {
        received.add(request);
        if (scripted.isEmpty()) {
            throw new LlmException("ScriptedLlm exhausted after " + received.size() + " calls");
        }
        return scripted.poll();
    }

    @Override
    public LlmResponse generate(LlmRequest request, StreamHandler handler) {
        LlmResponse response = generate(request);
        String text = response.message().text();
        if (!text.isEmpty()) {
            for (String fragment : text.split("(?<= )")) {
                handler.onTextDelta(fragment);
            }
        }
        return response;
    }

    synchronized List<LlmRequest> received() {
        return List.copyOf(received);
    }

    static LlmResponse text(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.END_TURN, TokenUsage.ZERO);
    }

    /** Through {@link ProposedCall#of}, the door every shipped adapter uses (#277). */
    static LlmResponse toolUse(String id, String name, Map<String, Object> input) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, ProposedCall.of(id, name, input)),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO);
    }
}
