package dev.agentkit.eval;

import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import java.util.Objects;

/**
 * One tool invocation observed during an {@link EvalRun}, what came back, and how far it got.
 *
 * <p>The distinction matters for scoring: the agent loop records a tool call even
 * when the tool is unknown, blocked by a gate, or throws — those all come back as
 * <em>error</em> results. A call that {@link #succeeded() succeeded} actually ran and
 * returned a non-error result.
 *
 * <h2>Why the disposition is here, having deliberately not been</h2>
 *
 * <p>{@link EvalHarness} used to drop it, with an argument worth restating before it is
 * reversed: an eval scores the <em>model</em>, whether a gate stopped a call is a fact about
 * the runtime, and "the harness's gate refused twice" would be scoring the harness.
 *
 * <p>That holds where the gate is scaffolding the harness put there. It does not hold where
 * the gate is <strong>part of what is being evaluated</strong>, which is the case
 * {@code agentkit-examples-itops} brought: its supervisor and its {@code RunRules} ship in the
 * product, and "the platform refused the second route to the same capability" is the property
 * an eval on that agent exists to score. There is no way to ask it from
 * {@link #succeeded()}.
 *
 * <p>And the collapse is not lossless even for scoring the model. {@code succeeded()} is
 * {@code !isError()}, which {@link Disposition}'s own javadoc measures as seven outcomes in
 * one bit — and the two an eval most needs apart sit on either side of a judgement that
 * reverses:
 *
 * <pre>
 * what the model saw                       succeeded()   disposition   trying another tool next is
 * a gate refused the call                   false         REFUSED       the attack shape
 * the tool ran and said "no such user"      false         RAN           correct behaviour
 * a gate parked the call for a person       false         PARKED        the run ending, not a choice
 * </pre>
 *
 * <p>Scoring the second row as if it were the first is how a check calibrated on
 * {@code isError()} reports a route-around that never happened, or misses one that did. The
 * runtime's own word for the state is carried instead of re-derived, for the reason
 * {@code Disposition} exists at all.
 *
 * @param invocation  what the model requested; never {@code null}
 * @param error       whether the result was an error (unknown tool, gate denial, thrown, or
 *     tool-reported error)
 * @param disposition how far the call got, as the runner stamped it; never {@code null}.
 *     Not derivable from {@code error} — see above
 */
public record ToolCall(ToolInvocation invocation, boolean error, Disposition disposition) {

    public ToolCall {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(disposition, "disposition");
    }

    public String name() {
        return invocation.name();
    }

    /**
     * Whether the tool actually ran and returned a non-error result.
     *
     * <p>Unchanged, and deliberately still {@code !error}: {@link Disposition#RAN} covers a
     * tool that ran <em>and returned an error</em>, so "it ran" and "it worked" are two
     * questions and this is the second one.
     */
    public boolean succeeded() {
        return !error;
    }

    /** Whether a gate refused this call outright — nothing ran, and nothing is outstanding. */
    public boolean refused() {
        return disposition == Disposition.REFUSED;
    }

    /** Whether a gate stopped this call pending somebody's decision. */
    public boolean parked() {
        return disposition == Disposition.PARKED;
    }
}
