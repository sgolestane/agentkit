package dev.agentkit.core.llm;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A JSON Schema the model's reply must conform to — the provider-agnostic form of
 * "structured output". Set it on an {@link LlmRequest} and the adapter constrains
 * decoding, so the returned text is valid JSON matching the schema instead of prose
 * you have to coax and then parse defensively.
 *
 * <p>Use it wherever a call's answer feeds code rather than a human: extraction,
 * classification, scoring, or any step whose result is branched on.
 *
 * <pre>{@code
 * OutputSchema schema = OutputSchema.ofProperties("sentiment", Map.of(
 *         "label", Map.of("type", "string", "enum", List.of("positive", "negative")),
 *         "confidence", Map.of("type", "number")));
 *
 * LlmResponse response = llm.generate(LlmRequest.builder(model)
 *         .addMessage(Message.user("Classify: " + text))
 *         .outputSchema(schema)
 *         .build());
 * // response.message().text() is JSON conforming to the schema
 * }</pre>
 *
 * <p><strong>Combinable with tools.</strong> Both supported providers accept a schema
 * and tools in the same request: the model may call a tool or answer, and when it
 * answers, the answer is schema-constrained. That is the natural shape for a verifier
 * or judge that can look things up before committing to a verdict.
 *
 * <p><strong>Write schemas providers accept.</strong> Constrained decoding supports a
 * subset of JSON Schema. Every object must carry {@code "additionalProperties": false}
 * and list all its properties in {@code required} — {@link #ofProperties} does both for
 * you; with {@link #of} it is on you, and omitting it is a 400 rather than a silent
 * degradation. {@code enum}, {@code const}, {@code anyOf}, {@code allOf} and
 * {@code $ref}/{@code $defs} are supported; recursive schemas and numeric/string
 * constraints ({@code minimum}, {@code pattern}, …) are not.
 *
 * <p><strong>Parsing is the caller's.</strong> {@code agentkit-core} deliberately has no
 * dependencies — not even a JSON library — so the response arrives as schema-conforming
 * text for you to parse with whatever you already use.
 *
 * <p>Support is per-adapter. The Anthropic client (and therefore {@code agentkit-llm-bedrock},
 * which reuses it) and the OpenRouter client both constrain decoding natively; the
 * OpenRouter client additionally asks for a provider that honours the request, so a
 * model that cannot constrain decoding fails rather than returning unconstrained prose.
 *
 * @param name   a short identifier for the shape, matching {@code [A-Za-z0-9_-]{1,64}}.
 *               OpenAI-shaped providers require it and enforce that charset; Anthropic's
 *               wire format has no name field, so it is dropped on that path
 * @param schema the JSON Schema, as a JSON-like map — typically
 *               {@code {"type":"object","properties":{...},"required":[...],
 *               "additionalProperties":false}}. Stored as a deep, unmodifiable copy, so
 *               later mutation of the map you passed in cannot change what is sent.
 *               Values must be JSON-representable: {@code Map}, {@code List},
 *               {@code String}, {@code Boolean}, a standard number type (finite), or
 *               {@code null}
 */
public record OutputSchema(String name, Map<String, Object> schema) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public OutputSchema {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("OutputSchema name must not be blank");
        }
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "OutputSchema name must match [A-Za-z0-9_-]{1,64} (providers enforce this): " + name);
        }
        Objects.requireNonNull(schema, "schema");
        if (schema.isEmpty()) {
            throw new IllegalArgumentException("OutputSchema schema must not be empty");
        }
        schema = copyObject(schema, "schema", 0);
    }

    /**
     * A schema under the given name, forwarded to the provider as written. Prefer
     * {@link #ofProperties} for a plain object; reach for this when you need a shape it
     * cannot express (a {@code $defs} block, an {@code anyOf}, optional fields), and see
     * the class javadoc for what providers accept.
     */
    public static OutputSchema of(String name, Map<String, Object> schema) {
        return new OutputSchema(name, schema);
    }

    /**
     * A convenience for the common case: an object with the given properties, all of them
     * required, and no others permitted. {@code properties} maps each field name to its
     * JSON Schema fragment, e.g. {@code Map.of("type", "string")} — that is, the
     * <em>contents</em> of the schema's {@code properties} block, not a whole schema.
     *
     * <p>Property order is the map's iteration order, and the model tends to fill fields
     * in that order — so if you want it to state its reasoning before its verdict, pass a
     * {@link LinkedHashMap}. {@code Map.of} iterates in an order that varies per JVM run,
     * which also means the emitted schema differs run to run.
     */
    public static OutputSchema ofProperties(String name, Map<String, Object> properties) {
        Objects.requireNonNull(properties, "properties");
        if (properties.isEmpty()) {
            throw new IllegalArgumentException("OutputSchema properties must not be empty");
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        schema.put("required", List.copyOf(properties.keySet()));
        // Mandatory for constrained decoding on both providers; omitting it is a 400.
        schema.put("additionalProperties", false);
        return new OutputSchema(name, schema);
    }

    // --- deep, JSON-validating copy ------------------------------------------

    // Adapters hand the schema straight to a JSON mapper, where a non-JSON value would
    // surface as a mapper-specific error mid-call. Rejecting it here names the offending
    // path instead, and the copy keeps a caller's retained nested maps out of the wire.

    // Deeper than any real schema; a cycle would otherwise recurse until the stack
    // dies with an Error rather than the documented IllegalArgumentException.
    private static final int MAX_DEPTH = 100;

    private static Map<String, Object> copyObject(Map<?, ?> source, String path, int depth) {
        checkDepth(depth, path);
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException(
                        "OutputSchema keys must be strings, but " + path + " has a " + typeOf(key) + " key");
            }
            copy.put(name, copyValue(value, path + "." + name, depth + 1));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static void checkDepth(int depth, String path) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "OutputSchema nesting exceeds " + MAX_DEPTH + " levels at " + path
                            + " (a self-referential map or list would do this)");
        }
    }

    private static Object copyValue(Object value, String path, int depth) {
        return switch (value) {
            case null -> null;
            case Map<?, ?> map -> copyObject(map, path, depth);
            case List<?> list -> {
                checkDepth(depth, path);
                List<Object> copy = new ArrayList<>(list.size());
                for (int i = 0; i < list.size(); i++) {
                    copy.add(copyValue(list.get(i), path + "[" + i + "]", depth + 1));
                }
                yield Collections.unmodifiableList(copy);
            }
            case String s -> s;
            case Boolean b -> b;
            // Deliberately the concrete numeric types rather than Number: a Number is
            // not necessarily JSON-safe (a custom subclass whose toString() is not
            // numeric fails inside the mapper) nor necessarily immutable (an
            // AtomicInteger would let a caller keep changing what is sent).
            case Byte b -> b;
            case Short s -> s;
            case Integer i -> i;
            case Long l -> l;
            case BigInteger i -> i;
            case BigDecimal d -> d;
            case Float f -> finite(f, f.isNaN() || f.isInfinite(), path);
            case Double d -> finite(d, d.isNaN() || d.isInfinite(), path);
            default -> throw new IllegalArgumentException(
                    "OutputSchema values must be JSON-representable (Map, List, String, Boolean, a standard "
                            + "number type or null), but " + path + " is a " + typeOf(value));
        };
    }

    private static Object finite(Number value, boolean nonFinite, String path) {
        if (nonFinite) {
            // JSON has no NaN or Infinity; mappers render them as strings, so the
            // provider sees a string where it expects a number and 400s obscurely.
            throw new IllegalArgumentException(
                    "OutputSchema numbers must be finite, but " + path + " is " + value);
        }
        return value;
    }

    private static String typeOf(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
