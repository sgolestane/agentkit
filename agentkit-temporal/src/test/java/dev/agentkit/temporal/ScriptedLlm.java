package dev.agentkit.temporal;

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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A thread-safe scripted {@link LlmClient} for the durable tests. Entries are
 * consumed in order across activity invocations (which run on worker threads); a
 * {@link #fail()} entry throws once to exercise Temporal's activity retry. The
 * total number of {@code generate} calls is recorded so a test can prove that a
 * completed activity is not re-executed when a later one is retried.
 */
final class ScriptedLlm implements LlmClient {

    private sealed interface Entry permits Reply, Fail {
    }

    private record Reply(LlmResponse response) implements Entry {
    }

    private record Fail() implements Entry {
    }

    private final Deque<Entry> script = new ArrayDeque<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<LlmRequest> requests = new ArrayList<>();

    ScriptedLlm then(LlmResponse response) {
        script.add(new Reply(response));
        return this;
    }

    /** Scripts a single transient failure at this point in the sequence. */
    ScriptedLlm fail() {
        script.add(new Fail());
        return this;
    }

    int callCount() {
        return calls.get();
    }

    /** The requests received so far, in order (thread-safe snapshot). */
    synchronized List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public synchronized LlmResponse generate(LlmRequest request) {
        calls.incrementAndGet();
        requests.add(request);
        Entry entry = script.poll();
        if (entry == null) {
            throw new LlmException("ScriptedLlm exhausted after " + calls.get() + " calls");
        }
        if (entry instanceof Fail) {
            throw new LlmException("scripted transient failure");
        }
        return ((Reply) entry).response();
    }

    // --- response factories -------------------------------------------------

    static LlmResponse text(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.END_TURN, TokenUsage.ZERO);
    }

    static LlmResponse textWithUsage(String text, TokenUsage usage) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.END_TURN, usage);
    }

    /**
     * An assistant turn proposing one tool call, built the way a provider adapter builds
     * one — through {@link ProposedCall#of} (#277).
     *
     * <h4>There used to be two of these, and the argument for the second was wrong</h4>
     *
     * <p>{@code parsedToolUse} was the door and this method was the direct constructor,
     * with a javadoc that said so: <em>"{@code toolUse} keeps the direct constructor on
     * purpose: it is how the rest of these tests say 'a call whose arguments are fine', and
     * going through the door there would hide a change that made the door refuse
     * everything."</em>
     *
     * <p><strong>Corrected rather than deleted, because the risk it names is real and is
     * covered elsewhere.</strong> A {@link ProposedCall#of} that refused everything would
     * not be hidden by this method: every test reached through it asserts that the tool
     * <em>ran</em> — {@code runsMultipleToolUsesInOneTurn} pins two executions — so a door
     * that refused a well-formed call would fail them, not pass them quietly. And
     * {@code ProposedCallTest} pins the answer directly, on both sides.
     *
     * <p>What the old shape did cost is what #277 is about, and it is not hypothetical:
     * every shipped adapter turns arguments this framework will not carry into an
     * {@code UnusableToolUseBlock} the model receives as a refusal, and a stand-in on the
     * direct constructor <em>throws</em> there instead. So the durable suite's model
     * modelled a provider that does not exist, on exactly the input the framework's newest
     * refusal path was built for. The asymmetry decides it: the risk the old comment feared
     * fails loudly and is pinned twice over, and the risk it created fails silently and is
     * pinned nowhere.
     */
    static LlmResponse toolUse(String id, String name, Map<String, Object> input) {
        return toolUseWithUsage(id, name, input, TokenUsage.ZERO);
    }

    /** {@link #toolUse} with the usage a metering test needs to see. */
    static LlmResponse toolUseWithUsage(String id, String name, Map<String, Object> input,
                                        TokenUsage usage) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, ProposedCall.of(id, name, input)),
                LlmStopReason.TOOL_USE, usage);
    }

    /**
     * An assistant turn carrying several proposed calls, runnable or refused (#246).
     *
     * <p>The sibling {@code multiToolUse(List<ToolUseBlock>)} is gone with
     * {@code parsedToolUse} (#277) and for the same reason: a turn of several calls is
     * built by the same parse as a turn of one, so a stand-in that had one door for each
     * was modelling two providers.
     */
    static LlmResponse multiProposedCall(List<? extends ProposedCall> blocks) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, List.copyOf(blocks)),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO);
    }

    static LlmResponse refusal(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.REFUSAL, TokenUsage.ZERO);
    }

    static LlmResponse maxTokens(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.MAX_TOKENS, TokenUsage.ZERO);
    }

    static LlmResponse pause(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)),
                LlmStopReason.PAUSE, TokenUsage.ZERO);
    }
}
