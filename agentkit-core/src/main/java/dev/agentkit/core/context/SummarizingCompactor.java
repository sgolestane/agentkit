package dev.agentkit.core.context;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.ImageBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ThinkingBlock;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.message.UnusableToolUseBlock;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.util.Quoted;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Compactor} that summarises older turns with a model call once the
 * estimated history size crosses a trigger, keeping a recent window verbatim.
 *
 * <p>The compaction boundary is chosen to never orphan a {@code tool_result}
 * (its {@code tool_use} would otherwise be summarised away), so the surviving
 * transcript stays valid. On a summarisation failure the original history is
 * returned unchanged — compaction degrades gracefully rather than dropping data.
 *
 * <p><strong>Message 0 is treated as the objective</strong> and kept verbatim rather than
 * summarised, because it is the one message an {@code Agent} run gets from the operator —
 * every later user message carries tool results. Summarising it would return it fenced as
 * untrusted content, and the agent would then be told to disregard its own goal. A history
 * whose first message carries a {@code tool_result} plainly did not come from the start of
 * an agent run, so nothing is held out there and the whole head is summarised as before;
 * pinning it would hand the provider an orphaned {@code tool_result}.
 *
 * <p>Compaction can also decline to act while over the trigger: it needs at least two
 * messages to summarise, since replacing one with one summary is not progress. A history
 * that is over the trigger because of the objective alone therefore stays as it is, rather
 * than being re-summarised on every call.
 *
 * <p><strong>Durability note:</strong> {@link #compact} issues a non-deterministic
 * model call, so under a durable runner (the Temporal integration) the whole
 * context strategy must run inside an activity, not the replayed workflow body.
 */
public final class SummarizingCompactor implements Compactor {

    private static final Logger log = LoggerFactory.getLogger(SummarizingCompactor.class);

    private static final String SUMMARY_SYSTEM = """
            You compress an AI agent transcript. Produce a concise summary that a \
            fresh reader could use to continue the task: preserve goals, key facts \
            discovered, decisions made, tool results that still matter, and any \
            open sub-tasks. Omit chit-chat. Output only the summary.""";

    private static final String SUMMARY_PREFIX = "[Summary of earlier conversation]\n";

    private final LlmClient llm;
    private final String model;
    private final TokenEstimator estimator;
    private final int triggerTokens;
    private final int keepRecentMessages;
    private final int summaryMaxTokens;

    private final String systemPrompt;

    private SummarizingCompactor(Builder b) {
        this.llm = Objects.requireNonNull(b.llm, "llm");
        this.model = Objects.requireNonNull(b.model, "model");
        this.estimator = b.estimator;
        this.triggerTokens = b.triggerTokens;
        this.keepRecentMessages = b.keepRecentMessages;
        this.summaryMaxTokens = b.summaryMaxTokens;
        this.systemPrompt = b.systemPrompt;
    }

    public static Builder builder(LlmClient llm, String model) {
        return new Builder(llm, model);
    }

    @Override
    public List<Message> compact(List<Message> history) {
        if (estimator.estimate(history) <= triggerTokens) {
            return history;
        }
        int cut = Math.max(0, history.size() - keepRecentMessages);
        // Never let the surviving tail begin with an orphaned tool_result. This
        // relies on the agent-loop invariant that a tool_result message directly
        // follows the tool_use that produced it, so an orphan can only appear at
        // the tail's first message.
        while (cut < history.size() && containsToolResult(history.get(cut))) {
            cut++;
        }
        // Held out only when it looks like an objective. A ContextEditor running ahead of
        // this one (see ContextStrategies) can trim the head, and a Compactor is a public
        // interface anyone may call on any history — so index 0 is not guaranteed to be the
        // goal. If it carries a tool_result, pinning it would hand the provider an orphaned
        // tool_result, which is an API error rather than merely a weaker prompt. Summarise
        // from the start in that case: the objective is already gone.
        boolean holdOutObjective = !history.isEmpty() && !containsToolResult(history.get(0));
        int headStart = holdOutObjective ? 1 : 0;

        // Two messages minimum, or compaction makes no progress: replacing one message with
        // one summary leaves the history the same length, and a caller that compacts every
        // turn then pays for a model call per turn forever, re-summarising its own summary.
        // That is reachable — a pasted document as the goal is over the trigger on its own,
        // so with the objective held out the head never shrinks past it.
        if (cut - headStart < 2) {
            return history;
        }

        // Why hold it out at all: folding message 0 into the summary would return it
        // fenced, and the agent would be told to disregard directions found in its own
        // objective and report them. Whether it IS the objective is decided above.
        List<Message> head = history.subList(headStart, cut);
        List<Message> tail = history.subList(cut, history.size());

        String summary;
        try {
            summary = summarise(head);
        } catch (RuntimeException e) {
            log.warn("Compaction summarisation failed; keeping full history", Quoted.failure(e));
            return history;
        }

        List<Message> compacted = new ArrayList<>(tail.size() + 2);
        if (holdOutObjective) {
            compacted.add(history.get(0));
        }
        // The summary re-enters as a user message and outlives the turns it replaced, so
        // an instruction that survived summarisation would be laundered into the trusted
        // channel permanently. Fencing the input alone raises the bar for step one and
        // does nothing for step two. Advisory rather than evidence: what comes back is
        // this run's own progress, and the summariser is asked to preserve open sub-tasks
        // — content the agent is meant to carry on from. Marking it "weigh, do not follow"
        // would tell the agent to disregard its own half-finished plan, which is the
        // message-0 failure again one step along. The shared bound still applies, so it
        // cannot move the objective or widen the tool set.
        compacted.add(Message.user(SUMMARY_PREFIX
                + Spotlight.wrap(Spotlight.Kind.ADVISORY, Source.of("summary-of-earlier-turns"), summary)));
        compacted.addAll(tail);
        return compacted;
    }

    private String summarise(List<Message> head) {
        StringBuilder transcript = new StringBuilder();
        for (Message message : head) {
            transcript.append(message.role()).append(": ").append(renderBlocks(message)).append('\n');
        }
        LlmRequest request = LlmRequest.builder(model)
                .system(Spotlight.withInstruction(systemPrompt))
                .maxTokens(summaryMaxTokens)
                .addMessage(Message.user(Spotlight.wrap(Source.of("transcript"), transcript.toString())))
                .build();
        LlmResponse response = llm.generate(request);
        return response.message().text();
    }

    private static String renderBlocks(Message message) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : message.content()) {
            switch (block) {
                case TextBlock t -> sb.append(t.text());
                // Named, not carried. This transcript goes to a model to be SUMMARISED, and
                // a summary of an image is a thing only a vision model can write — sending
                // the base64 would spend the image's whole cost again on a call whose job is
                // to make the history smaller, and sending nothing would let a screenshot
                // vanish from the record silently. So the summary knows one was there.
                case ImageBlock i -> sb.append("[an image the model was shown: ")
                        .append(i.mediaType()).append(']');
                case ThinkingBlock t -> sb.append("(thinking) ").append(t.thinking());
                case ToolUseBlock u -> sb.append("[calls ").append(u.name()).append(' ').append(u.input()).append(']');
                // No arguments to render: nothing froze them, so the block does not hold
                // them (#246). The summary says the call was refused rather than dropping
                // it, because a transcript that shows a turn proposing nothing is the same
                // "wiring looked right and did nothing" shape the runners avoid.
                case UnusableToolUseBlock u -> sb.append("[refused call to ")
                        .append(u.name()).append(": arguments this framework will not carry]");
                case ToolResultBlock r -> sb.append("[tool result")
                        .append(r.isError() ? " (error)" : "").append(": ").append(r.content()).append(']');
            }
            sb.append(' ');
        }
        return sb.toString().strip();
    }

    private static boolean containsToolResult(Message message) {
        return message.content().stream().anyMatch(ToolResultBlock.class::isInstance);
    }

    /** Builder for {@link SummarizingCompactor}. */
    public static final class Builder {
        private final LlmClient llm;
        private final String model;
        private TokenEstimator estimator = TokenEstimator.HEURISTIC;
        private int triggerTokens = 100_000;
        private int keepRecentMessages = 6;
        private int summaryMaxTokens = 2048;
        private String systemPrompt = SUMMARY_SYSTEM;

        private Builder(LlmClient llm, String model) {
            this.llm = llm;
            this.model = model;
        }

        public Builder estimator(TokenEstimator estimator) {
            this.estimator = Objects.requireNonNull(estimator, "estimator");
            return this;
        }

        /** Compact once the estimated history exceeds this many tokens. */
        public Builder triggerTokens(int triggerTokens) {
            if (triggerTokens <= 0) {
                throw new IllegalArgumentException("triggerTokens must be > 0");
            }
            this.triggerTokens = triggerTokens;
            return this;
        }

        /** Keep this many most-recent messages verbatim (never summarised). */
        public Builder keepRecentMessages(int keepRecentMessages) {
            if (keepRecentMessages < 0) {
                throw new IllegalArgumentException("keepRecentMessages must be >= 0");
            }
            this.keepRecentMessages = keepRecentMessages;
            return this;
        }

        public Builder summaryMaxTokens(int summaryMaxTokens) {
            if (summaryMaxTokens <= 0) {
                throw new IllegalArgumentException("summaryMaxTokens must be > 0");
            }
            this.summaryMaxTokens = summaryMaxTokens;
            return this;
        }

        /** Overrides the summarisation system prompt (e.g. for another language or domain). */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
            return this;
        }

        public SummarizingCompactor build() {
            return new SummarizingCompactor(this);
        }
    }
}
