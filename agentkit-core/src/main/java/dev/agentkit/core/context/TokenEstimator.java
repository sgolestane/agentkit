package dev.agentkit.core.context;

import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.ImageBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ThinkingBlock;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.message.UnusableToolUseBlock;
import java.util.List;

/**
 * Estimates the token footprint of text and messages, so context strategies can
 * decide when to compact or edit before the real context window fills.
 *
 * <p>The core ships a fast, provider-agnostic heuristic. For exact accounting an
 * adapter can implement this over a provider's token-counting endpoint; strategies
 * depend only on this interface.
 */
public interface TokenEstimator {

    /** A cheap heuristic estimator (~4 characters per token). */
    TokenEstimator HEURISTIC = new HeuristicTokenEstimator();

    /** Estimates the token count of a text string. */
    int estimate(String text);

    /** Estimates the token count of a message, including per-block overhead. */
    default int estimate(Message message) {
        int total = 4; // role + framing overhead
        for (ContentBlock block : message.content()) {
            total += estimateBlock(block);
        }
        return total;
    }

    /** Estimates the token count of a whole message list. */
    default int estimate(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += estimate(message);
        }
        return total;
    }

    private int estimateBlock(ContentBlock block) {
        return switch (block) {
            case TextBlock t -> estimate(t.text());
            // An image, coarsely and DELIBERATELY HIGH.
            //
            // Anthropic's own rule of thumb is width × height / 750, and this has neither
            // dimension — decoding the image to find out would make a context estimate do
            // image processing, which is a cost nobody asked for on every compaction check.
            // So it is estimated from what is actually held: the encoded size.
            //
            // The divisor is the interesting choice. A 1500×1000 screenshot is about two
            // million pixels — call it 2,700 tokens by the rule above — and lands somewhere
            // near 400 kB of base64. That is roughly 150 bytes per token, so a divisor of 100
            // over-estimates by about half.
            //
            // Over-estimating is the safe direction and the reason is asymmetric: this number
            // decides when to compact. Too high and a strategy compacts sooner than it needed
            // to, which costs a summarisation. Too low and the real request overruns the
            // context window, which costs the run. A heuristic that guesses a screenshot is
            // free is how a conversation with three of them dies at the provider.
            case ImageBlock i -> i.wireBytes() / 100 + 8;
            case ThinkingBlock t -> estimate(t.thinking());
            case ToolResultBlock r -> estimate(r.content()) + 4;
            case ToolUseBlock u -> estimate(u.name()) + estimate(String.valueOf(u.input())) + 6;
            // The wire echo of a refused call is a tool_use with empty arguments (#246), so
            // it costs a name and the block's own overhead and nothing else. The refusal
            // text is not counted here: it travels as the matching ToolResultBlock, which
            // this method already estimates on its own line above.
            case UnusableToolUseBlock u -> estimate(u.name()) + 6;
        };
    }
}
