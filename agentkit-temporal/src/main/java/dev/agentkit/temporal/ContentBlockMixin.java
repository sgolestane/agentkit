package dev.agentkit.temporal;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.agentkit.core.message.ImageBlock;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ThinkingBlock;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.message.UnusableToolUseBlock;

/**
 * Jackson mix-in that teaches the durable data converter how to (de)serialize the
 * sealed {@code ContentBlock} hierarchy polymorphically, via a {@code "@type"}
 * discriminator. Applied in {@link DurableJson} so the core module stays free of
 * any Jackson or Temporal dependency.
 *
 * <p><strong>A new {@code @type} is a rolling-deploy hazard, stated rather than hidden
 * (#246).</strong> {@code tool_use_unusable} is written by a worker running this code and
 * read back by the <em>workflow</em>, which during a rolling deploy may still be an older
 * worker that has never heard of it — and an unknown discriminator fails the workflow task,
 * which Temporal retries indefinitely, stalling the run. That is the failure mode
 * {@link DurableJson} disables {@code FAIL_ON_UNKNOWN_PROPERTIES} to avoid for an added
 * <em>field</em>, and it is exactly why the refused call is a separate type rather than a
 * fourth component on {@code tool_use}: an old worker silently dropping such a field would
 * be left holding a call with empty arguments that it would gate and run. A stall is bad; a
 * tool running on arguments the model never sent, at an authorization boundary, is worse.
 *
 * <p><strong>The mitigating sentence here was too generous, and it is corrected rather than
 * deleted (#284).</strong> It read: "The block also only appears at all for a turn whose
 * arguments were already going to end the run before this change." True about when the
 * block is written, and it does not carry the weight it was doing, for two reasons.
 *
 * <p>First, the two outcomes are not the same operational fact. A run that ends with a
 * reported error reaches {@code AgentRunResult} and whatever an operator watches for a
 * failed run. A run wedged in an infinite workflow-task retry reaches <em>nothing</em> on
 * any AgentKit channel — it is the failure this codebase is most careful about everywhere
 * else, and somebody has to go and find it in Temporal. How long it lasts is not
 * predictable either: a retry recovers as soon as it lands on a new worker, so a mixed
 * fleet makes every workflow task for that run a coin toss until the last old worker goes.
 *
 * <p>Second, the comparison is against behaviour this change removed. Since #246 such a
 * turn does <em>not</em> end the run: the refusal travels as an ordinary error result, the
 * model reacts, and the run carries on. So during a mixed fleet the stall replaces a run
 * that would now survive, not one that was going to die. And the moment is the model's to
 * choose, so a run reading attacker-written content is a run somebody else can time.
 *
 * <p>None of that argues for a fourth component on {@code tool_use} — the trade above still
 * holds. What it argues is that this is a deployment instruction and not only a design
 * note, so the rule now lives where an operator doing a rolling deploy will meet it: the
 * README's durable-execution section, and {@link DurableJson}, which is the public class
 * that used to document only the tolerant half.
 *
 * <p>And the rule is the general one rather than one about this discriminator. Tolerating
 * unknown <em>fields</em> does nothing for an unknown <em>subtype id</em>, which Jackson
 * resolves first — so every future {@code @type} added below carries the same hazard, and
 * {@code DurableJsonTest} pins it with a discriminator no release has ever used.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
        // #374, and the second discriminator this file has added. The rule two paragraphs up
        // applies unchanged and was applied deliberately: an older worker reading `image`
        // fails its workflow task and retries forever, so shipping it is every-worker-first
        // or a drained fleet. Not additive, and the README's durable-execution section says
        // so where an operator doing a rolling deploy will meet it.
        @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
        @JsonSubTypes.Type(value = ThinkingBlock.class, name = "thinking"),
        @JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool_use"),
        @JsonSubTypes.Type(value = UnusableToolUseBlock.class, name = "tool_use_unusable"),
        @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result"),
})
interface ContentBlockMixin {
}
