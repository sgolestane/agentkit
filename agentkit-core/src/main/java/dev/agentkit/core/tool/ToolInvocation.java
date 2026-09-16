package dev.agentkit.core.tool;

import dev.agentkit.core.util.Frozen;
import java.util.Map;
import java.util.Objects;

/**
 * A concrete request to execute a tool, as issued by the model.
 *
 * <h2>One constructor, and the bound stays in it (#172)</h2>
 *
 * <p>{@link Frozen#deeply} refuses beyond 100,000 expanded values or 100 levels of nesting,
 * and this record is <strong>also</strong> read back off Temporal history — it rides on
 * {@code ToolOutcome.effective} and inside {@code PendingApproval}, and an activity result is
 * deserialized on the <em>workflow</em> thread, where a throw fails the workflow task, which
 * Temporal retries forever. That is a stall rather than a rejection, and on replay it is a
 * stall nothing drains: the payload is already in history. {@code Frozen.MAX_NODES}' javadoc
 * calls it the worst failure mode this codebase has, and it is right.
 *
 * <p>#172 asks whether this record should therefore distinguish "built in process" from
 * "read back off a wire". Measured, and the answer taken is <strong>no</strong>. The bound
 * guards the writing side too: every invocation that reaches history was constructed in
 * process first — by a gate's {@code effectiveFor}, by the park a gate raised, or by a runner
 * out of an already-frozen {@code ToolUseBlock} — so the reader can only be handed what the
 * writer accepted, provided the round trip preserves the quantity both bounds are taken of.
 * It does: measured over every shape {@code deeply} permits, including the {@code Set} and
 * {@code Collection} it rewrites, node count and nesting depth come back identical. At the
 * cliff, 99,999 arguments is 1,477,952 bytes of JSON, is accepted by the writer and reads
 * back in about 200&nbsp;ms; 100,100 — #132's own figure — is refused by the writer before
 * anything is recorded. {@code DurableToolInvocationBoundsTest} is that measurement, and it
 * is the test that fails if a later change moves one side's bound without the other.
 *
 * <p>A split was rejected for what it would cost rather than for what it would buy. #169
 * tried the shape it takes — a second {@code Frozen} method named for its caller's
 * provenance — and reverted it, because which of two copies is correct then lives only in
 * prose, where a green build cannot catch a caller getting it wrong. #170 proposing that
 * several more records hold a {@code ToolInvocation} argues the same way round: one
 * constructor with one contract, more load-bearing, not two with a rule between them. The
 * two in-process doors that could inject an oversized map — {@code
 * ApprovalDecision.approveWithArguments} and {@code ApprovalVerdict.approveWithArguments} —
 * refuse it before it can enter history, and since #169 the durable one turns that refusal
 * into a denial rather than a throw.
 *
 * <p>Two things narrowed the hazard rather than closing it, and neither is this record's
 * doing. #133 moved {@code Goal} and {@code AgentConfig} — the other parsed payloads a
 * workflow thread reconstructs — onto {@link Frozen#containersOf}, which takes no node
 * budget. #134 made {@code deeply} hand back a map it already produced, so building this
 * record out of a {@code ToolUseBlock} no longer walks the arguments a second time; on the
 * durable path that second walk was paid on every replay of every step, and the bound here
 * is not evaluated on that path at all any more.
 *
 * @param id        the invocation id, echoed back on the result so the model can
 *                  correlate; never {@code null}
 * @param name      the name of the tool to run; never {@code null}
 * @param arguments the parsed arguments; never {@code null}. Stored as a defensive,
 *                  order-preserving, unmodifiable copy — <em>deep</em> through the JSON
 *                  shapes, so a nested map or list is a snapshot rather than a shared
 *                  reference (null values permitted). A value that is not a JSON shape is
 *                  refused, and every nested {@link java.util.Collection} is stored as a
 *                  {@link java.util.List}. See {@link Frozen} for why a snapshot matters at
 *                  a gate boundary, and for what it does not promise.
 */
public record ToolInvocation(String id, String name, Map<String, Object> arguments) {

    public ToolInvocation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        arguments = Frozen.deeply(arguments);
    }

    /** Returns the argument for {@code key}, or {@code null} if absent. */
    public Object argument(String key) {
        return arguments.get(key);
    }

    /** Returns the argument for {@code key} as a string, or {@code null} if absent. */
    public String stringArgument(String key) {
        Object value = arguments.get(key);
        return value == null ? null : value.toString();
    }
}
