package dev.agentkit.host.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.util.OneLine;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a task agent works from: the fields a person fills in to start it — in a form in the console, as the arguments
 * of {@code run_<agent>} over MCP — and how they become the request the agent is given.
 *
 * <p>The schema is a flat JSON Schema object: each property a string (optionally an {@code enum}, or a {@code date}
 * or {@code email} format), an integer, a number or a boolean, with a {@code title} and {@code description} for the
 * person. Flat on purpose: it is a form a person fills in, and the shape MCP's own questions to a person allow.
 *
 * <p>The goal template is text with {@code {{input}}} — every field given, one per line — and {@code {{field}}} for
 * one field's value. Without one, the request is the fields, one per line.
 *
 * @param schema   the JSON Schema, as the definition wrote it
 * @param fields   its properties, in the order written
 * @param template the goal template
 */
public record TaskInput(Map<String, Object> schema, List<Field> fields, String template) {

    /** One field of the input. {@code choices} is empty unless it is an enum; {@code format} is null unless given. */
    public record Field(String name, String type, String title, String description, List<String> choices,
                        String format, boolean required) {
        public Field {
            choices = List.copyOf(choices);
        }
    }

    static final String DEFAULT_TEMPLATE = "{{input}}";

    private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean");
    private static final Set<String> FORMATS = Set.of("date", "email");
    private static final Set<String> SCHEMA_KEYS = Set.of("type", "properties", "required", "title", "description",
            "$schema", "additionalProperties");
    private static final Set<String> FIELD_KEYS = Set.of("type", "title", "description", "enum", "format", "default");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final ObjectMapper JSON = new ObjectMapper();

    public TaskInput {
        schema = Map.copyOf(schema);
        fields = List.copyOf(fields);
        Objects.requireNonNull(template, "template");
    }

    /**
     * Reads a schema and a template, reporting each problem through {@code problem(where, message)}; null if there
     * were any.
     */
    @SuppressWarnings("unchecked")
    static TaskInput parse(JsonNode schema, String template, BiConsumer<String, String> problem) {
        int[] found = {0};
        BiConsumer<String, String> report = (where, message) -> {
            found[0]++;
            problem.accept(where, message);
        };
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText())) {
            report.accept("", "must be a JSON Schema with type: object");
            return null;
        }
        schema.fieldNames().forEachRemaining(key -> {
            if (!SCHEMA_KEYS.contains(key)) {
                report.accept(key, "is not supported in an input schema");
            }
        });
        JsonNode properties = schema.path("properties");
        if (!properties.isObject() || properties.isEmpty()) {
            report.accept("properties", "must name at least one field");
            return null;
        }
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(r -> required.add(r.asText()));
        List<Field> fields = new ArrayList<>();
        properties.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            JsonNode field = entry.getValue();
            String where = "properties." + name;
            if (!FIELD_NAME.matcher(name).matches()) {
                report.accept(where, "a field's name is letters, digits and underscores");
                return;
            }
            field.fieldNames().forEachRemaining(key -> {
                if (!FIELD_KEYS.contains(key)) {
                    report.accept(where + "." + key, "is not supported; a field is flat: type, title, description, "
                            + "enum, format");
                }
            });
            String type = field.path("type").asText("");
            if (!TYPES.contains(type)) {
                report.accept(where + ".type", "must be one of " + new java.util.TreeSet<>(TYPES));
                return;
            }
            List<String> choices = new ArrayList<>();
            if (field.has("enum")) {
                if (!type.equals("string") || !field.get("enum").isArray() || field.get("enum").isEmpty()) {
                    report.accept(where + ".enum", "is a non-empty list, on a string field");
                } else {
                    field.get("enum").forEach(c -> choices.add(c.asText()));
                }
            }
            String format = field.hasNonNull("format") ? field.get("format").asText() : null;
            if (format != null && (!type.equals("string") || !FORMATS.contains(format))) {
                report.accept(where + ".format", "is date or email, on a string field");
            }
            fields.add(new Field(name, type, field.path("title").asText(name), field.path("description").asText(""),
                    choices, format, required.contains(name)));
        });
        for (String name : required) {
            if (!properties.has(name)) {
                report.accept("required", name + " is not one of the fields");
            }
        }
        String text = template == null ? DEFAULT_TEMPLATE : template;
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (!name.equals("input") && !properties.has(name)) {
                report.accept("goal", "{{" + name + "}} is not a field of the input");
            }
        }
        if (found[0] > 0) {
            return null;
        }
        return new TaskInput(JSON.convertValue(schema, Map.class), fields, text);
    }

    /** What is wrong with {@code input}, one sentence each; empty when it is a valid input. */
    public List<String> problems(Map<String, Object> input) {
        List<String> problems = new ArrayList<>();
        Map<String, Object> given = input == null ? Map.of() : input;
        for (String name : given.keySet()) {
            if (fields.stream().noneMatch(f -> f.name().equals(name))) {
                problems.add(name + " is not a field of this task");
            }
        }
        for (Field field : fields) {
            Object value = given.get(field.name());
            if (value == null || value instanceof String s && s.isBlank()) {
                if (field.required()) {
                    problems.add(field.title() + " is required");
                }
                continue;
            }
            String problem = switch (field.type()) {
                case "boolean" -> value instanceof Boolean || "true".equals(value) || "false".equals(value)
                        ? null : field.title() + " must be true or false";
                case "integer" -> isInteger(value) ? null : field.title() + " must be a whole number";
                case "number" -> isNumber(value) ? null : field.title() + " must be a number";
                default -> stringProblem(field, String.valueOf(value));
            };
            if (problem != null) {
                problems.add(problem);
            }
        }
        return problems;
    }

    /** The request for {@code input}, which must be valid: the template, with its placeholders filled. */
    public String render(Map<String, Object> input) {
        Map<String, Object> given = input == null ? Map.of() : input;
        StringBuilder lines = new StringBuilder();
        for (Field field : fields) {
            Object value = given.get(field.name());
            if (value != null && !(value instanceof String s && s.isBlank())) {
                lines.append("- ").append(field.name()).append(": ").append(OneLine.of(String.valueOf(value))).append('\n');
            }
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = name.equals("input") ? lines.toString().stripTrailing()
                    : OneLine.of(String.valueOf(given.getOrDefault(name, "")));
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString().strip();
    }

    /** The fields, one per line, for a prompt: what a request to this agent should say. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        for (Field field : fields) {
            text.append("- ").append(field.name()).append(" (").append(field.type())
                    .append(field.choices().isEmpty() ? "" : ": one of " + field.choices())
                    .append(field.format() == null ? "" : ", " + field.format())
                    .append(field.required() ? ", required" : ", optional").append(')');
            if (!field.description().isBlank()) {
                text.append(": ").append(OneLine.of(field.description()));
            }
            text.append('\n');
        }
        return text.toString().stripTrailing();
    }

    /** The schema, for a form or an MCP tool: only what this record understood, in the order written. */
    public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Field field : fields) {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", field.type());
            property.put("title", field.title());
            if (!field.description().isBlank()) {
                property.put("description", field.description());
            }
            if (!field.choices().isEmpty()) {
                property.put("enum", field.choices());
            }
            if (field.format() != null) {
                property.put("format", field.format());
            }
            properties.put(field.name(), property);
        }
        return Map.of("type", "object", "properties", properties,
                "required", fields.stream().filter(Field::required).map(Field::name).toList());
    }

    private static String stringProblem(Field field, String value) {
        if (!field.choices().isEmpty() && !field.choices().contains(value)) {
            return field.title() + " must be one of " + field.choices();
        }
        if ("date".equals(field.format())) {
            try {
                LocalDate.parse(value.strip());
            } catch (DateTimeParseException e) {
                return field.title() + " must be a date, such as 2026-10-01";
            }
        }
        if ("email".equals(field.format()) && !value.strip().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            return field.title() + " must be an email address";
        }
        return null;
    }

    private static boolean isInteger(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue() == Math.rint(n.doubleValue());
        }
        return String.valueOf(value).strip().matches("-?\\d+");
    }

    private static boolean isNumber(Object value) {
        if (value instanceof Number) {
            return true;
        }
        try {
            Double.parseDouble(String.valueOf(value).strip());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
