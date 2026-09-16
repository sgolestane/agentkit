package dev.agentkit.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.OutputSchema;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StructuredOutputTest {

    record Finding(String title, int severity) { }

    record Review(String label, double confidence, List<Finding> findings) { }

    enum Verdict { PASS, FAIL }

    /** Replies with fixed text and records the request it was given. */
    private static LlmClient replying(String text, AtomicReference<LlmRequest> captured) {
        return request -> {
            captured.set(request);
            return new LlmResponse(new Message(Role.ASSISTANT, List.of(new TextBlock(text))),
                    LlmStopReason.END_TURN, new TokenUsage(12, 34), Optional.empty());
        };
    }

    private static LlmRequest.Builder request() {
        return LlmRequest.builder("m").addMessage(Message.user("review this"));
    }

    @Test
    void aTypedCallSetsTheDerivedSchemaAndParsesTheReplyBack() {
        AtomicReference<LlmRequest> sent = new AtomicReference<>();
        LlmClient llm = replying("""
                {"label":"mixed","confidence":0.8,
                 "findings":[{"title":"off-by-one","severity":2}]}""", sent);

        var structured = StructuredOutput.generate(llm, request(), Review.class);

        assertThat(structured.value()).isEqualTo(
                new Review("mixed", 0.8, List.of(new Finding("off-by-one", 2))));
        // The schema was attached to the call, not left for the caller to remember.
        assertThat(sent.get().outputSchema()).isPresent();
        assertThat(sent.get().outputSchema().orElseThrow().name()).isEqualTo("Review");
    }

    @Test
    void theWholeResponseComesBackNotJustTheValue() {
        // Metering happens in the client, so wrapping it is what makes a structured call
        // visible to UsageMeter. What returning the response buys is the rest of the turn —
        // this call's own usage and stop reason, without a meter and without a second call.
        var structured = StructuredOutput.generate(
                replying("{\"title\":\"x\",\"severity\":1}", new AtomicReference<>()),
                request(), Finding.class);

        assertThat(structured.response().usage()).isEqualTo(new TokenUsage(12, 34));
        assertThat(structured.response().stopReason()).isEqualTo(LlmStopReason.END_TURN);
    }

    @Test
    void theCallersBuilderIsLeftAsTheyHandedItOver() {
        // A builder passed to a helper is usually a template. Setting the schema on it in
        // place would attach Finding's schema to every later call made from the same
        // builder — including ones that wanted no schema at all.
        LlmRequest.Builder template = request();
        AtomicReference<LlmRequest> sent = new AtomicReference<>();

        StructuredOutput.generate(replying("{\"title\":\"x\",\"severity\":1}", sent),
                template, Finding.class);

        assertThat(sent.get().outputSchema()).isPresent();
        assertThat(template.build().outputSchema()).isEmpty();
    }

    @Test
    void aToolCallIsReportedAsAnUnansweredTurnRatherThanAMismatch() {
        LlmClient callingATool = request -> new LlmResponse(
                new Message(Role.ASSISTANT, List.of(ProposedCall.of("id", "search", Map.of()))),
                LlmStopReason.TOOL_USE, new TokenUsage(1, 2), Optional.empty());

        // "did not match Finding" would send you looking at the record, which is fine.
        assertThatThrownBy(() -> StructuredOutput.generate(callingATool, request(), Finding.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("TOOL_USE")
                .hasMessageContaining("has to be executed");
    }

    @Test
    void aTruncatedReplyBlamesTheTokenLimitRatherThanTheRecord() {
        // "Unexpected end-of-input" reads as a type that has drifted, and sends you to the
        // record. The commonest cause is a maxTokens that was too low.
        LlmClient cutOff = request -> new LlmResponse(
                new Message(Role.ASSISTANT, List.of(new TextBlock("{\"title\":\"a very long"))),
                LlmStopReason.MAX_TOKENS, new TokenUsage(1, 2), Optional.empty());

        assertThatThrownBy(() -> StructuredOutput.generate(cutOff, request(), Finding.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("token limit")
                .hasMessageContaining("Raise maxTokens");
    }

    @Test
    void trailingProseAfterTheJsonFailsRatherThanBeingIgnored() {
        // Jackson stops at the end of the first value by default, so this parses cleanly and
        // the give-away that the schema never reached the model is thrown away with it.
        assertThatThrownBy(() -> StructuredOutput.parse(
                "{\"title\":\"x\",\"severity\":1}\n\nLet me know if you'd like more detail!",
                Finding.class))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void aFloatForAnIntegerComponentFailsRatherThanTruncating() {
        assertThatThrownBy(() -> StructuredOutput.parse(
                "{\"title\":\"x\",\"severity\":2.7}", Finding.class))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void aQuotedNumberFailsBecauseTheSchemaSaidInteger() {
        // Jackson's default coerces "1" into an int. That is right for hand-written JSON and
        // wrong here: the provider validated against a schema that said integer, so a string
        // means the reply was not schema-constrained after all.
        assertThatThrownBy(() -> StructuredOutput.parse(
                "{\"title\":\"x\",\"severity\":\"1\"}", Finding.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("wrong JSON type for Finding");
    }

    @Test
    void aNumberForAStringComponentFailsToo() {
        assertThatThrownBy(() -> StructuredOutput.parse(
                "{\"title\":42,\"severity\":1}", Finding.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("wrong JSON type for Finding");
    }

    @Test
    void aQuotedBooleanFails() {
        record Flagged(boolean urgent) { }

        assertThatThrownBy(() -> StructuredOutput.parse("{\"urgent\":\"true\"}", Flagged.class))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void theTighteningDoesNotRejectAnythingAConformingReplyContains() {
        // The risk of tightening coercion is breaking calls that worked. These are all valid
        // instances of the schemas this generator emits, and every one must still parse.
        record Shapes(int i, long l, double d, float f, java.math.BigDecimal dec,
                      java.math.BigInteger big, boolean flag, String text) { }

        record Rich(Verdict verdict, List<Verdict> verdicts, Set<String> tags, int[] counts,
                    Finding nested, List<Finding> findings, String text) { }

        assertThatCode(() -> {
            StructuredOutput.parse("""
                    {"i":1,"l":9007199254740993,"d":2.0,"f":1.5,"dec":3,"big":7,
                     "flag":true,"text":"x"}""", Shapes.class);
            // Exponent notation, whitespace and newlines are all ordinary JSON.
            StructuredOutput.parse("""
                    {"i":1,"l":1000,"d":1e3,"f":2.5e-3,"dec":1.25e2,"big":42,
                     "flag":false,"text":"y"}
                    """, Shapes.class);
            // Boundaries, and a BigInteger past long range in both directions.
            StructuredOutput.parse("""
                    {"i":-2147483648,"l":-9223372036854775808,"d":4.9e-324,"f":-0.0,
                     "dec":1234567890123456789012345678901234567890,
                     "big":-99999999999999999999999999,"flag":true,"text":""}""", Shapes.class);
            // The shapes the tightening could most plausibly have caught by accident: an
            // empty string, an enum, enums in a list, collections, arrays, nested records.
            StructuredOutput.parse("""
                    {"verdict":"FAIL","verdicts":["PASS","FAIL"],"tags":["a","b"],
                     "counts":[1,2,3],"nested":{"title":"t","severity":1},
                     "findings":[{"title":"u","severity":2}],
                     "text":"\\u00e9 \\"quoted\\" \\\\ backslash"}""", Rich.class);
        }).doesNotThrowAnyException();
    }

    @Test
    void anEnumOrdinalFailsRatherThanInventingAVerdict() {
        // The worst of the coercions: the others convert a value, this one makes one up. A
        // judge record reading an unconstrained 0 returns PASS — confident, plausible, and
        // not something the model said.
        record Rated(Verdict verdict) { }

        assertThatThrownBy(() -> StructuredOutput.parse("{\"verdict\":0}", Rated.class))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void anEmptyStringForANumberSaysSoRatherThanBlamingANull() {
        // An empty string is its own coercion shape, so the String rules never saw it and it
        // became null. The null check would catch that now, but would report "Invalid null
        // value" — true of what Jackson ended up with, and silent about what arrived.
        record Boxed(Integer severity) { }

        assertThatThrownBy(() -> StructuredOutput.parse("{\"severity\":\"\"}", Boxed.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("empty String");
        assertThatThrownBy(() -> StructuredOutput.parse(
                "{\"title\":\"x\",\"severity\":\"\"}", Finding.class))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void aCoercionFailureDoesNotTellTheCallerToSetAKnobTheyCannotReach() {
        // Jackson's own message ends "but might if coercion using CoercionConfig was
        // enabled" — a lever this library holds privately and has already decided about.
        assertThatThrownBy(() -> StructuredOutput.parse(
                "{\"title\":\"x\",\"severity\":\"1\"}", Finding.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("wrong JSON type")
                .hasMessageContaining("your own ObjectMapper")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("CoercionConfig"));
    }

    @Test
    void anEnumStillArrivesAsAStringButNotAsAnIndex() {
        // The first half guards nothing on its own: Jackson resolves a valid constant name
        // before consulting coercion config at all, so no mutation of the mapper breaks it.
        // The half worth testing is the quoted ordinal, which reads as a legal string right
        // up until Jackson treats it as an index and hands back a verdict nobody gave.
        record Rated(Verdict verdict) { }

        assertThatCode(() -> StructuredOutput.parse("{\"verdict\":\"PASS\"}", Rated.class))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> StructuredOutput.parse("{\"verdict\":\"0\"}", Rated.class))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void everyCrossShapeRuleIsExercised() {
        // A mutation run found four live rules with no test at all. The components here are
        // boxed on purpose: a primitive double or boolean already refuses a String without
        // any coercion config, so testing through primitives cannot detect whether the rule
        // exists — which is exactly why the first version of this test still passed with
        // two of the rules deleted.
        record Shapes(String text, Integer i, Double d, Boolean flag) { }

        assertThat(List.of(
                "{\"text\":\"x\",\"i\":1,\"d\":\"1.5\",\"flag\":true}",   // Float <- String (boxed)
                "{\"text\":\"x\",\"i\":1,\"d\":1.5,\"flag\":1}",         // Boolean <- Integer (boxed)
                "{\"text\":1.5,\"i\":1,\"d\":1.5,\"flag\":true}",          // Textual <- Float
                "{\"text\":true,\"i\":1,\"d\":1.5,\"flag\":true}",         // Textual <- Boolean
                "{\"text\":\"x\",\"i\":\"1\",\"d\":1.5,\"flag\":true}",   // Integer <- String (boxed)
                "{\"text\":\"x\",\"i\":\"\",\"d\":1.5,\"flag\":true}",    // Integer <- EmptyString
                "{\"text\":\"x\",\"i\":1,\"d\":1.5,\"flag\":\"true\"}",   // Boolean <- String
                "{\"text\":\"x\",\"i\":1,\"d\":1.5,\"flag\":null}"))     // null anywhere
                .allSatisfy(reply -> assertThatThrownBy(() -> StructuredOutput.parse(reply, Shapes.class))
                        .describedAs(reply)
                        .isInstanceOf(LlmException.class));
    }

    @Test
    void aNullForAnyPropertyFailsBecauseEveryPropertyIsRequired() {
        // The schema marks every property required with a concrete type, so a null is a
        // property the model declined to answer — and it used to arrive as a component
        // silently left null, which is the under-fill the generator refuses annotations for.
        record Holder(String text, List<String> tags, Verdict verdict) { }

        assertThat(List.of(
                "{\"text\":null,\"tags\":[],\"verdict\":\"PASS\"}",
                "{\"text\":\"x\",\"tags\":null,\"verdict\":\"PASS\"}",
                "{\"text\":\"x\",\"tags\":[],\"verdict\":null}",
                "{\"text\":\"x\",\"tags\":[\"a\",null],\"verdict\":\"PASS\"}"))
                .allSatisfy(reply -> assertThatThrownBy(() -> StructuredOutput.parse(reply, Holder.class))
                        .describedAs(reply)
                        .isInstanceOf(LlmException.class));
    }

    @Test
    void anEmptyReplySaysTheSchemaWasProbablyNotApplied() {
        assertThatThrownBy(() -> StructuredOutput.parse("   ", Finding.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("no text");
    }

    @Test
    void aFloodOfMappingDetailIsClampedToo() {
        // Jackson quotes the offending content back, so an unknown property whose name is
        // enormous produced a 20,000-character message even though the reply was clamped.
        String hugeName = "k".repeat(20_000);

        assertThatThrownBy(() -> StructuredOutput.parse(
                "{\"title\":\"x\",\"severity\":1,\"" + hugeName + "\":1}", Finding.class))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(1_500));
    }

    @Test
    void anUnknownPropertyFailsRatherThanBeingDropped() {
        // Under constrained decoding an unexpected field means the schema and the type have
        // drifted apart; ignoring it hands back an object with a component left at default.
        assertThatThrownBy(() -> StructuredOutput.parse(
                "{\"title\":\"x\",\"severity\":1,\"extra\":true}", Finding.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("did not match Finding");
    }

    @Test
    void aMissingValueForAPrimitiveFailsRatherThanSilentlyBecomingZero() {
        assertThatThrownBy(() -> StructuredOutput.parse("{\"title\":\"x\",\"severity\":null}", Finding.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("did not match Finding");
    }

    @Test
    void proseInsteadOfJsonSaysTheSchemaWasProbablyNotApplied() {
        // The likeliest cause by far, and worth naming: an adapter or a model that ignored
        // the schema, rather than a type that has drifted.
        assertThatThrownBy(() -> StructuredOutput.parse(
                "Sure! Here is the review you asked for.", Finding.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("not valid JSON")
                .hasMessageContaining("Sure! Here is the review");
    }

    @Test
    void aLongUnparseableReplyIsQuotedButNotDumpedWholesale() {
        String flood = "x".repeat(5_000);

        assertThatThrownBy(() -> StructuredOutput.parse(flood, Finding.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("5000 characters")
                .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(1_000));
    }

    @Test
    void jsonNullIsAFailureRatherThanANullValue() {
        assertThatThrownBy(() -> StructuredOutput.parse("null", Finding.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("JSON null");
    }

    @Test
    void aVouchedSchemaCanReachGenerate() {
        // Without the overload the escape hatch was unreachable from the class most callers
        // use: generate derived its own schema and overwrote whatever the caller had set, so
        // anyone who needed to vouch had to hand-roll the call and lose the truncation and
        // unanswered-turn diagnostics that are the reason to use generate at all.
        AtomicReference<LlmRequest> sent = new AtomicReference<>();
        OutputSchema vouched = StructuredOutput.schemaFor(Finding.class, java.util.Set.of());

        var structured = StructuredOutput.generate(
                replying("{\"title\":\"x\",\"severity\":1}", sent), request(), vouched, Finding.class);

        assertThat(structured.value()).isEqualTo(new Finding("x", 1));
        assertThat(sent.get().outputSchema()).contains(vouched);
    }

    @Test
    void schemaForMatchesWhatGenerateWouldSend() {
        AtomicReference<LlmRequest> sent = new AtomicReference<>();
        StructuredOutput.generate(replying("{\"title\":\"x\",\"severity\":1}", sent),
                request(), Finding.class);

        assertThat(sent.get().outputSchema().orElseThrow())
                .isEqualTo(StructuredOutput.schemaFor(Finding.class));
    }

    @Test
    void theCallersOwnRequestSettingsSurvive() {
        AtomicReference<LlmRequest> sent = new AtomicReference<>();
        StructuredOutput.generate(
                replying("{\"title\":\"x\",\"severity\":1}", sent),
                LlmRequest.builder("claude-opus-4-8")
                        .system("be terse")
                        .maxTokens(256)
                        .addMessage(Message.user("go")),
                Finding.class);

        assertThat(sent.get().model()).isEqualTo("claude-opus-4-8");
        assertThat(sent.get().system()).contains("be terse");
        assertThat(sent.get().maxTokens()).isEqualTo(256);
    }
}
