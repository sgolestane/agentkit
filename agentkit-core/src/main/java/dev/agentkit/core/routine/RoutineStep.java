package dev.agentkit.core.routine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

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
 * <h2>The template</h2>
 *
 * <p>Only top-level string arguments are templated. A string is {@code {name}} for a placeholder, and a literal
 * brace is doubled ({@code {{} and {@code }}}), so text that happens to look like a placeholder is never filled.
 * Nested maps and lists are recorded as they were: if they held a task's value they differ from run to run, the runs
 * never agree, and nothing settles — the safe way to be unable to generalise.
 *
 * @param tool      the tool's name
 * @param arguments the arguments as recorded; values may be null
 */
public record RoutineStep(String tool, Map<String, Object> arguments) {

    /** The shortest value replaced where it appears inside a longer argument, rather than as the whole of it. */
    private static final int SHORTEST_EMBEDDED = 3;

    public RoutineStep {
        Objects.requireNonNull(tool, "tool");
        if (tool.isBlank()) {
            throw new IllegalArgumentException("a step names a tool");
        }
        Objects.requireNonNull(arguments, "arguments");
        // Not Map.copyOf: a tool argument may be null, and refusing it here would drop the call from the
        // recording without a word.
        arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /**
     * This call with the task's values replaced by placeholders.
     *
     * <p>In one pass over each argument, so a placeholder is never rewritten by a later value:
     * <ul>
     *   <li>An argument that is exactly one parameter's value becomes that placeholder, however short.</li>
     *   <li>Inside a longer argument, a value of at least {@value #SHORTEST_EMBEDDED} characters is replaced where it
     *       stands as a whole word — bounded by the ends of the text or by anything that is not a letter or digit —
     *       and the longest value wins where two could match. {@code eng} is not found in {@code engineering}.</li>
     *   <li>A value two parameters share is never replaced, because which one it was cannot be known. It stays
     *       literal, differs between runs, and keeps the routine from settling.</li>
     * </ul>
     */
    public static RoutineStep recorded(String tool, Map<String, Object> arguments, Task task) {
        Map<String, List<String>> namesByValue = new LinkedHashMap<>();
        task.parameters().forEach((name, value) -> {
            if (!value.isEmpty()) {
                namesByValue.computeIfAbsent(value, v -> new ArrayList<>()).add(name);
            }
        });
        Map<String, Object> templated = new LinkedHashMap<>();
        arguments.forEach((name, value) -> templated.put(name,
                value instanceof String text ? template(text, namesByValue) : value));
        return new RoutineStep(tool, templated);
    }

    /** These arguments with every placeholder filled from {@code task}, and doubled braces made single. */
    public Map<String, Object> filledFor(Task task) {
        Map<String, Object> filled = new LinkedHashMap<>();
        arguments.forEach((name, value) -> filled.put(name,
                value instanceof String text ? fill(text, task.parameters(), null) : value));
        return filled;
    }

    /** The parameters this step's arguments expect, in name order. */
    public Set<String> placeholders() {
        Set<String> names = new TreeSet<>();
        arguments.values().forEach(value -> {
            if (value instanceof String text) {
                fill(text, Map.of(), names);
            }
        });
        return names;
    }

    private static String template(String text, Map<String, List<String>> namesByValue) {
        List<String> whole = namesByValue.get(text);
        if (whole != null && whole.size() == 1) {
            return "{" + whole.get(0) + "}";
        }
        List<Map.Entry<String, List<String>>> embeddable = namesByValue.entrySet().stream()
                .filter(e -> e.getKey().length() >= SHORTEST_EMBEDDED && e.getValue().size() == 1)
                .sorted(Comparator.comparingInt((Map.Entry<String, List<String>> e) -> e.getKey().length()).reversed())
                .toList();
        StringBuilder out = new StringBuilder(text.length() + 8);
        int i = 0;
        while (i < text.length()) {
            Map.Entry<String, List<String>> match = null;
            if (wordStartsAt(text, i)) {
                for (Map.Entry<String, List<String>> candidate : embeddable) {
                    String value = candidate.getKey();
                    if (text.startsWith(value, i) && wordEndsAt(text, i + value.length())) {
                        match = candidate;
                        break;
                    }
                }
            }
            if (match != null) {
                out.append('{').append(match.getValue().get(0)).append('}');
                i += match.getKey().length();
                continue;
            }
            char c = text.charAt(i);
            if (c == '{' || c == '}') {
                out.append(c);
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean wordStartsAt(String text, int index) {
        return index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1));
    }

    private static boolean wordEndsAt(String text, int index) {
        return index >= text.length() || !Character.isLetterOrDigit(text.charAt(index));
    }

    /**
     * Reads a template: doubled braces are literal, {@code {name}} is a placeholder. Fills from {@code values} and,
     * when {@code found} is given, collects the names instead. A placeholder with no value is left as written;
     * {@link Routines} refuses to replay a step whose placeholders the task does not fill, so it never reaches a tool.
     */
    private static String fill(String template, Map<String, String> values, Set<String> found) {
        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if ((c == '{' || c == '}') && i + 1 < template.length() && template.charAt(i + 1) == c) {
                out.append(c);
                i += 2;
                continue;
            }
            if (c == '{') {
                int close = template.indexOf('}', i + 1);
                if (close > i + 1) {
                    String name = template.substring(i + 1, close);
                    if (found != null) {
                        found.add(name);
                    }
                    String value = values.get(name);
                    out.append(value != null ? value : template, value != null ? 0 : i,
                            value != null ? value.length() : close + 1);
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
