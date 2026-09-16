package dev.agentkit.json;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.OutputSchema;
import java.lang.annotation.Annotation;
import java.util.Set;
import java.util.Objects;

/**
 * Asks the model for a Java type and gets one back.
 *
 * <pre>{@code
 * record Sentiment(String label, double confidence) { }
 *
 * Sentiment sentiment = StructuredOutput.generate(llm,
 *         LlmRequest.builder(model).addMessage(Message.user("Classify: " + text)),
 *         Sentiment.class).value();
 * }</pre>
 *
 * <p>{@code OutputSchema} gets you a reply that <em>conforms</em> to a schema; this closes
 * the remaining gap, deriving the schema from the type and parsing the reply back into it.
 * It lives here rather than in {@code agentkit-core} because that module deliberately has
 * no JSON library — a constraint worth keeping, since it is what lets the core be embedded
 * anywhere. The cost is one extra dependency for callers who want typed results, which is
 * the right side of that trade.
 *
 * <p>Parsing is strict: an unknown property fails rather than being dropped, trailing text
 * after the JSON fails rather than being ignored, a float does not quietly truncate into an
 * integer component, and a value must be the JSON <em>type</em> the schema asked for rather
 * than merely convertible to it — {@code "1"} is not an integer and {@code 1} is not a
 * string. Constrained decoding means any of those indicates the schema and the type have
 * drifted apart — or that the schema never reached the model — and being lenient hands you
 * an object with a component left at its default and no sign anything went wrong.
 */
public final class StructuredOutput {

    /**
     * Not shared with an application's own mapper: this one is configured for reading a
     * constrained reply, and inheriting someone's lenient settings would quietly undo that.
     */
    private static final ObjectMapper MAPPER = strictMapper();

    private static ObjectMapper strictMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                // Jackson stops at the end of the first value by default, so a reply that
                // opens with the object and then adds a sentence would parse and look clean.
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                // The schema says "integer"; accepting 2.7 for one would silently truncate it.
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        // Types must match the schema's, not merely be convertible to it. Jackson's default
        // is to coerce across shapes — "1" into an int, 1 into a String — which is the right
        // default for hand-written JSON and the wrong one here: the schema said which shape
        // it wanted, the provider validated against it, so a mismatch means the reply was
        // not schema-constrained after all. Silently coercing hides exactly that.
        // Boolean is absent from these two on purpose: Jackson has no boolean-to-number
        // coercion path, so int <- true already fails with nothing configured.
        mapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Float)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        // Enums are their own logical type, and the worst of the lot: the others coerce a
        // value, this one invents one. An ordinal 0 read into enum Verdict { PASS, FAIL }
        // returns PASS — a confident, plausible, unconstrained answer, which is exactly the
        // failure this whole change exists to surface.
        // Not the coercion config, which closes {"verdict":0} and leaves {"verdict":"0"}
        // mapping to the same constant — a quoted ordinal reads as a legal string right up
        // until Jackson treats it as an index.
        mapper.enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        // An empty string is its own shape, so the String rules above never saw it and ""
        // became null on a required property. Textual is deliberately left out: {"text":""}
        // is a conforming reply and has to keep parsing.
        mapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Float)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        // null is not a type the schema can ask for: every property is required with a
        // concrete type, so a null is a property the model declined to answer. Without this
        // it arrives as a component silently left null — the same under-fill the generator
        // refuses annotations for.
        mapper.setDefaultSetterInfo(JsonSetter.Value.construct(Nulls.FAIL, Nulls.FAIL));
        return mapper;
    }

    /** How much of an unparseable reply to quote back in the failure. */
    private static final int EXCERPT = 500;

    /** Jackson's mapping detail can run to tens of thousands of characters. */
    private static final int DETAIL = 300;

    private StructuredOutput() {
    }

    /**
     * The parsed value and the call that produced it — the stop reason, the usage and any
     * raw text, so a typed helper does not narrow what you can see about the turn.
     */
    public record Structured<T>(T value, LlmResponse response) {
        public Structured {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(response, "response");
        }
    }

    /** The schema {@link #generate} would use for {@code type}. */
    public static OutputSchema schemaFor(Class<?> type) {
        return JsonSchemas.of(type);
    }

    /**
     * As {@link #schemaFor(Class)}, vouching for annotations you have checked do not affect
     * how Jackson reads the type — see {@link JsonSchemas#of(String, Class, Set)}.
     */
    public static OutputSchema schemaFor(Class<?> type, Set<Class<? extends Annotation>> alsoUnderstood) {
        Objects.requireNonNull(type, "type");
        return JsonSchemas.of(type.getSimpleName(), type, alsoUnderstood);
    }

    /**
     * Sets the schema for {@code type} on {@code request}, runs it, and parses the reply.
     *
     * <p>The builder is taken rather than a built request because the schema has to be set
     * before the call, and handing back a request you then have to remember to attach it to
     * is the kind of API that works right up until someone forgets.
     *
     * <p>The builder is copied rather than modified, so a caller can keep one as a template
     * and hand it to several typed calls without the first schema riding along on the rest.
     *
     * @throws LlmException if the model's reply is not valid JSON for {@code type}
     */
    public static <T> Structured<T> generate(LlmClient llm, LlmRequest.Builder request, Class<T> type) {
        Objects.requireNonNull(type, "type");
        return generate(llm, request, JsonSchemas.of(type), type);
    }

    /**
     * As {@link #generate(LlmClient, LlmRequest.Builder, Class)}, with a schema you derived
     * yourself.
     *
     * <p>For the case {@code JsonSchemas.of(name, type, alsoUnderstood)} exists to serve:
     * without this overload a caller who had to vouch for an annotation could not use
     * {@code generate} at all, because it derives the schema itself and would overwrite
     * theirs — leaving them to hand-roll the call and lose the truncation and unanswered-turn
     * diagnostics below, which are the reason to use it.
     *
     * <p>Also the way to derive once and reuse. Nothing checks that {@code schema} describes
     * {@code type}; if you pass a schema for something else, the model will satisfy it and
     * the parse will fail.
     */
    public static <T> Structured<T> generate(LlmClient llm, LlmRequest.Builder request,
                                             OutputSchema schema, Class<T> type) {
        Objects.requireNonNull(llm, "llm");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(type, "type");
        LlmResponse response = llm.generate(request.copy().outputSchema(schema).build());
        String text = response.message().text();
        if (response.stopReason() == LlmStopReason.MAX_TOKENS) {
            // Reaches parse() as "Unexpected end-of-input" otherwise, which reads as a type
            // that has drifted rather than a reply that was cut off mid-object.
            throw new LlmException("The model hit its token limit before finishing the "
                    + type.getSimpleName() + " object, so the reply is truncated rather than "
                    + "wrong. Raise maxTokens. Reply was: " + excerpt(text));
        }
        if (text.isBlank()) {
            // Distinguishable, and worth distinguishing: a tool call or an empty turn is not a
            // reply that failed to match, it is a turn that never answered. Reported as "did
            // not match" it sends you looking at the record.
            throw new LlmException("The model's turn ended with " + response.stopReason()
                    + " and no text to parse as " + type.getSimpleName()
                    + (response.stopReason() == LlmStopReason.TOOL_USE
                            ? ". A tool call has to be executed and the loop continued before a "
                                    + "structured answer is available; use an Agent, or drop the tools."
                            : "."));
        }
        return new Structured<>(parse(text, type), response);
    }

    /**
     * Parses {@code json} as {@code type}.
     *
     * <p>Exposed separately for a reply you already have — a durable run's result, a
     * cached response, a fixture in a test.
     *
     * @throws LlmException if the text is not valid JSON for {@code type}
     */
    public static <T> T parse(String json, Class<T> type) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(type, "type");
        if (json.isBlank()) {
            throw new LlmException("The model returned no text, so there is nothing to parse as "
                    + type.getSimpleName() + "; the schema was probably not applied");
        }
        try {
            T value = MAPPER.readValue(json, type);
            if (value == null) {
                throw new LlmException("The model returned JSON null where a " + type.getSimpleName()
                        + " was required");
            }
            return value;
        } catch (JsonMappingException e) {
            String detail = e.getOriginalMessage();
            if (detail != null && detail.contains("Cannot coerce")) {
                // Jackson's own message ends "but might if coercion using CoercionConfig was
                // enabled", which points at a lever this library holds privately and has
                // decided about. Saying so would be telling the caller to do something they
                // cannot do — every other failure here names an action they can take.
                throw new LlmException("The model's reply had a value of the wrong JSON type for "
                        + type.getSimpleName() + " (" + clamp(withoutCoercionHint(detail), DETAIL)
                        + "), which means it was not schema-constrained. If you are replaying a "
                        + "fixture or a reply captured before the schema was applied, read it with "
                        + "your own ObjectMapper. Reply was: " + excerpt(json), e);
            }
            throw new LlmException("The model's reply did not match " + type.getSimpleName()
                    + " (" + clamp(detail, DETAIL) + "). Reply was: " + excerpt(json), e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Worth separating: unparseable text usually means constrained decoding was not
            // in effect at all — an adapter or a model that ignored the schema — whereas a
            // mapping failure means it was, and the type has drifted from the schema.
            throw new LlmException("The model's reply was not valid JSON, so the schema was probably "
                    + "not applied. Reply was: " + excerpt(json), e);
        }
    }

    private static String excerpt(String text) {
        return clamp(text.strip(), EXCERPT);
    }

    private static String withoutCoercionHint(String detail) {
        int hint = detail.indexOf(" (but might if coercion");
        return hint < 0 ? detail : detail.substring(0, hint);
    }

    private static String clamp(String text, int limit) {
        if (text == null) {
            return "no detail";
        }
        return text.length() <= limit
                ? text
                : text.substring(0, limit) + "… (" + text.length() + " characters)";
    }
}
