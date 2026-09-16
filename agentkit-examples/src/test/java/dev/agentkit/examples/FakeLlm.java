package dev.agentkit.examples;

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

/** A minimal thread-safe scripted {@link LlmClient} for the example integration tests. */
final class FakeLlm implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private final List<LlmRequest> received = new ArrayList<>();
    private int served;

    FakeLlm then(LlmResponse response) {
        script.add(response);
        return this;
    }

    /** Scripted responses not yet consumed — assert 0 to catch over-scripting. */
    synchronized int remaining() {
        return script.size();
    }

    /**
     * Every request the wiring actually sent, in order — including the ones a subagent's
     * own agent made, since one {@code FakeLlm} serves a whole wiring.
     *
     * <p>Held so a test can assert on what reached a model rather than only on what came
     * back. Two things need it: the critic's own prompt, which is a different party's
     * context from the agent's and is not otherwise reachable from outside
     * {@code SelfWiringAgent} (#309), and what a run <em>advertises</em> per turn, which is
     * the whole of #308.
     */
    synchronized List<LlmRequest> received() {
        return List.copyOf(received);
    }

    @Override
    public synchronized LlmResponse generate(LlmRequest request) {
        received.add(request);
        LlmResponse response = script.poll();
        if (response == null) {
            throw new LlmException("FakeLlm exhausted after serving " + served
                    + " responses; the script is too short for what the agent requested");
        }
        served++;
        return response;
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
