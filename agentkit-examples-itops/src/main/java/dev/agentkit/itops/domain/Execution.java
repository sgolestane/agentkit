package dev.agentkit.itops.domain;

import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.OneLine;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One agent run, as the platform records it — not as the model remembers it.
 *
 * <p>The transcript is not the state. A conversation is lossy (it gets compacted), it is
 * not queryable, and it is written by the thing whose behaviour is under review. Everything
 * the platform needs in order to resume, audit or answer "what happened" lives here and in
 * the {@link Event} stream, so a restart loses nothing and an auditor never has to trust a
 * model's account of itself.
 *
 * @param id        stable identifier, used by the UI and the event stream
 * @param tenantId  every row in this system is tenant-scoped
 * @param agentId   which agent definition ran
 * @param trigger   what started it
 * @param triggerReference the schedule name, conversation id or ticket id behind the trigger
 * @param goal      what it was asked to do
 * @param status    where it is now
 * @param createdAt when the row was made
 * @param startedAt when work began, or {@code null}
 * @param completedAt when it stopped, or {@code null}
 * @param summary   the closing account, once there is one
 */
public record Execution(String id, String tenantId, String agentId, Trigger trigger,
                        String triggerReference, String goal, Status status,
                        Instant createdAt, Instant startedAt, Instant completedAt,
                        String summary) {

    /** How an execution came to exist. Both modes run the same runtime. */
    public enum Trigger { CHAT, SCHEDULE, WORKFLOW, APPROVAL_RESUME }

    /**
     * Lifecycle. {@code WAITING_FOR_APPROVAL} is the one that matters architecturally: it is
     * a durable state, not a thread parked on a latch, so the process that started the run
     * may exit before a human ever looks at it.
     */
    public enum Status { PENDING, RUNNING, WAITING_FOR_APPROVAL, COMPLETED, FAILED, CANCELLED }

    public Execution {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * The ceiling on a stored {@code summary}, in characters before {@link Cut}'s marker.
     *
     * <p>400 because that is what the two sites that already bounded one chose —
     * {@code WorkflowRunner}'s thrown-step summary and {@code IntakeWorker}'s sweep summary —
     * and a third number would only mean an operator's list rendered three widths.
     */
    public static final int SUMMARY_LIMIT = 400;

    /**
     * This row as it stops, with the summary an operator will read bounded and flattened.
     *
     * <p><strong>One rule, three runners (#267).</strong> Every durable transition out of
     * {@code RUNNING} in this module used to be assembled by whichever runner happened to be
     * holding the pieces — {@code withSummary(...).withStatus(...)}, spelled out fifteen
     * times across {@code ExecutionRunner}, {@code IntakeWorker} and {@code WorkflowRunner}
     * — and they had drifted. Two of the fifteen put the text through
     * {@code Cut.to(OneLine.of(...))} and thirteen did not, so
     * {@code "Failed: " + failure.getMessage()} and
     * {@code "Step x failed: " + result.content()} wrote a provider's whole message, newlines
     * and all, into a field a console renders as one line of a table. That is the shape #151
     * measured at 200,000 characters and 37 seconds to emit.
     *
     * <p>Bounding here rather than at each caller because the callers are the thing that
     * drifted. The text is always somebody else's in the end: a provider's exception message
     * quoting a ticket, a tool result, a model's closing sentence. None of them is a line,
     * and none of them is short because it was asked to be.
     *
     * <p><strong>What this cannot do, said plainly because the issue asked it to.</strong> It
     * cannot carry {@code startedAt} forward from a row it was never given. {@code withStatus}
     * already carries it, and it is null here exactly when the caller built this transition
     * from a stale copy of the record — the {@code PENDING} one it created, rather than the
     * {@code RUNNING} one {@code OpsStore.save} handed back. That is a defect at the call
     * site and it is fixed at the call sites; a helper that invented a start time to cover it
     * would put a made-up number where the missing one at least reads as missing. Null is
     * also the honest answer for a row that really never started — a ticket cancelled because
     * another execution had claimed it.
     *
     * @param status where this run stopped; anything but {@link Status#PENDING} and
     *     {@link Status#RUNNING}, which are not stops
     * @param summary the closing account, cut and flattened; {@code null} is read as empty
     * @throws IllegalArgumentException if {@code status} is {@code PENDING} or {@code RUNNING}
     */
    public Execution concluded(Status status, String summary) {
        Objects.requireNonNull(status, "status");
        if (status == Status.PENDING || status == Status.RUNNING) {
            throw new IllegalArgumentException(
                    "A run that stopped cannot be filed as " + status);
        }
        return withSummary(Cut.to(OneLine.of(summary == null ? "" : summary), SUMMARY_LIMIT))
                .withStatus(status);
    }

    public Execution withStatus(Status next) {
        Instant started = startedAt == null && next == Status.RUNNING ? Instant.now() : startedAt;
        Instant completed = switch (next) {
            case COMPLETED, FAILED, CANCELLED -> Instant.now();
            default -> completedAt;
        };
        return new Execution(id, tenantId, agentId, trigger, triggerReference, goal, next,
                createdAt, started, completed, summary);
    }

    public Execution withSummary(String text) {
        return new Execution(id, tenantId, agentId, trigger, triggerReference, goal, status,
                createdAt, startedAt, completedAt, text);
    }

    /**
     * One thing that happened, appended and never edited.
     *
     * <p>Append-only because an audit trail that can be rewritten answers a different
     * question from the one it was built for, and because the same stream drives the live
     * UI — a client that has seen event <em>n</em> can be sent everything after it without
     * the server holding per-client state.
     *
     * @param sequence monotonic within an execution
     * @param type     what kind of thing happened
     * @param at       when
     * @param detail   structured payload; the UI renders it, the auditor reads it
     */
    public record Event(String executionId, long sequence, Type type, Instant at,
                        Map<String, Object> detail) {

        /**
         * The vocabulary of the audit trail.
         *
         * <p>Named for what happened in the operator's world rather than for the code that
         * emitted it, because these are read by people reconstructing an incident, and
         * "why did it touch that group" is not answerable from {@code STEP_3_COMPLETED}.
         */
        public enum Type {
            EXECUTION_STARTED,
            TICKET_DISCOVERED,
            TICKET_CLAIM_SKIPPED,
            TICKET_CLAIMED,
            CAPABILITY_EVALUATED,
            TOOLS_DISCLOSED,
            /**
             * This run was told what people have refused before, and by whom (#329).
             *
             * <p>{@code HUMAN_REJECTED} records that a correction was written; without this
             * nothing recorded that one was read. For a durable cross-run surface whose
             * contents reach every later prompt, the day it matters is the day a mistaken or
             * poisoned note produces a bad run — and then the question is which correction,
             * from whom, was in that run's goal. The row is the record (#157).
             */
            CORRECTIONS_RECALLED,
            /**
             * The run read somebody else's words and the policy in force changed for the
             * rest of it (#162). Its own row rather than an {@code ACTION_PROPOSED}: this
             * is not a proposal and nothing is being asked, and an auditor reading why a
             * routine action needed a person on this run and not the last one has to be
             * able to find the moment the line moved.
             */
            TRUST_FLOOR_LOWERED,
            ACTION_PROPOSED,
            ACTION_ALLOWED,
            ACTION_REJECTED,
            HUMAN_APPROVAL_REQUESTED,
            HUMAN_APPROVED,
            HUMAN_REJECTED,
            TOOL_STARTED,
            TOOL_COMPLETED,
            TOOL_FAILED,
            VERIFICATION_COMPLETED,
            VERIFICATION_FAILED,
            ARTIFACT_CREATED,
            EXECUTION_PARKED,
            EXECUTION_RESUMED,
            EXECUTION_COMPLETED,
            /**
             * The run stopped because the thread carrying it was interrupted (#265).
             *
             * <p>Its own row rather than {@link #EXECUTION_FAILED}, because nothing failed:
             * {@code StopReason.CANCELLED} is an ordinary stop, and the steps already taken
             * stand. Filing it under the failure heading would tell an operator hunting a
             * broken connector to start here.
             */
            EXECUTION_CANCELLED,
            EXECUTION_FAILED
        }

        public Event {
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(at, "at");
            detail = Map.copyOf(Objects.requireNonNull(detail, "detail"));
        }
    }
}
