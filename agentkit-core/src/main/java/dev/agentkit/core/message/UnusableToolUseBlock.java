package dev.agentkit.core.message;

import java.util.Objects;

/**
 * A tool call the model made and this framework will not carry the arguments of, standing in
 * the assistant turn where the call would have been (#246).
 *
 * <p>It exists so the turn can be built at all. {@link ToolUseBlock}'s constructor refuses
 * arguments past {@link dev.agentkit.core.util.Frozen#MAX_DEPTH}, past the node budget, or
 * holding a value no JSON document could hold; before this type, that refusal escaped the
 * parse as an exception and took the whole turn with it — and with the turn went the
 * assistant message a tool result has to be correlated against, so the model was told
 * nothing. See {@link ProposedCall} for the measurement and for why admitting the arguments
 * instead would have been fail-open.
 *
 * <p><strong>It carries no arguments, and that is the load-bearing part.</strong> There is
 * no {@code input()} on this type: nothing froze the model's tree, so there is nothing this
 * block could hand out that would be both safe to hold and true to what was sent. A runner
 * cannot gate it, cannot run it, and cannot accidentally treat it as a call — its only move
 * is {@link #refusal()} back to the model as an error result.
 *
 * <p>What a provider adapter sends back on the wire for one of these is a tool-use block
 * with empty arguments, because the wire format has no other spelling and a
 * {@code tool_result} with no matching {@code tool_use} is rejected outright. That echo is
 * not a call anybody may act on — by the time it is written the call has already been
 * refused and answered — which is why it is safe here and would not have been safe as a
 * {@code ToolUseBlock} in the framework's own hands.
 *
 * @param id      the tool-call id, echoed back on the matching {@link ToolResultBlock};
 *                never {@code null}
 * @param name    the tool the refused call named; never {@code null}
 * @param refusal the sentence the model is handed instead of a result, from
 *                {@link ToolUseBlock.UnusableArguments#refusal()}; never {@code null}
 */
public record UnusableToolUseBlock(String id, String name, String refusal)
        implements ProposedCall {

    public UnusableToolUseBlock {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(refusal, "refusal");
    }

    /**
     * The block standing in for the call {@code refused} was raised about.
     *
     * <p>The wording is {@link ToolUseBlock.UnusableArguments#refusal()}'s and is not
     * rewritten here — that method exists so four runners do not invent four sentences, and
     * a second spelling on the way through this factory would be the fifth.
     */
    public static UnusableToolUseBlock of(ToolUseBlock.UnusableArguments refused) {
        Objects.requireNonNull(refused, "refused");
        return new UnusableToolUseBlock(refused.id(), refused.name(), refused.refusal());
    }
}
