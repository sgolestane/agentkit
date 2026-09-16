package dev.agentkit.workbench.runtime;

import dev.agentkit.core.reliability.ApprovalNeeded;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.workbench.domain.Approval;
import dev.agentkit.workbench.domain.Risk;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.store.WorkbenchStore;
import dev.agentkit.workbench.tools.ToolCatalog;
import dev.agentkit.workbench.tools.WorkbenchTools;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The layer that decides whether a proposed action is allowed to happen — an AgentKit
 * {@code ToolGate}, which is what makes it unavoidable: there is no path from a model's
 * decision to the ALM that does not pass through here.
 *
 * <p>The three modes are the workbench's adoption ladder, stated as policy:
 *
 * <ul>
 *   <li><strong>PREVIEW</strong> — "what would the agent do?". Reads run; every write and every
 *       question is refused with a reason that tells the model to put it in the plan
 *       instead. Nothing outside the process changes, and nothing parks.</li>
 *   <li><strong>SUPERVISED</strong> — the human watches. Every ALM write parks for the
 *       operator's approval; a question parks for their answer. This is deliberately
 *       stricter than a threshold: supervision means each change is theirs to allow.</li>
 *   <li><strong>AUTO</strong> — earned automation. Writes up to {@link Risk#MEDIUM} proceed
 *       on their own because a person enabled a rule for this category of ticket; anything
 *       graded higher still parks. Risk only ever goes up from the tool's baseline — an
 *       unknown tool is graded {@link Risk#HIGH}, and an irreversible consequential change
 *       escalates — so automation never widens what a human would have been asked about.</li>
 * </ul>
 *
 * <p>Parking writes the {@link Approval} row first and then returns
 * {@link GateResult#needsAPerson}; the agent loop ends at {@code AWAITING_APPROVAL} and the
 * {@link Workbench} files the run as waiting. Resuming is a fresh run carrying the decided
 * approval, allowed exactly once, compared in the form the resumed run was shown
 * ({@link ShownArguments}) so a run that reproduces exactly what it was told cannot be
 * refused for reproducing it.
 */
public final class Supervisor implements ToolGate {

    private final Run.Mode mode;
    private final RunContext context;
    private final WorkbenchStore store;
    private final String ticketKey;
    private final AnswerBox answers;
    private final String tenantId;

    /** Capabilities this tenant has stopped wanting to be asked about, or {@code null}. */
    private final StandingApprovals trusted;

    /**
     * Every action a human has approved for this ticket that has not yet run — each
     * allowed exactly once, in whatever order the model proposes them.
     *
     * <p>The whole unconsumed set rather than the latest approval, because the model
     * re-walks its checklist on a resume and may lead with an earlier-approved step:
     * carrying only the newest approval produced an approve/park ping-pong in which the
     * same comment was approved three times before it ever ran. Consumption is recorded
     * on the store ({@link Approval#consumed()}), so an approval still authorises one
     * execution across the whole ladder, not one per resume.
     */
    private final List<Approval> preApproved;
    private final Map<String, AtomicBoolean> preApprovedUsed;

    /**
     * Set once an approval has been raised; every later call is refused. A backstop — the
     * runner ends the run on the first park — that keeps a runner which does not from
     * queueing a second approval for the same run.
     */
    private final AtomicBoolean parked = new AtomicBoolean();

    /** As below, asking about every consequential action — see {@link #trusted}. */
    public Supervisor(Run.Mode mode, RunContext context, WorkbenchStore store, String ticketKey,
            AnswerBox answers, List<Approval> preApproved) {
        this(mode, context, store, ticketKey, answers, preApproved, null, context.tenantId());
    }

    public Supervisor(Run.Mode mode, RunContext context, WorkbenchStore store, String ticketKey,
            AnswerBox answers, List<Approval> preApproved, StandingApprovals trusted,
            String tenantId) {
        this.trusted = trusted;
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.context = Objects.requireNonNull(context, "context");
        this.store = Objects.requireNonNull(store, "store");
        this.ticketKey = Objects.requireNonNull(ticketKey, "ticketKey");
        this.answers = Objects.requireNonNull(answers, "answers");
        this.preApproved = List.copyOf(Objects.requireNonNull(preApproved, "preApproved"));
        Map<String, AtomicBoolean> used = new java.util.concurrent.ConcurrentHashMap<>();
        for (Approval approval : this.preApproved) {
            used.put(approval.id(), new AtomicBoolean());
        }
        this.preApprovedUsed = used;
    }

    @Override
    public boolean boundToOneRun() {
        // This gate holds one run's context, its pre-approval and two flags — every one of
        // which is that run's and nobody else's.
        return true;
    }

    @Override
    public GateResult evaluate(Tool tool, ToolInvocation invocation) {
        Objects.requireNonNull(tool, "tool");

        if (parked.get()) {
            return GateResult.deny("This run is parked awaiting a person and cannot take "
                    + "further action.");
        }

        var policy = ToolCatalog.policyOrUnknown(invocation.name());
        Risk risk = effectiveRisk(policy.baselineRisk(), policy.reversible());

        // The question tool is its own branch in every mode: it is never a write to the
        // world, and what it needs is a person, not a threshold.
        if (WorkbenchTools.ASK_HUMAN.equals(invocation.name())) {
            return question(invocation);
        }

        if (risk == Risk.READ) {
            return GateResult.allow();
        }

        context.event(Run.Event.Type.ACTION_PROPOSED, Map.of(
                "tool", invocation.name(),
                "arguments", invocation.arguments(),
                "baselineRisk", policy.baselineRisk().name(),
                "effectiveRisk", risk.name()));

        if (mode == Run.Mode.PREVIEW) {
            return rejected(invocation.name(), risk, "This is a preview: nothing changes. "
                    + "Describe this action in your plan — what you would do, and that it "
                    + "would wait for the operator's approval — instead of performing it.");
        }

        // A previously approved action: allowed once, and only if it is the same action.
        // Matched against the whole unconsumed set, in whatever order the model proposes;
        // a proposal matching only already-spent approvals is a repeat and is refused.
        boolean matchedSpent = false;
        for (Approval approval : preApproved) {
            if (approval.kind() != Approval.Kind.ACTION || !matchesApproved(approval, invocation)) {
                continue;
            }
            if (!preApprovedUsed.get(approval.id()).compareAndSet(false, true)) {
                matchedSpent = true;
                continue;
            }
            store.save(approval.consumed());
            context.event(Run.Event.Type.ACTION_ALLOWED, Map.of(
                    "tool", invocation.name(), "risk", risk.name(),
                    "reason", "Approved by " + approval.decidedBy(),
                    "approvalId", approval.id()));
            return GateResult.allow();
        }
        if (matchedSpent) {
            return rejected(invocation.name(), risk,
                    "That approval has already been used once and does not authorise a repeat.");
        }

        // Two ways a call is cleared, and they are one rule: an automation rule covering
        // this ticket's category, or a capability the tenant has stopped wanting to be
        // asked about. Both stop at the same ceiling — a call this run's arguments push to
        // HIGH still reaches a person, because the grade is what THIS call is worth and
        // the clearance was given to the ordinary case.
        boolean cleared = mode == Run.Mode.AUTO
                || (trusted != null && trusted.covers(tenantId, policy.capability()));
        if (cleared && !risk.atLeast(Risk.HIGH)) {
            context.event(Run.Event.Type.ACTION_ALLOWED, Map.of(
                    "tool", invocation.name(), "risk", risk.name(),
                    "reason", mode == Run.Mode.AUTO
                            ? "An automation rule covers this ticket's category."
                            : "A person cleared " + policy.capability()
                                    + " and asked not to be shown it again."));
            return GateResult.allow();
        }

        return park(invocation, risk, policy.reversible());
    }

    /**
     * The tool's baseline, raised by what the call would mean — never lowered. An unknown
     * tool arrives already graded HIGH by the catalog's fallback; an irreversible change at
     * or above MEDIUM escalates to HIGH so automation cannot quietly perform it.
     */
    private Risk effectiveRisk(Risk baseline, boolean reversible) {
        Risk risk = baseline;
        if (risk == Risk.READ) {
            return risk;
        }
        if (!reversible && risk.atLeast(Risk.MEDIUM)) {
            risk = risk.raisedTo(Risk.HIGH);
        }
        return risk;
    }

    private GateResult question(ToolInvocation invocation) {
        if (mode == Run.Mode.PREVIEW) {
            return rejected(invocation.name(), Risk.LOW, "This is a preview: note the open "
                    + "question in your plan instead of asking it now.");
        }
        if (answers.hasRemaining()) {
            // An answer the human already gave is waiting; the tool handler returns it.
            return GateResult.allow();
        }
        return park(invocation, Risk.LOW, true);
    }

    private static boolean matchesApproved(Approval approval, ToolInvocation invocation) {
        return approval.toolName().equals(invocation.name())
                && ShownArguments.asShown(approval.arguments())
                        .equals(ShownArguments.asShown(invocation.arguments()));
    }

    private GateResult rejected(String toolName, Risk risk, String reason) {
        context.event(Run.Event.Type.ACTION_REJECTED,
                Map.of("tool", toolName, "risk", risk.name(), "reason", reason));
        return GateResult.deny(reason);
    }

    /**
     * Raises the approval row and stops the call, saying a person must decide. The row is
     * written before the outcome is returned so the queue and the run cannot disagree; the
     * {@link ApprovalNeeded} strings are the platform's words, never the model's — the
     * model's words travel on the invocation, where the console escapes them.
     */
    private GateResult park(ToolInvocation invocation, Risk risk, boolean reversible) {
        boolean isQuestion = WorkbenchTools.ASK_HUMAN.equals(invocation.name());
        Approval request = new Approval(WorkbenchStore.Ids.next("apr"), context.tenantId(),
                context.runId(), ticketKey,
                isQuestion ? Approval.Kind.QUESTION : Approval.Kind.ACTION,
                invocation, risk,
                isQuestion
                        ? "The agent needs an answer from a person before it can continue."
                        : "This change waits for the supervising operator.",
                effectOf(invocation, isQuestion), reversible, context.evidence(),
                Approval.State.PENDING, Instant.now(), null, null, null);
        store.save(request);
        parked.set(true);
        context.event(isQuestion
                        ? Run.Event.Type.HUMAN_QUESTION_ASKED
                        : Run.Event.Type.HUMAN_APPROVAL_REQUESTED,
                Map.of("approvalId", request.id(), "tool", request.toolName(),
                        "risk", risk.name(), "arguments", request.arguments()));
        ApprovalNeeded why = ApprovalNeeded
                .because(request.reason() + " (" + request.id() + ")")
                .withEffect(request.effect());
        return GateResult.needsAPerson(reversible ? why.thatCanBeUndone() : why);
    }

    private static String effectOf(ToolInvocation invocation, boolean isQuestion) {
        if (isQuestion) {
            return "Answering resumes the run with your reply, and the answer is remembered "
                    + "for future tickets.";
        }
        return switch (invocation.name()) {
            case "jira.add_comment" -> "A comment visible to the requester is added to "
                    + invocation.stringArgument("ticket_key") + " and cannot be unsaid.";
            case "jira.assign_to_me" -> "Ticket " + invocation.stringArgument("ticket_key")
                    + " leaves the unassigned queue.";
            case "jira.transition_ticket" -> "Ticket " + invocation.stringArgument("ticket_key")
                    + " moves through the '" + invocation.stringArgument("transition")
                    + "' transition.";
            default -> "Runs " + invocation.name() + ".";
        };
    }

    /** The evidence list a card shows; exposed for tests. */
    public List<String> evidence() {
        return context.evidence();
    }
}
