package dev.agentkit.accessdesk.deferred;

import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The {@code schedule_deferred_action} tool: a model schedules a goal to be carried out for a subject at a
 * later time.
 *
 * <p>It checks what code can check — the subject exists, the time is a real time after now, given either
 * outright or relative to a time field of the subject's record — and stores the goal the model wrote. It
 * does not judge the goal. If the use case can say what the subject currently holds ({@code holdings}), the
 * result names any of those the goal never mentions: feedback the model can act on.
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
     * @param holdings what a subject currently holds, by system name, for feedback; may return an empty list
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
        String kinds = String.join(", ", new TreeSet<>(resolver.kinds()));
        return FunctionTool.builder(TOOL_NAME,
                        "Schedule a deferred action: a goal to be carried out for a subject (" + kinds + ") at a later "
                                + "time. Write the goal so it can be carried out with no other context. Give the time "
                                + "either as run_at, or as offset_minutes relative to a time field of the subject's record "
                                + "(relative_to, e.g. expires_at); one or the other, not both. When it runs, the action can "
                                + "only read, revoke, notify and open requests, and only for that subject.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "subject_kind", Map.of("type", "string", "enum", List.copyOf(new TreeSet<>(resolver.kinds()))),
                                "subject_id", Map.of("type", "string", "description", "The subject's id, e.g. a grant id"),
                                "goal", Map.of("type", "string", "description", "What to do when the action runs"),
                                "run_at", Map.of("type", "string", "format", "date-time",
                                        "description", "When to run, as an ISO-8601 UTC time such as 2026-09-16T18:00:00Z"),
                                "relative_to", Map.of("type", "string",
                                        "description", "A time field of the subject's record to count from, e.g. expires_at"),
                                "offset_minutes", Map.of("type", "integer",
                                        "description", "Minutes after relative_to; negative for minutes before")),
                        "required", List.of("subject_kind", "subject_id", "goal")))
                .sideEffects(SideEffects.IDEMPOTENT)
                .provenance(Provenance.FIRST_PARTY)
                .handler(inv -> schedule(inv, scheduledBy))
                .build();
    }

    ToolResult schedule(ToolInvocation inv, String scheduledBy) {
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
            return ToolResult.error("Scheduler: goal is " + goal.length() + " characters; the limit is " + MAX_GOAL_CHARS + ".");
        }
        String runAtArg = blankToNull(inv.stringArgument("run_at"));
        String relativeTo = blankToNull(inv.stringArgument("relative_to"));
        if ((runAtArg == null) == (relativeTo == null)) {
            return ToolResult.error("Scheduler: give exactly one of run_at or relative_to.");
        }

        Instant runAt;
        String when;
        if (runAtArg != null) {
            // The schema says date-time, but nothing checks a schema before a handler runs.
            try {
                runAt = Instant.parse(runAtArg);
            } catch (DateTimeParseException e) {
                return ToolResult.error("Scheduler: run_at must be an ISO-8601 UTC time such as 2026-09-16T18:00:00Z, got \""
                        + runAtArg + "\"");
            }
            when = "at " + runAt;
        } else {
            Optional<Instant> anchor = subject.instant(relativeTo);
            if (anchor.isEmpty()) {
                return ToolResult.error("Scheduler: the " + kind + " " + id + " has no time field " + relativeTo
                        + ". Its fields: " + new TreeSet<>(subject.facts().keySet()));
            }
            Integer offset = integer(inv.argument("offset_minutes"));
            if (offset == null) {
                return ToolResult.error("Scheduler: offset_minutes must be a whole number of minutes.");
            }
            runAt = anchor.get().plus(offset, ChronoUnit.MINUTES);
            when = offset == 0 ? "at " + relativeTo + " (" + anchor.get() + ")"
                    : Math.abs(offset) + " minutes " + (offset < 0 ? "before " : "after ") + relativeTo + " (" + anchor.get() + ")";
        }
        Instant now = clock.get();
        if (!runAt.isAfter(now)) {
            return ToolResult.error("Scheduler: " + runAt + " is not after now (" + now + "). Nothing scheduled.");
        }

        String actionId = kind + "_" + id + "_" + ID_TIME.format(runAt);
        boolean replaced = store.put(new DeferredAction(actionId, kind, id, runAt, when, goal, now, scheduledBy,
                DeferredAction.Status.SCHEDULED, "", null));

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

    private static Integer integer(Object value) {
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        if (value instanceof String s) {
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
