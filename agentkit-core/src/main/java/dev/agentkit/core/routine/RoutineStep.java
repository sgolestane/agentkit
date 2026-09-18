package dev.agentkit.core.routine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One call in a {@link Routine}: a tool, and its arguments with this task's values taken out and left as
 * placeholders.
 *
 * <p>A run that called {@code okta_create_user(email=marcus@acme.example)} for employee W-1002 is recorded as
 * {@code okta_create_user(email={work_email})} once {@code work_email} is known to be a parameter of the task. That
 * substitution is the whole of the generalisation: nothing else about the call is inferred, and an argument holding
 * a value that came from somewhere else — a search result, a generated id — is recorded literally and will be
 * replayed literally, which is why {@link Routine} is only established from runs that agreed.
 *
 * @param tool      the tool's name
 * @param arguments the arguments as recorded, string values possibly holding {@code {parameter}} placeholders
 */
public record RoutineStep(String tool, Map<String, Object> arguments) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]{1,64})}");

    /** The shortest parameter value that is replaced where it appears inside a longer argument. */
    private static final int SHORTEST_SUBSTITUTABLE = 3;

    public RoutineStep {
        Objects.requireNonNull(tool, "tool");
        if (tool.isBlank()) {
            throw new IllegalArgumentException("a step names a tool");
        }
        Objects.requireNonNull(arguments, "arguments");
        arguments = Map.copyOf(new LinkedHashMap<>(arguments));
    }

    /**
     * This call with the task's values replaced by placeholders.
     *
     * <p>A value is replaced where an argument is exactly it, and inside a longer argument when it is at least
     * {@value #SHORTEST_SUBSTITUTABLE} characters — short values ("no", "1") appear inside unrelated words, and a
     * routine that rewrote those would be worse than no routine.
     */
    public static RoutineStep recorded(String tool, Map<String, Object> arguments, Task task) {
        Map<String, Object> templated = new LinkedHashMap<>();
        arguments.forEach((name, value) -> templated.put(name, template(value, task)));
        return new RoutineStep(tool, templated);
    }

    /** These arguments with every placeholder filled from {@code task}. */
    public Map<String, Object> filledFor(Task task) {
        Map<String, Object> filled = new LinkedHashMap<>();
        arguments.forEach((name, value) -> filled.put(name, fill(value, task)));
        return filled;
    }

    /** The parameters this step's arguments expect, in name order. */
    public Set<String> placeholders() {
        Set<String> names = new TreeSet<>();
        arguments.values().forEach(value -> {
            if (value instanceof String text) {
                Matcher matcher = PLACEHOLDER.matcher(text);
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
        });
        return names;
    }

    private static Object template(Object value, Task task) {
        if (!(value instanceof String text) || text.isEmpty()) {
            return value;
        }
        String out = text;
        for (Map.Entry<String, String> parameter : task.parameters().entrySet()) {
            String actual = parameter.getValue();
            if (actual.isEmpty()) {
                continue;
            }
            if (out.equals(actual)) {
                return "{" + parameter.getKey() + "}";
            }
            if (actual.length() >= SHORTEST_SUBSTITUTABLE) {
                out = out.replace(actual, "{" + parameter.getKey() + "}");
            }
        }
        return out;
    }

    private static Object fill(Object value, Task task) {
        if (!(value instanceof String text)) {
            return value;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String parameter = matcher.group(1);
            String replacement = task.parameters().get(parameter);
            if (replacement == null) {
                // Left as it stands. Routines.replay refuses a step whose placeholders the task does not fill,
                // so this never reaches a tool.
                replacement = matcher.group(0);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
