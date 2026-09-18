package dev.agentkit.host.plans;

import dev.agentkit.host.repo.AgentDefinition;
import dev.agentkit.host.repo.TaskInput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A task a plan-execute agent was started on from its form, as plan reuse sees it: what kind of task it is, and the
 * values that make it this one.
 *
 * <p><b>The kind</b> ({@link #shape}) is what decides the plan: the agent at its version; the value of every field
 * that is a choice or a yes/no, and of any field {@code sameWhen} names; which of the other fields are filled in; and
 * which values happen to be equal. Two onboardings of a Sales contractor who works on-site and has an end date are the
 * same kind; a remote engineer is another.
 *
 * <p><b>The values</b> are the rest — a name, an email, an address, a date — and the person's own name and email. A
 * plan is kept with each of them in it taken out ({@link #parameterize}) and put back for the next task of the kind
 * ({@link #instantiate}).
 */
public record PlanTask(String shape, Map<String, String> values) {

    /** A value shorter than this is left in the plan as it is: too likely to be part of another word. */
    static final int SHORTEST_VALUE = 3;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z]+\\.[A-Za-z0-9_]+)}}");

    public PlanTask {
        Objects.requireNonNull(shape, "shape");
        values = Map.copyOf(values);
    }

    /** The task {@code agent} at {@code version} was started on with {@code input}, by the person {@code principal}. */
    public static PlanTask of(AgentDefinition agent, String version, Map<String, Object> input,
                              Function<String, Optional<String>> principal) {
        TaskInput form = Objects.requireNonNull(agent.input(), "a task started from a form");
        List<String> sameWhen = agent.planReuse() == null ? List.of() : agent.planReuse().sameWhen();
        StringBuilder shape = new StringBuilder(agent.id()).append('@').append(version);
        Map<String, String> values = new TreeMap<>();
        for (TaskInput.Field field : form.fields().stream().sorted(Comparator.comparing(TaskInput.Field::name)).toList()) {
            Object given = input.get(field.name());
            String value = given == null ? "" : String.valueOf(given).strip();
            boolean decides = field.type().equals("boolean") || !field.choices().isEmpty()
                    || sameWhen.contains(field.name());
            if (decides) {
                shape.append('\n').append(field.name()).append('=').append(value);
            } else {
                shape.append('\n').append(field.name()).append(value.isEmpty() ? " empty" : " given");
                if (!value.isEmpty()) {
                    values.put("input." + field.name(), value);
                }
            }
        }
        for (String path : List.of("principal.email", "principal.name")) {
            principal.apply(path).filter(v -> !v.isBlank()).ifPresent(v -> values.put(path, v.strip()));
        }
        // Values that are equal in one task and not another would be taken out under one name and put back under
        // the other, so which are equal is part of the kind.
        Map<String, List<String>> alike = new TreeMap<>();
        values.forEach((name, value) -> alike.computeIfAbsent(value, v -> new ArrayList<>()).add(name));
        alike.values().stream().filter(names -> names.size() > 1)
                .forEach(names -> shape.append("\nsame: ").append(String.join(" = ", names)));
        return new PlanTask(shape.toString(), values);
    }

    /** {@code text} with each of this task's values in it replaced by its placeholder, {@code {{input.name}}}. */
    public String parameterize(String text) {
        List<Map.Entry<String, String>> longestFirst = new ArrayList<>(values.entrySet());
        longestFirst.sort(Comparator.<Map.Entry<String, String>>comparingInt(e -> e.getValue().length()).reversed()
                .thenComparing(Map.Entry::getKey));
        String result = text;
        for (Map.Entry<String, String> value : longestFirst) {
            if (value.getValue().length() < SHORTEST_VALUE) {
                continue;
            }
            result = Pattern.compile("(?<![\\p{Alnum}_])" + Pattern.quote(value.getValue()) + "(?![\\p{Alnum}_])")
                    .matcher(result).replaceAll(Matcher.quoteReplacement("{{" + value.getKey() + "}}"));
        }
        return result;
    }

    /** {@code template} with this task's values put in; empty if it names a value this task does not have. */
    public Optional<String> instantiate(String template) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) {
                return Optional.empty();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return Optional.of(out.toString());
    }

    /** The placeholders {@code template} uses. */
    static List<String> placeholders(String template) {
        List<String> names = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /** For a report: the values, in order. */
    Map<String, String> sortedValues() {
        return new LinkedHashMap<>(new TreeMap<>(values));
    }
}
