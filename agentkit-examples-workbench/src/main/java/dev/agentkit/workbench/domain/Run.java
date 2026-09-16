package dev.agentkit.workbench.domain;

import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.OneLine;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One workbench run against one ticket, as the platform records it — not as the model
 * remembers it. Everything the console, the resume path or an auditor needs lives here and
 * in the {@link Event} stream.
 *
 * @param id         stable identifier
 * @param tenantId   every row is tenant-scoped
 * @param ticketKey  the ticket this run works
 * @param mode       what the run is allowed to change — see {@link Mode}
 * @param trigger    what started it — the operator, a bulk selection, a rule, a resume
 * @param goal       what it was asked to do
 * @param status     where it is now
 * @param createdAt  when the row was made
 * @param startedAt  when work began, or {@code null}
 * @param completedAt when it stopped, or {@code null}
 * @param summary    the closing account, once there is one
 */
public record Run(String id, String tenantId, String ticketKey, Mode mode, Trigger trigger,
                  String goal, Status status, Instant createdAt, Instant startedAt,
                  Instant completedAt, String summary) {

    /**
     * The workbench's adoption ladder, as three gate policies.
     *
     * <p>{@code PREVIEW} is "what would the agent do?": reads only, writes refused with the
     * refusal reported in the plan. {@code SUPERVISED} is "let the agent do it while I watch":
     * every ALM write parks for the operator. {@code AUTO} is the earned end state: writes
     * an automation rule covers proceed on their own, anything graded higher still parks.
     */
    public enum Mode { PREVIEW, SUPERVISED, AUTO }

    /** How a run came to exist. Every trigger runs the same runtime. */
    public enum Trigger { OPERATOR, BULK, RULE, RESUME }

    /**
     * Lifecycle. {@code WAITING_FOR_HUMAN} is the one that matters architecturally: it is a
     * durable state, not a thread parked on a latch — the decision may come next week.
     */
    public enum Status { PENDING, RUNNING, WAITING_FOR_HUMAN, COMPLETED, FAILED, CANCELLED }

    /** The ceiling on a stored {@code summary}, in characters before {@link Cut}'s marker. */
    public static final int SUMMARY_LIMIT = 400;

    public Run {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(ticketKey, "ticketKey");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * This row as it stops, with the summary an operator will read bounded and flattened.
     * The text is always somebody else's in the end — a provider's exception quoting a
     * ticket, a model's closing sentence — so it is cut and flattened here, once.
     */
    public Run concluded(Status next, String closing) {
        Objects.requireNonNull(next, "next");
        if (next == Status.PENDING || next == Status.RUNNING) {
            throw new IllegalArgumentException("A run that stopped cannot be filed as " + next);
        }
        return withSummary(Cut.to(OneLine.of(closing == null ? "" : closing), SUMMARY_LIMIT))
                .withStatus(next);
    }

    public Run withStatus(Status next) {
        Instant started = startedAt == null && next == Status.RUNNING ? Instant.now() : startedAt;
        Instant completed = switch (next) {
            case COMPLETED, FAILED, CANCELLED -> Instant.now();
            default -> completedAt;
        };
        return new Run(id, tenantId, ticketKey, mode, trigger, goal, next, createdAt, started,
                completed, summary);
    }

    public Run withSummary(String text) {
        return new Run(id, tenantId, ticketKey, mode, trigger, goal, status, createdAt,
                startedAt, completedAt, text);
    }

    /**
     * One thing that happened, appended and never edited. The same append-only stream that
     * answers "what happened" later is what paints the console now, so the two cannot drift.
     *
     * @param sequence monotonic within a run
     */
    public record Event(String runId, long sequence, Type type, Instant at,
                        Map<String, Object> detail) {

        /** Named for what happened in the operator's world, not for the code that emitted it. */
        public enum Type {
            RUN_STARTED,
            TOOL_STARTED,
            TOOL_COMPLETED,
            TOOL_FAILED,
            ACTION_PROPOSED,
            ACTION_ALLOWED,
            ACTION_REJECTED,
            HUMAN_APPROVAL_REQUESTED,
            HUMAN_QUESTION_ASKED,
            HUMAN_APPROVED,
            HUMAN_REJECTED,
            HUMAN_ANSWERED,
            LEARNING_RECORDED,
            /** A rejection's reason was kept so a later run is told (#331). */
            CORRECTION_RECORDED,
            /** This run was told what people have refused before, and by whom. */
            CORRECTIONS_RECALLED,
            CAPABILITY_GAP_REPORTED,
            /**
             * The operator acted on the ticket themselves, through the workbench. Not a
             * run's doing — the event log is a record of what happened, and a person
             * working a ticket by hand is one of the things that happens here.
             */
            OPERATOR_COMMENTED,
            OPERATOR_TRANSITIONED,
            RUN_PARKED,
            RUN_RESUMED,
            RUN_COMPLETED,
            RUN_CANCELLED,
            RUN_FAILED
        }

        public Event {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(at, "at");
            detail = Map.copyOf(Objects.requireNonNull(detail, "detail"));
        }
    }
}
