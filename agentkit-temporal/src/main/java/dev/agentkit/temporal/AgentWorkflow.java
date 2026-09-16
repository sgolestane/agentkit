package dev.agentkit.temporal;

import dev.agentkit.core.reliability.PendingApproval;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.List;

/**
 * The AgentKit agent loop as a Temporal workflow. The same goal-in / result-out
 * contract as the in-process {@code Agent}, but every model turn and tool call is
 * an activity, so the run is durable: it survives worker crashes and replays
 * deterministically from history without re-executing completed activities.
 */
@WorkflowInterface
public interface AgentWorkflow {

    /** Pursues {@link DurableAgentRun#goal()} to completion, durably. */
    @WorkflowMethod
    AgentRunResult run(DurableAgentRun run);

    /**
     * Answers one tool call that a gate stopped pending a person's decision (#101).
     *
     * <p>This is where the waiting lives, and the reason it is here rather than in a gate:
     * a Temporal activity cannot block on a person. It would burn its start-to-close
     * timeout, fail, and be retried — paging somebody again on each attempt and failing the
     * call anyway — which is why {@code ToolActivitiesImpl} refuses a gate that says
     * {@code ToolGate.waitsForAHuman()}. A workflow blocking on a signal costs nothing and
     * survives a worker dying, so the run can sit here for a week.
     *
     * <p><strong>Authorisation is Temporal's, not this method's.</strong> Anything that can
     * signal this workflow can decide, and neither {@code decidedBy} nor the invocation id
     * changes that — see {@link ApprovalVerdict}. Put the real check in front of the signal.
     *
     * <p><strong>A verdict is dropped unless the run is waiting on that ticket right now.</strong>
     * The first version kept every verdict for the life of the run, keyed on the model's own
     * tool-call id, and said here that an early one "cannot make anything happen that policy
     * would not". It could, twice over: a decision outlived the call it answered and
     * satisfied a later call that reused the id, and a decision signalled before anything
     * was parked ran the first park the moment it was raised — in both cases without the
     * call ever appearing in {@link #pendingApprovals()} for anybody to look at.
     *
     * <p>So the handle is a ticket the workflow mints per park, which nothing the model
     * writes reaches, and a ticket is learned by querying — and the query lists only what is
     * outstanding. A verdict for anything else is a mistake or an attempt, and is logged and
     * discarded either way.
     *
     * <p>A denial is honoured even if the policy has since stopped asking. Every other part
     * of this reads the gate first, because an approval must not widen what policy allows; a
     * refusal is never a widening, and discarding one because a gate relaxed while the call
     * sat on somebody's desk is the one direction with a bad outcome.
     */
    @SignalMethod
    void decide(ApprovalVerdict verdict);

    /**
     * The tool calls this run is currently waiting on somebody for.
     *
     * <p>A query rather than a database, so a console can list what is outstanding without
     * a second store to keep in step with the run — the answer is read out of the workflow's
     * own state, which is the state that actually decides. Empty when the run is not
     * waiting on anyone, which is almost always.
     *
     * <p>What it returns is written by the model: {@code PendingApproval} carries the
     * arguments verbatim, unfenced, because a reviewer has to see what they are approving.
     * That type's javadoc says whose job the escaping then is. It is worth reading before
     * building the console.
     *
     * <p>A durable run parks one call at a time, so this holds at most one entry today. It
     * is a list because that is the shape a console wants and because a runner that parked a
     * whole turn's worth would not have to change it — not as a promise that one is all
     * there will ever be. Read {@link PendingApproval#ticket()} rather than assuming an
     * index.
     */
    @QueryMethod
    List<PendingApproval> pendingApprovals();
}
