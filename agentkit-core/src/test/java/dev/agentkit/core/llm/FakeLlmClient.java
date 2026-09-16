package dev.agentkit.core.llm;

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
 * A scripted {@link LlmClient} for tests: returns pre-built responses in order
 * and records every request it receives.
 *
 * <p>Thread-safe: {@code generate} is synchronized so a single client may safely
 * back a subagent that runs on multiple threads (e.g. the same subagent delegated
 * twice in one parallel fan-out). Responses are still consumed in FIFO order.
 */
public final class FakeLlmClient implements LlmClient {

    private final Deque<LlmResponse> scripted = new ArrayDeque<>();
    private final List<LlmRequest> received = new ArrayList<>();

    public FakeLlmClient(LlmResponse... responses) {
        for (LlmResponse r : responses) {
            scripted.add(r);
        }
    }

    @Override
    public synchronized LlmResponse generate(LlmRequest request) {
        received.add(request);
        if (scripted.isEmpty()) {
            throw new LlmException("FakeLlmClient exhausted after " + received.size() + " calls");
        }
        return scripted.poll();
    }

    public synchronized List<LlmRequest> received() {
        return List.copyOf(received);
    }

    // --- response factories -------------------------------------------------

    public static LlmResponse text(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.END_TURN, TokenUsage.ZERO);
    }

    /**
     * An assistant turn proposing one tool call, built the way a provider adapter builds
     * one — through {@link ProposedCall#of} (#277).
     *
     * <p><strong>What the direct constructor made this fake model, and why it matters.</strong>
     * Every shipped adapter turns arguments this framework will not carry into an
     * {@code UnusableToolUseBlock} the model receives as a refusal; {@code new
     * ToolUseBlock(...)} <em>throws</em> instead. So a stand-in on the constructor models a
     * provider that does not exist, and the tests it backs assert against behaviour no
     * deployment can produce.
     *
     * <p>That is not hypothetical here. #269's hostile pass found exactly this in the
     * pentest suite's {@code PersuadedModel}: its {@code UnusualArgumentTest} stayed green
     * against a fake that had stopped modelling anything, and nothing failed to say so.
     * #277 is the same drift in the rest of the suite. The conversion is mechanical and
     * changes nothing for well-formed arguments — {@link ProposedCall#of} hands back a
     * {@code ToolUseBlock} for those, which is the whole point of it having one door.
     */
    public static LlmResponse toolUse(String id, String name, Map<String, Object> input) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, ProposedCall.of(id, name, input)),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO);
    }

    public static LlmResponse refusal(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.REFUSAL, TokenUsage.ZERO);
    }

    public static LlmResponse maxTokens(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.MAX_TOKENS, TokenUsage.ZERO);
    }

    public static LlmResponse pause(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.PAUSE, TokenUsage.ZERO);
    }

    public static LlmResponse textWithUsage(String text, TokenUsage usage) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.END_TURN, usage);
    }

    /** {@link #toolUse} with the usage a metering test needs to see; the same door. */
    public static LlmResponse toolUseWithUsage(String id, String name, Map<String, Object> input,
                                               TokenUsage usage) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, ProposedCall.of(id, name, input)),
                LlmStopReason.TOOL_USE, usage);
    }
}
