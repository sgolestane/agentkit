package dev.agentkit.core.deferred;

import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The {@code schedule_deferred_action} tool: a model schedules a goal to be carried out for a subject later.
 *
 * <p>It checks what code can check and stores the goal the model wrote; it does not judge the goal.
 * <ul>
 *   <li>The subject exists, as its {@link SubjectResolver} knows it now.</li>
 *   <li>The time is after now, given one of two ways: {@code run_at}, an ISO-8601 UTC time or a date (meaning its
 *       start in UTC); or {@code relative_to}, a time or date field of the subject's record, shifted by
 *       {@code offset_days} and/or {@code offset_minutes}. Relative times are the ones to prefer — "14 days before
 *       {@code termination_date}" is arithmetic a model should not be doing.</li>
 *   <li>The goal is present and bounded.</li>
 * </ul>
 * If the use case can say what the subject holds ({@code holdings}), the result names any of it the goal never
 * mentions: feedback the model can act on, not a rule, since a reminder need not name everything a removal must.
 *
 * <p>An action's id is {@code kind_subjectId_yyyyMMddHHmm}, so scheduling a second goal for the same subject at the
 * same minute replaces the first.
 */
public final class DeferredActionScheduler {

    public static final String TOOL_NAME = "schedule_deferred_action";

    /** The longest goal accepted. */
    public static final int MAX_GOAL_CHARS = 4_000;

    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

    private final SubjectResolver resolver;
    private final DeferredActionStore store;
    private final Supplier<Instant> clock;
    private final Function<SubjectRecord, List<String>> holdings;

    /**
     * @param holdings what a subject currently holds, named as a goal would name it, for feedback; may return an
     *                 empty list
     */
    public DeferredActionScheduler(SubjectResolver resolver, DeferredActionStore store, Supplier<Instant> clock,
                                   Function<SubjectRecord, List<String>> holdings) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.holdings = Objects.requireNonNull(holdings, "holdings");
    }

    /** The tool, scheduling on behalf of {@code scheduledBy}. */
    public FunctionTool tool(String scheduledBy) {
        return FunctionTool.builder(TOOL_NAME, description())
                .schema(schema())
                .sideEffects(SideEffects.IDEMPOTENT)
                .provenance(Provenance.FIRST_PARTY)
                .handler(inv -> schedule(inv, scheduledBy))
                .build();
    }

    /** The tool's description, for a deployment that builds the tool itself. */
    public String description() {
        return "Schedule a deferred action: a goal to be carried out for a subject (" + kinds() + ") at a later time. "
                + "Write the goal so it can be carried out with no other context. Give the time either as run_at, or "
                + "relative to a date or time field of the subject's record (relative_to, e.g. termination_date or "
                + "expires_at) shifted by offset_days and/or offset_minutes; prefer relative_to. When it runs, the action "
                + "can only read, revoke, notify and open requests, and only for that subject.";
    }

    /** The tool's input schema, for a deployment that builds the tool itself. */
    public Map<String, Object> schema() {
        return Map.of("type", "object", "properties", Map.of(
                        "subject_kind", Map.of("type", "string", "enum", List.copyOf(new TreeSet<>(resolver.kinds()))),
                        "subject_id", Map.of("type", "string", "description", "The subject's id in its system of record"),
                        "goal", Map.of("type", "string", "description", "What to do when the action runs"),
                        "run_at", Map.of("type", "string",
                                "description", "When to run: an ISO-8601 UTC time (2026-09-16T18:00:00Z) or a date (2026-12-31)"),
                        "relative_to", Map.of("type", "string",
                                "description", "A date or time field of the subject's record to count from"),
                        "offset_days", Map.of("type", "integer",
                                "description", "Days after relative_to; negative for before"),
                        "offset_minutes", Map.of("type", "integer",
                                "description", "Minutes after relative_to; negative for before")),
                "required", List.of("subject_kind", "subject_id", "goal"));
    }

    /** Handles one call to the tool on behalf of {@code scheduledBy}. */
    public ToolResult schedule(ToolInvocation inv, String scheduledBy) {
        String kind = blankToNull(inv.stringArgument("subject_kind"));
        String id = blankToNull(inv.stringArgument("subject_id"));
        if (kind == null || id == null) {
            return ToolResult.error("Scheduler: subject_kind and subject_id are required.");
        }
        Optional<SubjectRecord> found = resolver.resolve(kind, id);
        if (found.isEmpty()) {
            return ToolResult.error("Scheduler: no " + kind + " with id " + id + ".");
        }
        SubjectRecord subject = found.get();
        String goal = blankToNull(inv.stringArgument("goal"));
        if (goal == null) {
            return ToolResult.error("Scheduler: goal is required.");
        }
        if (goal.length() > MAX_GOAL_CHARS) {
            return ToolResult.error("Scheduler: goal is " + goal.length() + " characters; the limit is "
                    + MAX_GOAL_CHARS + ".");
        }
        String runAtArg = blankToNull(inv.stringArgument("run_at"));
        String relativeTo = blankToNull(inv.stringArgument("relative_to"));
        if ((runAtArg == null) == (relativeTo == null)) {
            return ToolResult.error("Scheduler: give exactly one of run_at or relative_to.");
        }

        Instant runAt;
        String when;
        if (runAtArg != null) {
            Optional<Instant> parsed = parseTime(runAtArg);
            if (parsed.isEmpty()) {
                return ToolResult.error("Scheduler: run_at must be an ISO-8601 UTC time such as 2026-09-16T18:00:00Z "
                        + "or a date such as 2026-12-31, got \"" + runAtArg + "\"");
            }
            runAt = parsed.get();
            when = "at " + runAt;
        } else {
            Optional<Instant> anchor = subject.instant(relativeTo);
            if (anchor.isEmpty()) {
                return ToolResult.error("Scheduler: the " + kind + " " + id + " has no date or time field " + relativeTo
                        + ". Its fields: " + new TreeSet<>(subject.facts().keySet()));
            }
            Integer days = optionalInteger(inv.argument("offset_days"));
            Integer minutes = optionalInteger(inv.argument("offset_minutes"));
            if (days == null || minutes == null) {
                return ToolResult.error("Scheduler: offset_days and offset_minutes must be whole numbers.");
            }
            Duration offset = Duration.ofDays(days).plusMinutes(minutes);
            runAt = anchor.get().plus(offset);
            when = offset.isZero() ? "at " + relativeTo + " (" + anchor.get() + ")"
                    : describe(offset.abs()) + (offset.isNegative() ? " before " : " after ") + relativeTo
                            + " (" + anchor.get() + ")";
        }
        Instant now = clock.get();
        if (!runAt.isAfter(now)) {
            return ToolResult.error("Scheduler: " + runAt + " is not after now (" + now + "). Nothing scheduled.");
        }

        String actionId = kind + "_" + id + "_" + ID_TIME.format(runAt);
        boolean replaced;
        try {
            replaced = store.put(new DeferredAction(actionId, kind, id, runAt, when, goal, now, scheduledBy,
                    DeferredAction.Status.SCHEDULED, "", null));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Scheduler: " + e.getMessage());
        }

        StringBuilder out = new StringBuilder("Scheduler: " + (replaced ? "replaced" : "scheduled") + " deferred action "
                + actionId + " to run " + when + ".");
        List<String> held = holdings.apply(subject);
        if (!held.isEmpty()) {
            String lowerGoal = goal.toLowerCase(Locale.ROOT);
            List<String> unnamed = held.stream().filter(h -> !lowerGoal.contains(h.toLowerCase(Locale.ROOT))).toList();
            out.append(" On record for ").append(kind).append(' ').append(id).append(": ").append(held)
                    .append(unnamed.isEmpty() ? "; the goal names all of it." : "; the goal does not name: " + unnamed + ".");
        }
        return ToolResult.ok(out.toString());
    }

    private String kinds() {
        return String.join(", ", new TreeSet<>(resolver.kinds()));
    }

    static Optional<Instant> parseTime(String value) {
        try {
            return Optional.of(Instant.parse(value.strip()));
        } catch (DateTimeParseException notAnInstant) {
            try {
                return Optional.of(LocalDate.parse(value.strip()).atStartOfDay().toInstant(ZoneOffset.UTC));
            } catch (DateTimeParseException notADate) {
                return Optional.empty();
            }
        }
    }

    private static String describe(Duration offset) {
        long days = offset.toDays();
        long minutes = offset.minusDays(days).toMinutes();
        if (minutes == 0) {
            return days + (days == 1 ? " day" : " days");
        }
        if (days == 0) {
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return days + (days == 1 ? " day " : " days ") + minutes + (minutes == 1 ? " minute" : " minutes");
    }

    /** A whole number, 0 when absent, or null when present and not a whole number. */
    private static Integer optionalInteger(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        if (value instanceof String s) {
            if (s.isBlank()) {
                return 0;
            }
            try {
                return Integer.parseInt(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
