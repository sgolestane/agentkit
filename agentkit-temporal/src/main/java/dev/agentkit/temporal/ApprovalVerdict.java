package dev.agentkit.temporal;

import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.reliability.PendingApproval;
import dev.agentkit.core.util.Frozen;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A person's answer to one parked tool call, signalled into a running workflow.
 *
 * <p>The three outcomes are {@link ApprovalDecision}'s, unchanged — approve, deny with a
 * reason, or approve with edited arguments — because they are the right three and #101 said
 * so before this existed. What is added is what a signal needs and an in-process decision
 * does not: which call this answers, and who answered.
 *
 <h2>Two handles, each checked where it can be</h2>
 *
 * <p>{@code ticket} is what this verdict answers. The workflow mints it per park and
 * matches on it, and a verdict naming a ticket the run is not currently waiting on is
 * dropped. It is <em>not</em> the model's tool-call id, which was the first design and was
 * a laundering primitive — {@link PendingApproval} carries the measurement.
 *
 * <p>{@code invocationId} is the model's own id for the call, carried so the activity can
 * refuse a pair that does not go together. The workflow cannot produce a mismatched pair;
 * {@code ToolActivities} is a public interface and another workflow can.
 *
 * <p>Neither makes the verdict <strong>trustworthy</strong>. The authority to signal a
 * workflow is Temporal's, granted by whatever can reach the namespace, and this record
 * cannot add to it or check it. {@code decidedBy} is likewise a label for the audit trail:
 * it is whatever the signaller said. Put the real check in front of the signal.
 *
 * <h2>An approval cannot widen what policy allows</h2>
 *
 * <p>A verdict is applied only where a gate has <em>already</em> said a person must decide,
 * and the gate is re-evaluated when the decision comes back. A gate that denies outright
 * still denies; approving something policy forbids does not make it happen. Edited
 * arguments go through {@code GateResult.allowWith}, so the rename-and-renumber refusal
 * applies to a person exactly as it applies to a gate.
 *
 * @param ticket       the runner's handle for the park this answers; never {@code null}
 * @param invocationId the model's id for that call, checked by the activity; never
 *                     {@code null}
 * @param kind         approve, deny, or approve with edited arguments; never {@code null}
 * @param reason       why, for a denial — surfaced to the model as the tool's error, so it
 *                     can adapt. Never {@code null}; empty on an approval
 * @param arguments    the reviewer's edited arguments for
 *                     {@link ApprovalDecision.Kind#APPROVE_WITH_ARGUMENTS}; null otherwise.
 *                     A <em>full</em> replacement, matching
 *                     {@link ApprovalDecision#approveWithArguments}
 * @param decidedBy    a label for whoever decided, for the audit trail; never {@code null}
 */
public record ApprovalVerdict(String ticket, String invocationId, ApprovalDecision.Kind kind,
                              String reason, Map<String, Object> arguments, String decidedBy) {

    public ApprovalVerdict {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(kind, "kind");
        // Coerced, not required: this record arrives from a signal payload, and a signal is
        // written by whatever can reach the workflow — including an older or hand-rolled
        // client that omits a field. Refusing here would throw inside the signal handler,
        // which fails the workflow *task*, which Temporal retries forever. The run would
        // stall on a malformed decision rather than reject it.
        reason = reason == null ? "" : reason;
        decidedBy = decidedBy == null ? "" : decidedBy;
        // And the same reasoning applies one line further than it used to reach (#132).
        //
        // This line was Map.copyOf, which rejects a null VALUE with a NullPointerException.
        // A reviewer approving with an edited argument set containing a JSON null — say
        // clearing an optional field — therefore threw inside the signal handler, which is
        // precisely the stall the paragraph above exists to prevent. The record documented
        // the hazard and then walked into it three lines later.
        //
        // A copy that CANNOT THROW, which is the only kind this constructor may make.
        //
        // The first attempt at this fix used Frozen.deeply, on the argument that its
        // refusals "cannot fire on a signal payload". Measured, false: a signal carrying
        // 100,100 arguments is 1,290,407 bytes of JSON, inside Temporal's default blob
        // limit, and Frozen.deeply threw on it — putting back the identical forever-retry
        // stall at a payload size the transport allows. 99,000 arguments passed. A cliff at
        // a size the wire permits is not a defence.
        //
        // The second attempt added a Frozen.deeplyFromWire that dropped only the node
        // budget. That worked and was the wrong shape: it named a method after its caller's
        // provenance rather than its behaviour, so which of two Frozen methods is correct
        // became a rule stated in prose that a future caller could get wrong with a green
        // build — in a repository whose recurring defect is exactly one rule with several
        // spellings.
        //
        // This is the idiom AgentConfig and McpToolInfo already use for the same job, and
        // the deep freeze is not lost, only moved to where it can fail safely: asDecision
        // below hands these arguments to ApprovalDecision.approveWithArguments, which
        // freezes them with every check, and turns a refusal into a denial instead of a
        // throw. approveWithArguments (the static factory) keeps the checking copy, because
        // a caller reaching it is an author at a keyboard who can act on a stack trace.
        arguments = arguments == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /**
     * Run the parked call as it stands.
     *
     * <p>Built from the {@link PendingApproval} a console was shown, so the two handles
     * cannot be mismatched by hand — which is the only way this record's own consistency
     * can be got wrong.
     */
    public static ApprovalVerdict approve(PendingApproval parked, String decidedBy) {
        return new ApprovalVerdict(parked.ticket(), parked.invocationId(),
                ApprovalDecision.Kind.APPROVE, "", null, decidedBy);
    }

    /** Refuse the parked call; {@code reason} reaches the model as the tool's error. */
    public static ApprovalVerdict deny(PendingApproval parked, String decidedBy, String reason) {
        return new ApprovalVerdict(parked.ticket(), parked.invocationId(),
                ApprovalDecision.Kind.DENY, Objects.requireNonNull(reason, "reason"), null,
                decidedBy);
    }

    /** Run the parked call, but with the arguments the reviewer edited. */
    public static ApprovalVerdict approveWithArguments(PendingApproval parked, String decidedBy,
                                                       Map<String, Object> arguments) {
        // Checked here and coerced in the constructor, which is the split this repository
        // already uses for ApprovalNeeded.because versus its constructor. A caller reaching
        // this method is writing code; a caller reaching the constructor may be Jackson
        // reading a signal, and refusing there stalls the run rather than rejecting the
        // decision. Frozen.deeply's bounds are worth enforcing against the first and are a
        // trap for the second.
        //
        // One narrowing this introduces, which was not here when the copy was Map.copyOf:
        // Frozen turns every Collection into a List, so a reviewer passing a Set of paths
        // gets a List back out of arguments(). That matches what the tool would have seen
        // anyway — ToolInvocation's constructor does the same — so it removes a difference
        // rather than adding one, but a caller reading arguments() back off the verdict
        // will see the changed type and should not be surprised by it here.
        return new ApprovalVerdict(parked.ticket(), parked.invocationId(),
                ApprovalDecision.Kind.APPROVE_WITH_ARGUMENTS, "",
                Frozen.deeply(Objects.requireNonNull(arguments, "arguments")), decidedBy);
    }

    /**
     * This verdict as the core decision type.
     *
     * <p>Converted rather than carried so that one class decides what each outcome means.
     * {@link ApprovalDecision#toGateResult} is where approve, deny and edit become a gate
     * result on the in-process path; a second spelling of that mapping on the durable path
     * is how the two runners come to disagree about an authorization boundary, which is a
     * sentence this repository has written several times now.
     *
     * <p>Edited arguments that are absent fall back to approving unchanged rather than
     * throwing: the payload came off a signal, and the loud failure of a stalled run is
     * worse than the quiet one of an unedited approval — which is still an approval
     * somebody gave, for a call the gate is about to re-check anyway.
     */
    /**
     * The reviewer's edit, or a denial saying it could not be applied.
     *
     * <p>{@link ApprovalDecision#approveWithArguments} freezes with
     * {@link Frozen#deeply}, which refuses beyond 100,000 expanded values — and this
     * record's constructor does not, deliberately, because refusing in a signal handler
     * stalls the run. So there is a band where a verdict is accepted and its edit cannot be
     * applied, and the first version of this fix left that band silent.
     *
     * <p>Measured end to end before this method existed: a person approved a call with
     * 100,100 edited arguments, and the run reported
     * {@code stopReason=COMPLETED entered=0 error=[]}. The approval was recorded, the tool
     * never ran, and nothing anywhere said so. That is the sentence #132 was opened about —
     * "a control that silently does not engage on an input the framework says is legal" —
     * reproduced by the fix for it, one frame further down.
     *
     * <p>A denial is the right fallback rather than approving unchanged. The paragraph on
     * {@link #asDecision} already argues the absent-arguments case the other way, and the
     * difference is that absent edits mean the reviewer asked for none, while unapplicable
     * edits mean they asked for something this cannot honour — running the <em>proposed</em>
     * call then discards a narrowing somebody made deliberately, which is the one direction
     * with a bad outcome. A denial reaches the model as the tool's error and the activity
     * result in history, so it is a rejected decision rather than a stalled run, which is
     * what the constructor's own coercion is for.
     */
    private ApprovalDecision editedOrDenied() {
        try {
            return ApprovalDecision.approveWithArguments(arguments);
        } catch (IllegalArgumentException cannotBeApplied) {
            return ApprovalDecision.deny("This approval carried edited arguments that could"
                    + " not be applied (" + cannotBeApplied.getMessage() + "). Nothing ran."
                    + " Ask for a decision with smaller or simpler arguments.");
        }
    }

    ApprovalDecision asDecision() {
        return switch (kind) {
            case APPROVE -> ApprovalDecision.approve();
            case DENY -> ApprovalDecision.deny(reason.isBlank()
                    ? "This action was not approved." : reason);
            case APPROVE_WITH_ARGUMENTS -> arguments == null
                    ? ApprovalDecision.approve()
                    : editedOrDenied();
        };
    }
}
