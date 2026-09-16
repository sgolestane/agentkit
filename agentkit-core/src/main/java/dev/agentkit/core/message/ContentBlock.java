package dev.agentkit.core.message;

/**
 * A single block of content within a {@link Message}.
 *
 * <p>Modelled as a sealed hierarchy so callers can exhaustively pattern-match
 * over the concrete block types. This mirrors the content-block model used by
 * modern tool-calling LLM APIs while remaining provider-agnostic.
 *
 * <p>{@link ImageBlock} is the one block whose absence was a correctness problem rather than a
 * missing feature: without it an uploaded screenshot reached the model as a filename, and the
 * agent answered about the filename while a person believed it was answering about the picture.
 * Adding it was a <em>deployment</em> change as well as a code one — see
 * {@code ContentBlockMixin}.
 *
 * <p>A tool call arrives as {@link ProposedCall}, itself sealed over the two shapes one can
 * take: a {@link ToolUseBlock} a runner may act on, and an {@link UnusableToolUseBlock} for
 * a call whose arguments this framework refused before anything could judge them (#246). An
 * exhaustive switch therefore has to say what it does with the second, which is the point —
 * defaulting a refused call into the branch that runs it is exactly the mistake a flag on
 * one type would have made easy.
 */
public sealed interface ContentBlock
        permits TextBlock, ImageBlock, ThinkingBlock, ProposedCall, ToolResultBlock {
}
