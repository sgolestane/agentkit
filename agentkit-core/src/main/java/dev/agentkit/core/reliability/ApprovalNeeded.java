package dev.agentkit.core.reliability;

import java.util.Objects;

/**
 * What a {@link ToolGate} says when the policy has reached its limit and a person has to
 * decide: not "no", and not "yes", but "not without someone".
 *
 * <h2>Why this is not a denial</h2>
 *
 * <p>A gate could already stop a call, and a gate could already ask a person — by holding
 * an {@link Approver} that blocks until one answers. Both were available and neither says
 * this. A denial ends the question: the model is told no and adapts, and nobody is asked.
 * A blocking approver asks, but it asks <em>on the thread the run is using</em>, which is
 * why {@link ToolGate#waitsForAHuman()} exists and why the durable runner refuses such a
 * gate outright — an activity that waits burns its timeout, fails, and is retried, so the
 * person is paged again for each attempt and the call fails anyway.
 *
 * <p>This is the third thing. The gate decides and returns immediately; <em>the runner</em>
 * arranges the waiting, in whatever way it can. A workflow blocks on a signal, where
 * waiting is free. An in-process loop stops the run and hands the caller what is pending.
 * A sandboxed script refuses, because it has nobody to ask and no way to come back. One
 * policy, three honest answers — which is the shape this repository keeps arriving at,
 * and the shape it keeps getting wrong by writing the rule once per runner.
 *
 * <h2>What it carries, and why it is more than a sentence</h2>
 *
 * <p>{@code reason} alone would compile. The example in this repository that already
 * solved this problem says why it is not enough: <em>"An approval card that says only 'run
 * delete_user?' asks the reviewer to reconstruct the agent's reasoning from nothing, which
 * is how rubber-stamping starts."</em> So a gate may also state the {@code effect} — what
 * changes if this is approved — and whether it is {@code reversible}.
 *
 * <p>{@code reversible} defaults to {@code false}, and that reads as <em>not claimed
 * reversible</em> rather than <em>proved irreversible</em>. A gate that has not thought
 * about the question gives the answer that makes a reviewer look harder.
 *
 * <h2>These are the policy's words</h2>
 *
 * <p>{@code reason} reaches the model, as every gate's reason already does, and it reaches
 * a person. Both are the deployment's own voice, so neither is fenced. A gate that builds
 * either string out of a model argument is handing the model a pen in the framework's
 * voice — the same mistake {@link GateResult#effectiveFor} refuses a replacement for, and
 * the same one {@code TracingToolGate} caps a status length against. Say what the policy
 * decided, not what the model asked for.
 *
 * @param reason     why a person must decide; never {@code null} or blank
 * @param effect     what changes if this is approved, for the person deciding; never
 *                   {@code null}, and empty when the gate does not say
 * @param reversible whether the gate claims the action can be undone afterwards
 */
public record ApprovalNeeded(String reason, String effect, boolean reversible) {

    /** What a payload with no stated reason is shown as, rather than nothing at all. */
    static final String UNSTATED = "A gate stopped this call and did not say why.";

    public ApprovalNeeded {
        // Coerced, not refused, and this constructor is the reason why: it travels inside
        // PendingApproval inside ToolOutcome, which is a Temporal activity's return value —
        // re-read from history on every replay of a run in flight. A requireNonNull here
        // refuses a payload written before a component existed, which fails the workflow
        // task, which Temporal retries forever: the run stalls rather than fails, and that
        // is worse than either. ToolOutcome and DurableAgentOptions say the same thing;
        // this type was the one on that wire that did the opposite.
        //
        // Measured: a payload missing "effect" threw NullPointerException through
        // DurableJson's mapper, and a blank reason threw IllegalArgumentException.
        reason = reason == null || reason.isBlank() ? UNSTATED : reason;
        effect = effect == null ? "" : effect;
    }

    /**
     * A person must decide, for this reason, with nothing further said about the effect.
     *
     * <p>The effect is empty and the action is not claimed reversible — the answers that
     * make a reviewer ask rather than assume.
     *
     * <p>Refuses a blank reason, where the constructor coerces one. The difference is who
     * is calling: this is a gate author at their keyboard, and a blank reason is a mistake
     * worth failing on before it reaches somebody's screen. The constructor is also
     * Jackson, deserializing history, where failing stalls a live run.
     *
     * @throws IllegalArgumentException if {@code reason} is blank
     */
    public static ApprovalNeeded because(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("an approval needs a stated reason");
        }
        return new ApprovalNeeded(reason, "", false);
    }

    /** The same reason, now also stating what approving it will change. */
    public ApprovalNeeded withEffect(String effect) {
        return new ApprovalNeeded(reason, effect, reversible);
    }

    /** The same reason, with the gate claiming the action can be undone afterwards. */
    public ApprovalNeeded thatCanBeUndone() {
        return new ApprovalNeeded(reason, effect, true);
    }
}
