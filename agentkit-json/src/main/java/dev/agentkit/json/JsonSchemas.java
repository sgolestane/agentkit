package dev.agentkit.json;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dev.agentkit.core.llm.OutputSchema;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Derives an {@link OutputSchema} from a Java type, so a structured call is described by
 * the record you want back rather than by a hand-written map.
 *
 * <pre>{@code
 * record Sentiment(
 *         @JsonPropertyDescription("positive, negative or mixed") String label,
 *         double confidence) { }
 *
 * OutputSchema schema = JsonSchemas.of(Sentiment.class);
 * }</pre>
 *
 * <p>The schema is emitted in the subset providers actually accept for constrained
 * decoding: every object closed with {@code "additionalProperties": false} and every
 * property listed in {@code required}. That is also why this generator is deliberately
 * narrow rather than general-purpose — a full JSON Schema generator happily emits
 * recursion, {@code minimum}, {@code pattern} and optional properties, all of which the
 * providers reject, and you would find out on the first real call. Anything it cannot
 * express in that subset is refused here, naming the offending path.
 *
 * <p>Two rules govern every refusal below, and between them they predict all of it. One:
 * the provider subset cannot express it. Two: <strong>this generator cannot see what
 * Jackson will do when reading</strong> — and where it cannot see, it refuses rather than
 * guesses, because the alternative is a schema the provider validates and Jackson then
 * rejects or silently under-fills. That is the worst failure this can have: it survives
 * every test that does not make a real call.
 *
 * <h2>What is supported</h2>
 *
 * <ul>
 *   <li><strong>Records</strong>, including nested ones — the repo's idiom, and the shape
 *       whose components give reliable property names without a naming convention.</li>
 *   <li><strong>Enums</strong>, as a string with an {@code enum} constraint.</li>
 *   <li>{@code String}, the integral and floating-point types (boxed or not),
 *       {@code BigInteger}, {@code BigDecimal}, {@code boolean}.</li>
 *   <li>{@code List}, {@code Set}, {@code Collection} and arrays of any of the above.</li>
 *   <li>{@code @JsonClassDescription} and {@code @JsonPropertyDescription}, which become
 *       the schema's descriptions — worth writing, since they are the only place the model
 *       learns what a field means.</li>
 *   <li>{@code @JsonProperty("name")}, on a record component or an enum constant, which
 *       renames it in the schema exactly as it does in Jackson.</li>
 * </ul>
 *
 * <h2>What is refused, and why</h2>
 *
 * <ul>
 *   <li><strong>Recursive types.</strong> Constrained decoding does not support them, so a
 *       tree or a linked list has to be flattened.</li>
 *   <li><strong>{@code Optional} and nullable intent.</strong> Every property must be
 *       required, so there is no way to say "may be absent". Model it explicitly — a
 *       {@code String} that may read {@code "unknown"}, or a separate boolean.</li>
 *   <li><strong>{@code Map} and untyped collections.</strong> A map has no fixed property
 *       set, which is exactly what a closed object schema has to state.</li>
 *   <li><strong>Ordinary classes.</strong> Records carry their component names; a bean
 *       would need a getter convention, and guessing one is how you get a schema that
 *       silently does not match what Jackson will parse.</li>
 *   <li><strong>Any Jackson annotation this does not model.</strong> Not a list of the bad
 *       ones — an allowlist of the ones it understands, because a list of the annotations
 *       someone thought of says nothing about the forty they did not, and every hole found
 *       in this generator so far was found by review rather than by use. Jackson has many
 *       ways to change what reading a type means; this models a handful and refuses the
 *       rest, so what it promises is "this schema describes what Jackson will read" rather
 *       than "nothing I have heard of contradicts it". The understood set is listed on
 *       {@code UNDERSTOOD} and is deliberately small.
 *
 *       <p>This refuses harmless annotations too, and knowingly. {@code @JsonRawValue} and
 *       {@code @JsonFilter} only affect writing, but a great many others do not, and telling
 *       them apart is exactly what this cannot do. The trade is deliberate: a refusal costs
 *       you an exception in the first seconds of writing the call, while a wrong acceptance
 *       costs a provider-billed reply that Jackson rejects in production. Where you have
 *       checked an annotation is read-inert, name it in
 *       {@link #of(String, Class, Set)} rather than working around this.</li>
 *   <li><strong>In particular, annotations that rename or hide what this cannot see.</strong>
 *       {@code @JsonNaming} rewrites every property through a strategy, {@code @JsonIgnore}
 *       and {@code @JsonIgnoreProperties}'s ignore-list remove ones the schema would still
 *       mark required, {@code @JsonSetter}'s name renames one and its null policy overrides
 *       the reader's refusal to accept an unanswered one, {@code @JsonFormat} changes the
 *       shape that arrives or widens what is accepted past what the schema allows,
 *       {@code @JsonCreator} on anything but the canonical constructor makes
 *       Jackson read through different parameter names, and {@code @JsonValue} on an enum
 *       substitutes a value only a running instance knows. Each produces a schema the model
 *       satisfies and Jackson then rejects or silently under-fills, which is the worst
 *       failure this can have — it survives every test that does not make a real call.
 *       ({@code @JsonIgnoreProperties(ignoreUnknown = true)} is allowed: it cannot make the
 *       two disagree, though it does waive the strict unknown-property check for that
 *       record.)</li>
 *   <li><strong>{@code char} and {@code Character}.</strong> The schema can say "string" but
 *       not "exactly one character", so the model is free to return a word that Jackson then
 *       refuses. Use a {@code String}, or an enum if the set is closed.</li>
 * </ul>
 */
public final class JsonSchemas {

    /** Deeper than any sane response shape; the guard is really for accidental recursion. */
    private static final int MAX_DEPTH = 20;

    private JsonSchemas() {
    }

    /** Names providers accept for a schema; {@link OutputSchema} enforces the same rule. */
    private static final java.util.regex.Pattern SCHEMA_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** A schema for {@code type}, named after its simple name. */
    public static OutputSchema of(Class<?> type) {
        Objects.requireNonNull(type, "type");
        String name = type.getSimpleName();
        // After the is-a-record check, so a non-record does not get told to pick a better
        // name and then told the name was never the problem.
        if (type.isRecord() && !SCHEMA_NAME.matcher(name).matches()) {
            // Anonymous and some generated types have simple names outside the charset, and
            // the failure otherwise reads as if the caller had chosen the name.
            throw new IllegalArgumentException(
                    "The simple name of " + type.getName() + " (\"" + name + "\") is not a legal "
                            + "schema name; providers accept [A-Za-z0-9_-]{1,64}. "
                            + "Use of(String, Class) to give it one.");
        }
        return of(name.isEmpty() ? "anonymous" : name, type);
    }

    /**
     * A schema for {@code type} under an explicit name — use this when the simple name is
     * not in the {@code [A-Za-z0-9_-]} charset providers require, or when two schemas in
     * one system would otherwise share a name.
     */
    public static OutputSchema of(String name, Class<?> type) {
        return of(name, type, Set.of());
    }

    /** As {@link #of(Class)}, vouching for {@code alsoUnderstood}. */
    public static OutputSchema of(Class<?> type, Set<Class<? extends Annotation>> alsoUnderstood) {
        Objects.requireNonNull(type, "type");
        return of(type.getSimpleName(), type, alsoUnderstood);
    }

    /**
     * A schema for {@code type}, treating {@code alsoUnderstood} as annotations you have
     * checked do not affect how Jackson <em>reads</em> the type.
     *
     * <p>The pressure valve for the allowlist. Many Jackson annotations outside it are
     * harmless <em>as you have configured things</em> — {@code @JsonRootName} changes
     * nothing unless a mapper enables {@code UNWRAP_ROOT_VALUE}, and then Jackson demands a
     * wrapper the schema never described. That conditionality is why it stays refused by
     * default rather than allowlisted: this method is public and cannot see your mapper.
     * ({@code @JsonView} is off the list for a related but sharper reason — it drops a
     * required property <em>silently</em>, where a root-name mismatch fails loudly. And
     * strictly the hazard there is the mapper feature rather than the annotation: under
     * {@code UNWRAP_ROOT_VALUE} the derived schema is wrong for every record, annotated or
     * not.) Refusing
     * everything conditional is the right default and a bad dead end on its own — a record
     * shared between an HTTP response body and a structured call is an ordinary thing to
     * have, and you may not even own it.
     *
     * <p>You have to name each annotation, which is the point. A blanket "trust me" would
     * also switch off {@code @JsonIgnore} and {@code @JsonNaming}, and those refusals are
     * load-bearing — so the ones with a reason of their own still fire even when listed
     * here. What this widens is only the generic "I do not model this" sweep.
     *
     * <p>A vouch applies to the <em>whole derivation</em>, nested records and enums
     * included — not to the type you were looking at when you made it. Naming an annotation
     * you have checked on the root also waives it on everything the root reaches. Keying
     * each vouch to a type would be narrower, and was considered and not done: the type
     * carrying the annotation is often one you did not write and did not name — a nested
     * record from the library the annotation came with — so the precise form would demand
     * knowledge the caller does not have to make a decision they already understand.
     * Narrowing it would need a way to compose a nested schema into a parent, which this
     * does not have.
     *
     * <p>A vouch does <em>not</em> relieve a {@link JacksonAnnotationsInside} bundle, and
     * cannot: the statement it makes is that you have read the annotation and it does not
     * change what is read, which nobody can say about an expansion neither they nor this
     * can see. Allowing it re-opened every refusal the bundle check reaches — those all
     * resolve annotations in a way that does not see meta-annotations, so a {@code
     * @JsonIgnore} inside a vouched bundle silently under-filled a conforming reply.
     *
     * <pre>{@code
     * JsonSchemas.of("Review", Review.class, Set.of(JsonRootName.class));
     * }</pre>
     */
    public static OutputSchema of(String name, Class<?> type,
                                  Set<Class<? extends Annotation>> alsoUnderstood) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(alsoUnderstood, "alsoUnderstood");
        if (!type.isRecord()) {
            throw new IllegalArgumentException(
                    "A structured response must be a record, but " + type.getName() + " is not. "
                            + "A record's components give the property names; a class would need a "
                            + "getter convention this deliberately does not guess at.");
        }
        return OutputSchema.of(name, schemaOf(type, type.getSimpleName(),
                new Derivation(new ArrayDeque<>(), Set.copyOf(alsoUnderstood))));
    }

    /** One derivation's state: what it is inside, and what the caller vouched for. */
    private record Derivation(Deque<Class<?>> visiting,
                              Set<Class<? extends Annotation>> alsoUnderstood) {
    }

    // --- derivation ----------------------------------------------------------

    private static Map<String, Object> schemaOf(Type type, String path, Derivation derivation) {
        if (derivation.visiting.size() > MAX_DEPTH) {
            throw new IllegalArgumentException("Type nesting exceeds " + MAX_DEPTH + " levels at " + path);
        }
        if (type instanceof Class<?> raw) {
            return schemaOfClass(raw, path, derivation);
        }
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            if (Collection.class.isAssignableFrom(raw)) {
                return arrayOf(parameterized.getActualTypeArguments()[0], path, derivation);
            }
            // Everything else falls through to the raw-class rules, so that Map<K,V> and
            // Optional<T> get the explanation they have earned rather than a generic one.
            return schemaOfClass(raw, path, derivation);
        }
        throw refuse(path, type.getTypeName(), "the type could not be resolved");
    }

    private static Map<String, Object> schemaOfClass(Class<?> raw, String path, Derivation derivation) {
        if (raw == String.class || raw == CharSequence.class) {
            return node("string", null);
        }
        if (raw == char.class || raw == Character.class) {
            throw refuse(path, raw.getName(),
                    "the subset cannot say \"exactly one character\", so the model may return a word "
                            + "that Jackson then refuses; use a String, or an enum for a closed set");
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return node("boolean", null);
        }
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
                || raw == short.class || raw == Short.class || raw == byte.class || raw == Byte.class
                || raw == BigInteger.class) {
            return node("integer", null);
        }
        if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class
                || raw == BigDecimal.class) {
            return node("number", null);
        }
        if (raw.isEnum()) {
            return enumSchema(raw, path, derivation);
        }
        if (raw.isArray()) {
            return arrayOf(raw.getComponentType(), path, derivation);
        }
        if (Collection.class.isAssignableFrom(raw)) {
            throw refuse(path, raw.getName(),
                    "a collection must declare its element type, e.g. List<String>");
        }
        if (Map.class.isAssignableFrom(raw)) {
            throw refuse(path, raw.getName(),
                    "a map has no fixed property set, which is what a closed object schema must state; "
                            + "use a record, or a List of key/value records");
        }
        if (raw == java.util.Optional.class) {
            throw refuse(path, raw.getName(),
                    "every property must be required, so optionality cannot be expressed; model it "
                            + "explicitly instead");
        }
        if (raw.isRecord()) {
            return recordSchema(raw, path, derivation);
        }
        throw refuse(path, raw.getName(), "only records, enums, strings, numbers, booleans and "
                + "collections of those can be expressed in the subset providers accept");
    }

    private static Map<String, Object> enumSchema(Class<?> raw, String path, Derivation derivation) {
        // The sweep runs here too. It did not, which made "refuses any Jackson annotation it
        // does not model" false for every enum in the graph — and an enum with a @JsonCreator
        // factory mapping wire codes to constants is the ordinary way to write one, so the
        // schema listed the constant names and Jackson accepted only the codes.
        requireOnlyUnderstood(path, raw.getName(), annotationsAround(raw), derivation);
        for (Method method : raw.getDeclaredMethods()) {
            if (method.isAnnotationPresent(JsonCreator.class)) {
                throw refuse(path, raw.getName(),
                        "@JsonCreator on " + method.getName() + "() means Jackson builds the "
                                + "constant from something other than its name, so the names listed "
                                + "here are not what a reply must use; use @JsonProperty on each "
                                + "constant instead, which this can see");
            }
        }
        for (Field constant : raw.getDeclaredFields()) {
            if (constant.isEnumConstant()) {
                requireOnlyUnderstood(path + "." + constant.getName(), raw.getName(),
                        List.of(constant.getDeclaredAnnotations()), derivation);
            }
        }
        // Not getMethods(): that is public-only, and Jackson honours @JsonValue at any
        // visibility — the idiomatic enum accessor is package-private, since nothing
        // outside the enum calls it. Not getDeclaredMethods() either, which misses an
        // inherited or interface default method. Both, which is what allMethods walks.
        for (Method method : allMethods(raw)) {
            if (method.isAnnotationPresent(JsonValue.class)) {
                throw refuseJsonValue(path, raw, method.getName() + "()");
            }
        }
        for (Field field : raw.getDeclaredFields()) {
            if (field.isAnnotationPresent(JsonValue.class)) {
                throw refuseJsonValue(path, raw, field.getName());
            }
        }
        Map<String, Object> schema = node("string", describe(raw));
        List<String> constants = new java.util.ArrayList<>();
        for (Object constant : raw.getEnumConstants()) {
            String name = ((Enum<?>) constant).name();
            constants.add(renamedBy(constantField(raw, name), name));
        }
        schema.put("enum", List.copyOf(constants));
        return schema;
    }

    private static Map<String, Object> recordSchema(Class<?> raw, String path, Derivation derivation) {
        for (Method method : allMethods(raw)) {
            if (method.isAnnotationPresent(JsonValue.class)) {
                // @JsonValue is in UNDERSTOOD so an enum reaches its own message, which meant
                // it reached no check at all on a record: the schema described an object and
                // Jackson wanted a bare scalar.
                throw refuse(path, raw.getName(),
                        "@JsonValue on " + method.getName() + "() means Jackson reads this record "
                                + "from a single scalar rather than from an object, so the closed "
                                + "object schema derived here is the wrong shape entirely; drop it, "
                                + "or use the component's type directly");
            }
        }
        if (derivation.visiting.contains(raw)) {
            throw refuse(path, raw.getName(),
                    "the type is recursive, and constrained decoding does not support recursion");
        }
        requireOnlyUnderstood(path, raw.getName(), annotationsAround(raw), derivation);
        requireCanonicalCreator(raw, path);
        requireNoDroppedProperties("the record", path, raw,
                inherited(raw, JsonIgnoreProperties.class));
        if (inherited(raw, JsonNaming.class) != null) {
            throw refuse(path, raw.getName(),
                    "@JsonNaming rewrites every property through a strategy this does not apply, so "
                            + "the schema would name properties Jackson will not accept; rename the "
                            + "components, or annotate each with @JsonProperty");
        }
        derivation.visiting.push(raw);
        try {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new java.util.ArrayList<>();
            for (RecordComponent component : raw.getRecordComponents()) {
                String where = path + "." + component.getName();
                requireOnlyUnderstood(where, component.getType().getName(),
                        annotationsOn(component), derivation);
                // Also legal on a component. On a record it is a no-op — Jackson consults a
                // property-level ignoral only for names that are not creator properties, and
                // every record property is one — but it is refused rather than ignored,
                // because writing it means intending something that will not happen. The
                // message says so, and names the site separately from the type whose
                // properties the author expected to lose.
                requireNoDroppedProperties(where, where, component.getType(),
                        annotationOn(component, JsonIgnoreProperties.class), false);
                JsonProperty access = annotationOn(component, JsonProperty.class);
                if (access != null && access.access() == JsonProperty.Access.READ_ONLY) {
                    throw refuse(where, component.getType().getName(),
                            "@JsonProperty(access = READ_ONLY) means Jackson writes it but never "
                                    + "reads it, so the model would be asked for a value that is then "
                                    + "discarded and the component left null; drop the access, or "
                                    + "drop the component");
                }
                JsonIgnore ignored = annotationOn(component, JsonIgnore.class);
                // value(), not presence: @JsonIgnore(false) explicitly means "keep this",
                // and Jackson honours it.
                if (ignored != null && ignored.value()) {
                    throw refuse(where, component.getType().getName(),
                            "@JsonIgnore means Jackson will never fill it, but every property in this "
                                    + "subset is required, so the model would be asked for a value that "
                                    + "is then discarded and the canonical constructor left short");
                }
                Map<String, Object> property = schemaOf(component.getGenericType(), where, derivation);
                String description = describe(component);
                if (description != null) {
                    property.put("description", description);
                }
                String name = renamedBy(annotationOn(component, JsonProperty.class), component.getName());
                if (properties.containsKey(name)) {
                    throw refuse(where, raw.getName(),
                            "two components map to the property \"" + name + "\"; a @JsonProperty rename "
                                    + "has collided with another component");
                }
                properties.put(name, property);
                required.add(name);
            }
            if (properties.isEmpty()) {
                throw refuse(path, raw.getName(), "a record with no components has nothing to ask for");
            }
            Map<String, Object> schema = node("object", describe(raw));
            schema.put("properties", properties);
            schema.put("required", List.copyOf(required));
            // Mandatory for constrained decoding; omitting it is a 400 on both providers.
            schema.put("additionalProperties", false);
            return schema;
        } finally {
            derivation.visiting.pop();
        }
    }

    /**
     * Refuses a named ignore-list, which drops properties the schema would still require.
     *
     * <p>Only the list. {@code ignoreUnknown = true} is a different statement — "tolerate a
     * property I did not declare" — which cannot make the schema disagree with the parser,
     * and is the caller's decision to make about their own type. It does mean an unexpected
     * field is dropped rather than failing, so the strictness described on
     * {@code StructuredOutput} is waived for that record; nothing here overrides it.
     */
    private static void requireNoDroppedProperties(String site, String path, Class<?> raw,
                                                   JsonIgnoreProperties annotation) {
        requireNoDroppedProperties(site, path, raw, annotation, true);
    }

    private static void requireNoDroppedProperties(String site, String path, Class<?> raw,
                                                   JsonIgnoreProperties annotation, boolean onRecord) {
        // allowSetters keeps the list serialization-only, so Jackson still reads every
        // property and the two halves agree. Refusing it would refuse working code.
        if (annotation == null || !raw.isRecord() || annotation.allowSetters()) {
            return;
        }
        Set<String> properties = java.util.Arrays.stream(raw.getRecordComponents())
                .map(RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
        // Intersected with what the record actually has: a list naming nothing real drops
        // nothing, so there is nothing to refuse — and refusing it would have sent a caller
        // who removed the offending component straight back to the same message.
        List<String> dropped = java.util.Arrays.stream(annotation.value())
                .filter(properties::contains).toList();
        if (dropped.isEmpty()) {
            return;
        }
        throw refuse(path, raw.getName(), onRecord
                ? "@JsonIgnoreProperties on " + site + " drops " + dropped + ", but every property "
                        + "in this subset is required, so the model would be asked for values "
                        + "Jackson then discards and those components left null; drop the "
                        + "ignore-list, or hand-write the schema"
                : "@JsonIgnoreProperties on " + site + " names " + dropped + ", which does nothing "
                        + "here — Jackson applies a property-level ignore-list only to names that "
                        + "are not creator properties, and every component of a record is one; move "
                        + "it to " + raw.getSimpleName() + " if you meant to drop those, or remove it");
    }

    /**
     * Refuses a record whose deserialization does not go through its canonical constructor.
     *
     * <p>The third annotation in the family {@code @JsonNaming} and {@code @JsonIgnore}
     * belong to: a {@code @JsonCreator} factory takes its own parameters, with their own
     * {@code @JsonProperty} names, and Jackson reads through it instead. Deriving from the
     * components then names properties the parser will not accept — the same failure, from
     * a different direction.
     */
    private static void requireCanonicalCreator(Class<?> raw, String path) {
        Class<?>[] canonical = java.util.Arrays.stream(raw.getRecordComponents())
                .map(RecordComponent::getType).toArray(Class<?>[]::new);
        for (java.lang.reflect.Constructor<?> constructor : raw.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(JsonCreator.class)
                    && !java.util.Arrays.equals(constructor.getParameterTypes(), canonical)) {
                throw refuseCreator(path, raw, "a constructor");
            }
        }
        for (Method method : raw.getDeclaredMethods()) {
            if (method.isAnnotationPresent(JsonCreator.class)) {
                throw refuseCreator(path, raw, method.getName() + "()");
            }
        }
    }

    private static IllegalArgumentException refuseCreator(String path, Class<?> raw, String where) {
        return refuse(path, raw.getName(),
                "@JsonCreator on " + where + " means Jackson reads through it rather than through "
                        + "the canonical constructor, so its parameter names — not the components' — "
                        + "are what a reply must use; drop the creator, or hand-write the schema");
    }

    private static Map<String, Object> arrayOf(Type element, String path, Derivation derivation) {
        Map<String, Object> schema = node("array", null);
        schema.put("items", schemaOf(element, path + "[]", derivation));
        return schema;
    }

    private static Map<String, Object> node(String type, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        if (description != null) {
            schema.put("description", description);
        }
        return schema;
    }

    private static String describe(Class<?> type) {
        JsonClassDescription annotation = type.getAnnotation(JsonClassDescription.class);
        return annotation == null || annotation.value().isBlank() ? null : annotation.value();
    }

    private static String describe(RecordComponent component) {
        JsonPropertyDescription description = annotationOn(component, JsonPropertyDescription.class);
        return description == null || description.value().isBlank() ? null : description.value();
    }

    /**
     * Every Jackson annotation this generator knows how to account for.
     *
     * <p>An allowlist, not a denylist, and that is the whole point. The refusals below each
     * exist because someone hit the failure — a schema the provider validates and Jackson
     * then rejects or silently under-fills — and a list of the ones we happened to think of
     * says nothing about the forty we did not. Jackson has many ways to change what reading
     * a type means, and this generator models a handful; anything outside that handful is
     * refused, so the promise it makes is "this schema matches what Jackson will read"
     * rather than "no annotation we have heard of contradicts it".
     *
     * <p>Four kinds are in here. Some are <em>honoured</em> — {@code @JsonProperty} renames
     * and the description annotations. {@code @JsonAlias} is merely <em>tolerated</em>: this
     * does nothing with it, and it is safe only because an alias adds an accepted name
     * without removing the declared one. Some
     * are <em>refused with a specific message</em> further down, and are listed here so they
     * reach that message rather than the generic one. The rest are write-side only: they
     * change how a value is serialized, which is not a thing this ever does.
     */
    private static final Set<Class<? extends Annotation>> UNDERSTOOD = Set.of(
            // Honoured.
            JsonProperty.class, JsonPropertyDescription.class, JsonClassDescription.class,
            // Tolerated: additive only, so it cannot contradict the schema.
            JsonAlias.class,
            // Refused below, each with a reason of its own.
            JsonIgnore.class, JsonIgnoreProperties.class, JsonNaming.class, JsonCreator.class,
            JsonValue.class,
            // Write-side only, so they cannot make reading disagree with the schema. Not
            // @JsonView, which was in here on that reasoning and does not belong: views apply
            // to deserialization too, and a reader with an active view silently drops a
            // property the schema still requires. It is safe under StructuredOutput's own
            // mapper, which sets no view — but of() is public and promises more than that.
            JsonInclude.class, JsonAutoDetect.class, JsonPropertyOrder.class,
            JsonSerialize.class, JsonIgnoreType.class,
            // Write-side, each round-tripped against a conforming instance in the tests.
            JsonRawValue.class, JsonAnyGetter.class, JsonFilter.class, JsonTypeName.class);

    /**
     * Refuses any Jackson annotation outside {@link #UNDERSTOOD}.
     *
     * <p>Conservative by design: a refusal is a compile-time-ish error with a message, while
     * the alternative is a schema that looks right and produces a reply Jackson will not
     * accept — found on a real call, against a provider, with tokens already spent.
     */
    private static void requireOnlyUnderstood(String path, String type,
                                              List<Annotation> annotations, Derivation derivation) {
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> kind = annotation.annotationType();
            if (annotation instanceof JsonSetter setter) {
                // Before the vouch, like the five refusals that live outside this method: a
                // vouch says "this does not change what is read", and for these two that
                // statement is false in every configuration that reaches real code.
                throw refuseSetter(path, type, setter);
            }
            if (annotation instanceof JsonFormat format) {
                throw refuseFormat(path, type, format);
            }
            if (kind.isAnnotationPresent(JacksonAnnotationsInside.class)) {
                // A bundle: Jackson expands it into whatever it is meta-annotated with, and
                // the expansion is invisible to every check here — including the five with
                // reasons of their own, so @JsonIgnore hidden inside one used to sail past.
                // The annotation's own package is the caller's, so the prefix test below
                // never had a chance. Refused rather than resolved: expanding bundles would
                // mean teaching every lookup in this class to do it.
                throw refuse(path, type, "@" + kind.getSimpleName() + " is a Jackson annotation "
                        + "bundle (@JacksonAnnotationsInside). Jackson expands it into annotations "
                        + "this generator cannot see, so it cannot tell whether they change what is "
                        + "read; write the annotations directly instead");
            }
            boolean jackson = kind.getName().startsWith("com.fasterxml.jackson.")
                    // Jackson 3 moved the databind annotations here, including @JsonDeserialize
                    // and @JsonNaming. Matching only the old prefix would fail open on upgrade,
                    // and fail open silently, which is the worst way for this to break.
                    || kind.getName().startsWith("tools.jackson.");
            if (!jackson || UNDERSTOOD.contains(kind) || derivation.alsoUnderstood.contains(kind)) {
                continue;
            }
            // Kept to the shape every other refusal in this class uses — one reason, one
            // semicolon, the remedies — because this is the one a caller hits most often and
            // the earlier version put its first actionable word two thirds of the way in.
            throw refuse(path, type, "@" + kind.getSimpleName() + " is a Jackson annotation this "
                    + "generator does not model, so it cannot promise the schema describes what "
                    + "Jackson will read; remove it, or — if you have checked it does not affect "
                    + "reading — name it in of(name, type, alsoUnderstood)");
        }
    }

    /**
     * Refuses {@code @JsonSetter}, with the reason that applies.
     *
     * <p>Every configuration of it changes what is read. {@code value} renames the
     * property, so the schema asks the model for a name Jackson does not read.
     * {@code nulls} and {@code contentNulls} look inert — a schema from this class never
     * permits a null — but they are read-side <em>defaults</em>, and they override the
     * {@code Nulls.FAIL} that {@code StructuredOutput} installs precisely to catch a
     * component left unanswered. They also fire on a <em>missing</em> property, not only
     * an explicit null, and {@code AS_EMPTY} does not tolerate the gap so much as invent a
     * value for it: a component the model declined to answer comes back as {@code ""} or
     * an empty list, indistinguishable from a real answer.
     */
    private static IllegalArgumentException refuseSetter(String path, String type, JsonSetter setter) {
        if (!setter.value().isEmpty()) {
            return refuse(path, type, "@JsonSetter(\"" + setter.value() + "\") renames the property "
                    + "Jackson reads, so the schema would ask the model for a different name; use "
                    + "@JsonProperty, which this generator applies to the schema too");
        }
        return refuse(path, type, "@JsonSetter overrides the Nulls.FAIL policy StructuredOutput "
                + "installs, so a property the model left null — or never sent — becomes a silent "
                + "null or a fabricated empty value instead of a failure; drop it");
    }

    /**
     * Refuses {@code @JsonFormat}, with the reason that applies.
     *
     * <p>{@code shape} says what arrives — {@code NUMBER} on an enum, {@code ARRAY} on a
     * record — and the schema would describe something else. {@code with} and
     * {@code without} carry read-side features that widen what Jackson accepts past what
     * the schema allows: {@code READ_UNKNOWN_ENUM_VALUES_AS_NULL} turns a value outside the
     * schema's enum into a silent null, {@code ACCEPT_SINGLE_VALUE_AS_ARRAY} takes a scalar
     * where the schema says array.
     *
     * <p>That leaves {@code pattern}, {@code locale} and {@code timezone}, which really are
     * read-inert — and unreachable, because they are consumed by date and time
     * deserializers and every date and time type is refused before this runs. So there is
     * no configuration to allow, and the annotation is refused entire.
     */
    private static IllegalArgumentException refuseFormat(String path, String type, JsonFormat format) {
        String detail = format.shape() != JsonFormat.Shape.ANY
                ? "shape = " + format.shape() + " says what arrives, which the schema describes "
                        + "differently"
                : "its read features widen what Jackson accepts — READ_UNKNOWN_ENUM_VALUES_AS_NULL "
                        + "turns a value outside the schema's enum into a silent null, "
                        + "ACCEPT_SINGLE_VALUE_AS_ARRAY takes a scalar where the schema says array";
        return refuse(path, type, "@JsonFormat is refused: " + detail + ". Its read-inert options "
                + "— pattern, locale, timezone — apply only to date and time types, which this "
                + "generator refuses anyway, so no configuration of it is safe here");
    }

    /** Everything Jackson could read off the type itself: the class, its interfaces, its members. */
    private static List<Annotation> annotationsAround(Class<?> raw) {
        List<Annotation> found = new java.util.ArrayList<>();
        for (Class<?> current = raw; current != null && current != Object.class;
                current = current.getSuperclass()) {
            collectTypeAnnotations(current, found);
        }
        Set<String> componentNames = raw.isRecord()
                ? java.util.Arrays.stream(raw.getRecordComponents())
                        .map(RecordComponent::getName).collect(java.util.stream.Collectors.toSet())
                : Set.of();
        for (Method method : allMethods(raw)) {
            // Component accessors are skipped here and scanned per component, so an
            // annotation written in the record header — which lands on the accessor, the
            // field and the constructor parameter — names the component that carries it.
            // Matched by name rather than identity, because the accessor a component
            // reports is the record's own and an interface may declare the same method.
            if (method.getParameterCount() > 0 || !componentNames.contains(method.getName())) {
                found.addAll(List.of(method.getDeclaredAnnotations()));
            }
        }
        for (java.lang.reflect.Constructor<?> constructor : raw.getDeclaredConstructors()) {
            found.addAll(List.of(constructor.getDeclaredAnnotations()));
        }
        // Fields and constructor parameters are deliberately left to annotationsOn, so an
        // annotation written in the record header — which lands on both — is reported
        // against the component that carries it rather than against the whole record.
        return found;
    }

    private static void collectTypeAnnotations(Class<?> raw, List<Annotation> into) {
        into.addAll(List.of(raw.getDeclaredAnnotations()));
        for (Class<?> face : raw.getInterfaces()) {
            collectTypeAnnotations(face, into);
        }
    }

    /**
     * Every method the type declares or inherits, at every visibility.
     *
     * <p>{@code getMethods()} is public-only and {@code getDeclaredMethods()} stops at the
     * class, and Jackson reads annotations from members neither of those returns — a
     * package-private {@code @JsonValue} accessor, which is the idiomatic one, or an
     * interface default method.
     */
    private static List<Method> allMethods(Class<?> raw) {
        List<Method> found = new java.util.ArrayList<>();
        for (Class<?> current = raw; current != null && current != Object.class;
                current = current.getSuperclass()) {
            found.addAll(List.of(current.getDeclaredMethods()));
            collectInterfaceMethods(current, found);
        }
        return found;
    }

    private static void collectInterfaceMethods(Class<?> raw, List<Method> into) {
        for (Class<?> face : raw.getInterfaces()) {
            into.addAll(List.of(face.getDeclaredMethods()));
            collectInterfaceMethods(face, into);
        }
    }

    /**
     * Every method an accessor overrides, up the interface hierarchy.
     *
     * <p>Jackson resolves a property's annotations from the methods it overrides, so a
     * sealed interface declaring {@code String title();} with an annotation on it is in
     * force there — and was invisible here, which is the same blind spot the type-level
     * walk already fixed one level up.
     */
    private static List<Method> overridden(Class<?> raw, String name) {
        List<Method> found = new java.util.ArrayList<>();
        for (Class<?> face : raw.getInterfaces()) {
            for (Method method : face.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) {
                    found.add(method);
                }
            }
            found.addAll(overridden(face, name));
        }
        Class<?> parent = raw.getSuperclass();
        if (parent == null || parent == Object.class || parent == Record.class
                || parent == Enum.class) {
            return found;
        }
        found.addAll(overridden(parent, name));
        return found;
    }

    /** Everywhere an annotation written on a record component can land. */
    private static List<Annotation> annotationsOn(RecordComponent component) {
        List<Annotation> found = new java.util.ArrayList<>(List.of(component.getDeclaredAnnotations()));
        found.addAll(List.of(component.getAccessor().getDeclaredAnnotations()));
        for (Method method : overridden(component.getDeclaringRecord(), component.getName())) {
            found.addAll(List.of(method.getDeclaredAnnotations()));
        }
        try {
            found.addAll(List.of(component.getDeclaringRecord()
                    .getDeclaredField(component.getName()).getDeclaredAnnotations()));
        } catch (NoSuchFieldException | RuntimeException e) {
            // No field to read; the parameter below still covers the header.
        }
        java.lang.reflect.Parameter parameter = canonicalParameter(component);
        if (parameter != null) {
            found.addAll(List.of(parameter.getDeclaredAnnotations()));
        }
        return found;
    }

    private static java.lang.reflect.Parameter canonicalParameter(RecordComponent component) {
        RecordComponent[] components = component.getDeclaringRecord().getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        int index = -1;
        for (int i = 0; i < components.length; i++) {
            parameterTypes[i] = components[i].getType();
            if (components[i].getName().equals(component.getName())) {
                index = i;
            }
        }
        try {
            return component.getDeclaringRecord()
                    .getDeclaredConstructor(parameterTypes).getParameters()[index];
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    /**
     * A class annotation as Jackson resolves it — across superclasses and interfaces.
     *
     * <p>{@code Class.getAnnotation} sees only what is declared on the class itself, and a
     * record's one inheritance path is an interface, which is where a sealed hierarchy or a
     * marker puts shared Jackson configuration. Reading only the declared annotation meant an
     * ignore-list or a naming strategy inherited from an interface was invisible here and
     * fully in force in Jackson.
     */
    private static <A extends Annotation> A inherited(Class<?> raw, Class<A> type) {
        for (Class<?> current = raw; current != null && current != Object.class;
                current = current.getSuperclass()) {
            A found = onTypeOrInterfaces(current, type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static <A extends Annotation> A onTypeOrInterfaces(Class<?> raw, Class<A> type) {
        A declared = raw.getDeclaredAnnotation(type);
        if (declared != null) {
            return declared;
        }
        for (Class<?> face : raw.getInterfaces()) {
            A found = onTypeOrInterfaces(face, type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The annotation as Jackson would see it.
     *
     * <p>An annotation written on a record component lands on whichever of the record
     * component, the field, the accessor and the constructor parameter its {@code @Target}
     * allows — {@code @JsonProperty} names none of them {@code RECORD_COMPONENT}, so reading
     * only {@code RecordComponent.getAnnotation} finds nothing and the derivation silently
     * disagrees with the parser. Jackson looks in all four; so does this.
     */
    private static <A extends Annotation> A annotationOn(RecordComponent component, Class<A> type) {
        A onComponent = component.getAnnotation(type);
        if (onComponent != null) {
            return onComponent;
        }
        // The canonical constructor parameter outranks the accessor, because that is what
        // Jackson deserializes through. A record annotating both differently is pathological,
        // but Jackson is itself asymmetric there — it writes the accessor's name and reads
        // the parameter's — and this has to agree with the reading half.
        A onParameter = onCanonicalParameter(component, type);
        if (onParameter != null) {
            return onParameter;
        }
        A onAccessor = component.getAccessor().getAnnotation(type);
        if (onAccessor != null) {
            return onAccessor;
        }
        for (Method method : overridden(component.getDeclaringRecord(), component.getName())) {
            A onOverridden = method.getAnnotation(type);
            if (onOverridden != null) {
                return onOverridden;
            }
        }
        try {
            return component.getDeclaringRecord()
                    .getDeclaredField(component.getName()).getAnnotation(type);
        } catch (NoSuchFieldException | RuntimeException e) {
            return null;
        }
    }

    private static <A extends Annotation> A onCanonicalParameter(RecordComponent component, Class<A> type) {
        java.lang.reflect.Parameter parameter = canonicalParameter(component);
        return parameter == null ? null : parameter.getAnnotation(type);
    }

    private static Field constantField(Class<?> enumType, String name) {
        try {
            return enumType.getDeclaredField(name);
        } catch (NoSuchFieldException | RuntimeException e) {
            return null;
        }
    }

    /** {@code @JsonProperty}'s name, or {@code fallback} when it is absent or left empty. */
    private static String renamedBy(JsonProperty annotation, String fallback) {
        return annotation == null || annotation.value().isEmpty() ? fallback : annotation.value();
    }

    private static String renamedBy(Field constant, String fallback) {
        return constant == null ? fallback : renamedBy(constant.getAnnotation(JsonProperty.class), fallback);
    }

    private static IllegalArgumentException refuseJsonValue(String path, Class<?> raw, String where) {
        return refuse(path, raw.getName(),
                "@JsonValue on " + where + " substitutes a value only a running instance knows, so "
                        + "the constants listed here would not be the ones Jackson accepts; use "
                        + "@JsonProperty on each constant instead");
    }

    private static IllegalArgumentException refuse(String path, String type, String why) {
        return new IllegalArgumentException(
                "Cannot derive a schema for " + path + " (" + type + "): " + why);
    }
}
