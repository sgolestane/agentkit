package dev.agentkit.examples.deferred;

import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Records deferred actions, and backs the {@code schedule_deferred_action} tool that a model uses to
 * create them — for any kind of subject a {@link SubjectResolver} knows.
 *
 * <p>The tool validates what code can check — the subject exists, the date is a real date after today,
 * given either outright or relative to a date field of the subject's record — and stores the goal the
 * model wrote. It does not judge the goal. If the use case can say what the subject currently holds
 * ({@code holdings}), the result reports which of those the goal never names: feedback the model can
 * act on, since a reminder need not name everything and a removal should.
 *
 * <p>The store is in memory, and nothing here runs an action on its date; that needs durable storage
 * and a runner, which would call {@link DeferredActions} for the goal, tools and gate.
 */
public final class DeferredActionScheduler {

    public static final String TOOL_NAME = "schedule_deferred_action";

    /** The longest goal accepted. */
    public static final int MAX_GOAL_CHARS = 4_000;

    private final SubjectResolver resolver;
    private final Supplier<LocalDate> today;
    private final Function<SubjectRecord, List<String>> holdings;
    private final Map<String, DeferredAction> actions = new LinkedHashMap<>();

    /**
     * @param resolver looks subjects up when an action is scheduled
     * @param today    the current date
     * @param holdings what a subject currently holds, by system name, for feedback; may return an empty list
     */
    public DeferredActionScheduler(SubjectResolver resolver, Supplier<LocalDate> today,
                                   Function<SubjectRecord, List<String>> holdings) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.today = Objects.requireNonNull(today, "today");
        this.holdings = Objects.requireNonNull(holdings, "holdings");
    }

    public String description() {
        return "Schedule a deferred action: a goal to be carried out for a subject (" + String.join(", ",
                new TreeSet<>(resolver.kinds())) + ") on a later date. Write the goal so it can be carried out "
                + "with no other context. Give the date either as run_on, or as offset_days relative to a date "
                + "field in the subject's record (relative_to); one or the other, not both. When it runs, the "
                + "action can only revoke, notify people and open requests, and only for that subject.";
    }

    public Map<String, Object> schema() {
        return Map.of("type", "object", "properties", Map.of(
                        "subject_kind", Map.of("type", "string", "enum", List.copyOf(new TreeSet<>(resolver.kinds()))),
                        "subject_id", Map.of("type", "string", "description", "The subject's id in its system of record"),
                        "goal", Map.of("type", "string", "description", "What to do when the action runs"),
                        "run_on", Map.of("type", "string", "format", "date", "pattern", "^\\d{4}-\\d{2}-\\d{2}$",
                                "description", "The date to run on, YYYY-MM-DD"),
                        "relative_to", Map.of("type", "string",
                                "description", "A date field in the subject's record to count from"),
                        "offset_days", Map.of("type", "integer",
                                "description", "Days after relative_to; negative for days before")),
                "required", List.of("subject_kind", "subject_id", "goal"));
    }

    /** Handles a call to the tool. */
    public synchronized ToolResult schedule(ToolInvocation inv) {
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
        String runOnArg = blankToNull(inv.stringArgument("run_on"));
        String relativeTo = blankToNull(inv.stringArgument("relative_to"));
        if ((runOnArg == null) == (relativeTo == null)) {
            return ToolResult.error("Scheduler: give exactly one of run_on or relative_to.");
        }

        LocalDate runOn;
        String when;
        if (runOnArg != null) {
            // The schema says YYYY-MM-DD, but nothing checks a schema before a handler runs.
            try {
                runOn = LocalDate.parse(runOnArg);
            } catch (DateTimeParseException e) {
                return ToolResult.error("Scheduler: run_on must be a date in YYYY-MM-DD form, got \"" + runOnArg + "\"");
            }
            when = "on " + runOn;
        } else {
            Optional<LocalDate> anchor = subject.date(relativeTo);
            if (anchor.isEmpty()) {
                return ToolResult.error("Scheduler: the record for " + kind + " " + id + " has no date field "
                        + relativeTo + ". Its fields: " + new TreeSet<>(subject.facts().keySet()));
            }
            Integer offset = integer(inv.argument("offset_days"));
            if (offset == null) {
                return ToolResult.error("Scheduler: offset_days must be a whole number of days.");
            }
            runOn = anchor.get().plusDays(offset);
            when = offset == 0 ? "on " + relativeTo + " (" + anchor.get() + ")"
                    : Math.abs(offset) + " days " + (offset < 0 ? "before " : "after ") + relativeTo + " (" + anchor.get() + ")";
        }
        LocalDate now = today.get();
        if (!runOn.isAfter(now)) {
            return ToolResult.error("Scheduler: " + runOn + " is not after today (" + now + "). Nothing scheduled.");
        }

        String actionId = kind + "_" + id + "_" + runOn.format(DateTimeFormatter.BASIC_ISO_DATE);
        boolean replaced = actions.put(actionId, new DeferredAction(actionId, kind, id, runOn, when, goal, now)) != null;

        StringBuilder out = new StringBuilder("Scheduler: " + (replaced ? "replaced" : "scheduled") + " deferred action "
                + actionId + " (" + when + ").");
        List<String> held = holdings.apply(subject);
        if (!held.isEmpty()) {
            String lowerGoal = goal.toLowerCase(Locale.ROOT);
            List<String> unnamed = held.stream().filter(system -> !lowerGoal.contains(system.toLowerCase(Locale.ROOT))).toList();
            out.append(" On record for ").append(kind).append(' ').append(id).append(": ").append(held)
                    .append(unnamed.isEmpty() ? "; the goal names all of it." : "; the goal does not name: " + unnamed + ".");
        }
        return ToolResult.ok(out.toString());
    }

    /** Everything scheduled, in order. */
    public synchronized List<DeferredAction> scheduled() {
        return List.copyOf(actions.values());
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
