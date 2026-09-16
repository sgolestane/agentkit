package dev.agentkit.core.reliability;

import dev.agentkit.core.tool.ToolInvocation;
import java.util.Map;

/**
 * One tool call stopped at the door, and the gate's stated reason for stopping it — what a
 * runner hands upward so somebody can decide.
 *
 * <h2>Why the gate does not build this</h2>
 *
 * <p>{@link ApprovalNeeded} says <em>why</em>; this says <em>which call</em>, and the two
 * are assembled by different parties on purpose. The gate returns only the reason. The
 * runner pairs it with the invocation it is already holding — the one it resolved a tool
 * for and evaluated the gate against.
 *
 * <p>A gate that named the call itself could name a different one, and this repository has
 * already paid for that shape once: a replacement invocation was allowed to carry any name,
 * so a gate meaning to downgrade a public {@code publish} to a private {@code draft} let
 * the publish run with the draft's arguments, silently and permissively. {@code
 * GateResult.effectiveFor} now refuses a rename outright. Making the same mistake
 * unrepresentable here is cheaper than refusing it: a gate is never handed the pen.
 *
 * <h2>The arguments are the model's words, on a person's screen</h2>
 *
 * <p>{@code invocation.arguments()} is written by the model, and a reviewer has to see it
 * faithfully — that is the whole point of showing them the call. So it is <strong>not</strong>
 * fenced and <strong>not</strong> normalised: neutralising the very text somebody is being
 * asked to approve would show them one thing and run another, which is worse than any
 * injection this could carry.
 *
 * <p>That makes the escaping somebody else's job, and it is worth being explicit about
 * whose. A console rendering this must escape for its own medium; an operator's HTML
 * approval card that interpolates an argument raw has a scripting bug, not a prompt-
 * injection one. And anything that summarises a queue of these <em>with a model</em> is
 * feeding a model text a model wrote — fence it there, with {@code Spotlight}, where the
 * consumer is known. The framework cannot do either from here without breaking the first
 * rule.
 *
 * <h2>The ticket, and why the call's own id is not one</h2>
 *
 * <p>{@code ticket} is what a decision names, and the runner mints it. It is deliberately
 * <strong>not</strong> {@code invocation.id()}, which the <em>model</em> chooses.
 *
 * <p>Keying decisions on the model's id was the first design and it was a laundering
 * primitive. Measured: a run proposes {@code publish{text:"benign"}} as call {@code t1}, a
 * person approves {@code t1}, it runs. The model then proposes
 * {@code publish{text:"NOBODY-APPROVED-THIS"}} — also as {@code t1}. The gate parks it, the
 * stored decision still answers to {@code t1}, and the call runs without ever appearing on
 * anybody's screen. The run reports {@code COMPLETED} and the worker logs an approval by a
 * person who never saw it. Nothing forbids reusing an id across turns:
 * {@code ToolUseBlock.refusalForRepeatedIds} checks within one turn, and a decision
 * outlives a turn.
 *
 * <p>So the handle a person's answer names has to be one the model cannot influence. A
 * runner mints it per park, in its own sequence, and a decision naming a ticket that is not
 * currently outstanding is not a decision about anything.
 *
 * @param invocation the call the gate stopped, exactly as it would have run — including any
 *                   edit an earlier gate made before the park; never {@code null}
 * @param why        the gate's reason and, if it said so, the effect; never {@code null}
 * @param ticket     the runner's own handle for this park, which a decision must name;
 *                   never {@code null}, and never derived from anything the model wrote
 */
public record PendingApproval(ToolInvocation invocation, ApprovalNeeded why, String ticket) {

    public PendingApproval {
        // Coerced for the reason ApprovalNeeded's constructor gives at length: this record
        // rides inside a Temporal activity's return value, which a workflow re-reads from
        // history on every replay, and a constructor that refuses a partial payload stalls
        // a live run rather than failing it.
        //
        // An absent invocation becomes one naming no tool. That is a strange thing to hand
        // a reviewer and a better thing than a stalled run: a console renders a call it
        // cannot act on, and somebody investigates. Measured before this: a payload missing
        // "invocation" threw NullPointerException through DurableJson's mapper.
        invocation = invocation == null ? new ToolInvocation("", "", Map.of()) : invocation;
        why = why == null ? ApprovalNeeded.because("A gate stopped this call.") : why;
        ticket = ticket == null ? "" : ticket;
    }

    /** A park with no ticket, for a runner that accepts no decisions and so needs none. */
    public PendingApproval(ToolInvocation invocation, ApprovalNeeded why) {
        this(invocation, why, "");
    }

    /** The tool the parked call would run. */
    public String toolName() {
        return invocation.name();
    }

    /**
     * The id the model gave this call.
     *
     * <p>Useful for correlating with a transcript, and <strong>not</strong> the handle a
     * decision names — see {@link #ticket()} and the measurement above for what happened
     * when it was. {@code GateResult.effectiveFor}'s javadoc says the rest: nothing
     * requires a model's tool-call ids to be unique, so this cannot identify a call on its
     * own.
     */
    public String invocationId() {
        return invocation.id();
    }
}
