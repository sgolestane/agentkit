package dev.agentkit.workbench.domain;

import dev.agentkit.core.tool.ToolInvocation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Something a run stopped on, waiting for a person: either a proposed action to authorise,
 * or a question to answer.
 *
 * <p>The two kinds are one queue on purpose — the workbench's whole argument is that the
 * supervising human is the escalation path, and a person triaging their agent's pending
 * items should not have to look in two places. What differs is what a decision means:
 * approving an {@code ACTION} re-runs the ticket with exactly that call cleared once;
 * answering a {@code QUESTION} re-runs it with the answer available to {@code ask_human},
 * and records the answer as a durable lesson so the next run need not ask.
 *
 * <p>It carries the {@link ToolInvocation} whole rather than a name and an argument map, so
 * the record a human decides from cannot pair one call's name with another call's arguments
 * (the itops example carries the measurement for why).
 *
 * @param id          stable identifier — the handle a decision names, minted by the store,
 *                    never the model's call id
 * @param tenantId    tenant scope
 * @param runId       the parked run this will resume
 * @param ticketKey   the ticket that run was working
 * @param kind        an action to authorise, or a question to answer
 * @param invocation  the call as proposed, whole
 * @param risk        the supervisor's graded risk, after any escalation
 * @param reason      why a person is needed — the platform's words, never the model's
 * @param effect      what will change if approved
 * @param reversible  whether it can be undone afterwards
 * @param evidence    what the run had established from systems of record before this
 * @param state       pending until someone decides
 * @param requestedAt when it was parked
 * @param decidedBy   who decided, once decided
 * @param decidedAt   when
 * @param note        what they said — an approval note, or the answer to a question
 */
public record Approval(String id, String tenantId, String runId, String ticketKey, Kind kind,
                       ToolInvocation invocation, Risk risk, String reason, String effect,
                       boolean reversible, List<String> evidence, State state,
                       Instant requestedAt, String decidedBy, Instant decidedAt, String note) {

    public enum Kind { ACTION, QUESTION }

    /**
     * {@code CONSUMED} is an {@code APPROVED} action whose one authorised execution has
     * happened. The split exists because a resume carries every approved-but-unconsumed
     * action for the ticket — the model may re-walk its checklist in a different order
     * than the approvals arrived, and an approval that vanished from the resume set the
     * moment a later one was granted produced an approve/park ping-pong, measured in the
     * browser: three approvals of the same comment before it ever ran.
     */
    public enum State { PENDING, APPROVED, REJECTED, ANSWERED, CONSUMED }

    public Approval {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(ticketKey, "ticketKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(effect, "effect");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }

    /** The tool the parked call would run. */
    public String toolName() {
        return invocation.name();
    }

    /** The arguments as proposed, verbatim — the model's words, escaped by whoever renders them. */
    public Map<String, Object> arguments() {
        return invocation.arguments();
    }

    /** The question the model asked, when this is a {@link Kind#QUESTION}. */
    public String question() {
        String question = invocation.stringArgument("question");
        return question == null ? "" : question;
    }

    public Approval decided(State outcome, String who, String saying) {
        return new Approval(id, tenantId, runId, ticketKey, kind, invocation, risk, reason,
                effect, reversible, evidence, outcome, requestedAt, who, Instant.now(), saying);
    }

    /** This approval spent: the decision stands, and it authorises no further execution. */
    public Approval consumed() {
        return new Approval(id, tenantId, runId, ticketKey, kind, invocation, risk, reason,
                effect, reversible, evidence, State.CONSUMED, requestedAt, decidedBy, decidedAt,
                note);
    }
}
