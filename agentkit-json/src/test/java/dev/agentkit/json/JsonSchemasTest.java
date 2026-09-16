package dev.agentkit.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dev.agentkit.core.llm.OutputSchema;
import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JsonSchemasTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    enum Verdict { PASS, FAIL }

    record Finding(String title, int severity) { }

    enum Grade { @JsonProperty("green") GREEN, AMBER }

    record Rated(Grade rating) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record SnakeCased(String issueTitle) { }

    enum Coded {
        A, B;

        @JsonValue
        public String code() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    record Scored(Coded code) { }

    @JsonIgnoreProperties("severity")
    record WithIgnoreList(String title, int severity) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Tolerant(String title, int severity) { }

    @JsonIgnoreProperties("note")
    interface DropsNote { }

    record ViaInterface(String title, String note) implements DropsNote { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    interface SnakeCasedByInterface { }

    record SnakeViaInterface(String longTitle) implements SnakeCasedByInterface { }

    @JsonIgnoreProperties(value = "severity", allowSetters = true)
    record WriteOnlyList(String title, int severity) { }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(@JsonSubTypes.Type(value = Square.class, name = "square"))
    record Shape(int sides) { }

    record Square(int sides) { }

    @JsonDeserialize(using = com.fasterxml.jackson.databind.deser.std.StringDeserializer.class)
    record CustomRead(String value) { }

    // One fixture per allowlisted annotation; see ALLOWLIST_FIXTURES.
    record PropertyRenamed(@JsonProperty("issue_title") String title, int severity) { }

    record Described(@JsonPropertyDescription("the title") String title) { }

    @JsonClassDescription("a described class")
    record ClassDescribed(String title) { }

    record Aliased(@JsonAlias("alias_title") String title) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Included(String title) { }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE,
            creatorVisibility = JsonAutoDetect.Visibility.NONE)
    record AutoDetected(String title) { }

    @JsonPropertyOrder({"severity", "title"})
    record Ordered(String title, int severity) { }

    record Serialized(@JsonSerialize(as = Integer.class) int severity) { }

    @JsonIgnoreType
    record IgnoredType(String title) { }

    record HoldsIgnoredType(IgnoredType leaf) { }

    record Raw(@JsonRawValue String payload) { }

    record AnyGetter(String title) {
        @JsonAnyGetter
        Map<String, Object> extras() {
            return Map.of();
        }
    }

    @JsonFilter("public")
    record Filtered(String title) { }

    @JsonTypeName("type-named")
    record TypeNamed(String title) { }


    @JacksonAnnotationsInside
    @JsonProperty("wire_title")
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface Wire { }

    @JsonFormat(shape = JsonFormat.Shape.OBJECT)
    enum Unmodelled { RED, GREEN }

    enum CodedEnum {
        RED, GREEN;

        @JsonCreator
        static CodedEnum from(String wire) {
            return wire.equals("r") ? RED : GREEN;
        }
    }

    interface HasTitle {
        @JsonDeserialize(converter = Shout.class)
        String title();
    }

    record Titled(String title) implements HasTitle { }

    class Shout extends com.fasterxml.jackson.databind.util.StdConverter<String, String> {
        @Override
        public String convert(String value) {
            return value.toUpperCase(java.util.Locale.ROOT);
        }
    }

    @JsonRootName("rooted")
    interface IsRooted { }

    record Rooted(String title) implements IsRooted { }

    @JsonRootName("vouched")
    record Vouched(String title) { }

    record HasIgnore(String title, @JsonIgnore int severity) { }

    enum Severity {
        P1, P2, P3;

        // Package-private on purpose: this is how the idiom is actually written.
        @JsonValue
        String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    interface Scalar {
        @JsonValue
        default String value() {
            return toString();
        }
    }

    record ScalarRecord(String value) implements Scalar { }

    interface RenamesIface {
        @JsonProperty("i")
        String title();
    }

    record ViaRename(String title) implements RenamesIface { }

    interface Wires {
        @JsonValue
        String wire();
    }

    enum Overriding implements Wires {
        RED, GREEN;

        @Override
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    interface HasAny {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        default void put(String key, Object value) {
        }
    }

    record WithAnySetter(String title) implements HasAny { }

    enum ValueOnField {
        RED("r"), GREEN("g");

        @JsonValue
        private final String code;

        ValueOnField(String code) {
            this.code = code;
        }
    }

    interface Wired {
        @JsonValue
        default String wire() {
            return toString().toLowerCase(java.util.Locale.ROOT);
        }
    }

    enum ValueInherited implements Wired { RED, GREEN }

    record ViaFactory(String title, int severity) {
        @JsonCreator
        static ViaFactory make(@JsonProperty("x") String title, @JsonProperty("y") int severity) {
            return new ViaFactory(title, severity);
        }
    }

    record Disagreeing(String title, int severity) {
        Disagreeing(@JsonProperty("from_ctor") String title, int severity) {
            this.title = title;
            this.severity = severity;
        }

        @Override
        @JsonProperty("from_accessor")
        public String title() {
            return title;
        }
    }

    @JsonClassDescription("A review of one file")
    record Review(
            @JsonPropertyDescription("positive, negative or mixed") String label,
            double confidence,
            Verdict verdict,
            List<Finding> findings) { }

    private static com.fasterxml.jackson.databind.JsonNode json(OutputSchema schema) {
        return MAPPER.valueToTree(schema.schema());
    }

    @Test
    void aRecordBecomesAClosedObjectWithEveryComponentRequired() throws Exception {
        // The exact shape both providers demand: every object closed, everything required.
        assertThat(json(JsonSchemas.of(Finding.class))).isEqualTo(MAPPER.readTree("""
                {"type":"object",
                 "properties":{"title":{"type":"string"},"severity":{"type":"integer"}},
                 "required":["title","severity"],
                 "additionalProperties":false}"""));
    }

    @Test
    void theSchemaIsNamedAfterTheTypeUnlessToldOtherwise() {
        assertThat(JsonSchemas.of(Finding.class).name()).isEqualTo("Finding");
        assertThat(JsonSchemas.of("review_result", Finding.class).name()).isEqualTo("review_result");
    }

    @Test
    void nestedRecordsEnumsAndListsAllSurvive() throws Exception {
        assertThat(json(JsonSchemas.of(Review.class))).isEqualTo(MAPPER.readTree("""
                {"type":"object",
                 "description":"A review of one file",
                 "properties":{
                   "label":{"type":"string","description":"positive, negative or mixed"},
                   "confidence":{"type":"number"},
                   "verdict":{"type":"string","enum":["PASS","FAIL"]},
                   "findings":{"type":"array","items":{
                     "type":"object",
                     "properties":{"title":{"type":"string"},"severity":{"type":"integer"}},
                     "required":["title","severity"],
                     "additionalProperties":false}}},
                 "required":["label","confidence","verdict","findings"],
                 "additionalProperties":false}"""));
    }

    @Test
    void descriptionsAreCarriedBecauseTheyAreTheOnlyPlaceTheModelLearnsWhatAFieldMeans() {
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) JsonSchemas.of(Review.class).schema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> label = (Map<String, Object>) properties.get("label");

        assertThat(label).containsEntry("description", "positive, negative or mixed");
        assertThat(JsonSchemas.of(Review.class).schema())
                .containsEntry("description", "A review of one file");
    }

    @Test
    void theNumericTypesLandOnTheRightJsonType() throws Exception {
        record Numbers(int i, long l, short s, byte b, java.math.BigInteger big,
                       double d, float f, BigDecimal dec, boolean flag) { }

        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) JsonSchemas.of(Numbers.class).schema().get("properties");

        assertThat(properties).allSatisfy((name, schema) -> {
            String type = (String) ((Map<?, ?>) schema).get("type");
            String expected = switch (name) {
                case "i", "l", "s", "b", "big" -> "integer";
                case "d", "f", "dec" -> "number";
                default -> "boolean";
            };
            assertThat(type).describedAs(name).isEqualTo(expected);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void setsAndArraysAreArraysToo() {
        record Bag(Set<String> tags, int[] counts) { }

        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) JsonSchemas.of(Bag.class).schema().get("properties");

        assertThat((Map<String, Object>) properties.get("tags")).containsEntry("type", "array");
        assertThat((Map<String, Object>) properties.get("counts")).containsEntry("type", "array");
    }

    // --- what it refuses, and why ---------------------------------------------

    @Test
    void aRecursiveTypeIsRefusedRatherThanEmittedForAProviderToReject() {
        record Node(String name, List<Node> children) { }

        assertThatThrownBy(() -> JsonSchemas.of(Node.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recursive");
    }

    @Test
    void optionalIsRefusedBecauseEveryPropertyMustBeRequired() {
        record Maybe(String name, Optional<String> nickname) { }

        assertThatThrownBy(() -> JsonSchemas.of(Maybe.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("optionality");
    }

    @Test
    void aMapIsRefusedBecauseAClosedObjectMustNameItsProperties() {
        record Loose(Map<String, String> attributes) { }

        assertThatThrownBy(() -> JsonSchemas.of(Loose.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no fixed property set");
    }

    @Test
    void aRawCollectionIsRefusedRatherThanGuessedAt() {
        record Raw(@SuppressWarnings("rawtypes") List items) { }

        assertThatThrownBy(() -> JsonSchemas.of(Raw.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("element type");
    }

    @Test
    void anOrdinaryClassIsRefusedRatherThanHavingAGetterConventionGuessed() {
        assertThatThrownBy(() -> JsonSchemas.of(StringBuilder.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a record");
    }

    @Test
    void anUnsupportedComponentTypeNamesThePathThatCausedIt() {
        record Timed(String label, java.time.Instant when) { }

        assertThatThrownBy(() -> JsonSchemas.of(Timed.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Timed.when")
                .hasMessageContaining("Instant");
    }

    @Test
    void anEmptyRecordIsRefusedBecauseThereIsNothingToAskFor() {
        record Nothing() { }

        assertThatThrownBy(() -> JsonSchemas.of(Nothing.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing to ask for");
    }

    @Test
    void charIsRefusedBecauseTheSubsetCannotSayExactlyOneCharacter() {
        record Graded(char grade) { }

        // "string" would let the model answer "excellent", which Jackson then refuses — a
        // schema the provider accepts and the parser does not is the worst failure here.
        assertThatThrownBy(() -> JsonSchemas.of(Graded.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one character");
    }

    // --- Jackson annotations that move the goalposts -----------------------------

    @Test
    void aRenamedComponentIsRenamedInTheSchemaToo() throws Exception {
        record Renamed(@JsonProperty("issue_title") String title, int severity) { }

        // Jackson will parse "issue_title", so the schema has to ask for it. Emitting
        // "title" would produce a reply the provider validated and Jackson then rejected.
        assertThat(json(JsonSchemas.of(Renamed.class))).isEqualTo(MAPPER.readTree("""
                {"type":"object",
                 "properties":{"issue_title":{"type":"string"},"severity":{"type":"integer"}},
                 "required":["issue_title","severity"],
                 "additionalProperties":false}"""));

        assertThat(MAPPER.readValue("{\"issue_title\":\"x\",\"severity\":1}", Renamed.class))
                .isEqualTo(new Renamed("x", 1));
    }

    @Test
    void aRenamedEnumConstantIsRenamedInTheSchemaToo() throws Exception {
        assertThat(json(JsonSchemas.of(Rated.class))).isEqualTo(MAPPER.readTree("""
                {"type":"object",
                 "properties":{"rating":{"type":"string","enum":["green","AMBER"]}},
                 "required":["rating"],
                 "additionalProperties":false}"""));

        assertThat(MAPPER.readValue("{\"rating\":\"green\"}", Rated.class))
                .isEqualTo(new Rated(Grade.GREEN));
    }

    @Test
    void jsonIgnoreIsRefusedBecauseEveryPropertyIsRequired() {
        record Hidden(String title, @JsonIgnore int severity) { }

        assertThatThrownBy(() -> JsonSchemas.of(Hidden.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Hidden.severity")
                .hasMessageContaining("@JsonIgnore");
    }

    @Test
    void jsonNamingIsRefusedBecauseTheStrategyIsNotApplied() {
        assertThatThrownBy(() -> JsonSchemas.of(SnakeCased.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonNaming");
    }

    @Test
    void jsonValueOnAnEnumIsRefusedBecauseTheConstantsWouldNotBeTheAcceptedValues() {
        assertThatThrownBy(() -> JsonSchemas.of(Scored.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonValue");
    }

    @Test
    void aRenameThatCollidesWithAnotherComponentIsRefused() {
        record Collided(@JsonProperty("severity") String title, int severity) { }

        assertThatThrownBy(() -> JsonSchemas.of(Collided.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collided");
    }

    @Test
    void aSimpleNameOutsideTheProviderCharsetPointsAtTheOverload() {
        // An anonymous or generated type has a simple name providers reject, and the
        // failure otherwise reads as if the caller had chosen it.
        record Fine(String a) { }
        Class<?> anonymous = new Object() { }.getClass();

        assertThat(JsonSchemas.of(Fine.class).name()).isEqualTo("Fine");
        // A record whose simple name providers reject is the case this is for; an
        // anonymous class is refused earlier, for not being a record at all.
        assertThatThrownBy(() -> JsonSchemas.of(anonymous))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonValueOnAnEnumFieldIsRefusedToo() {
        // @JsonValue's @Target includes FIELD, and looking only at methods missed it — the
        // schema said "RED" and Jackson accepted only "r". Exactly the failure this whole
        // set of refusals exists to prevent, from the placement nobody checks.
        record Scored(ValueOnField code) { }

        assertThatThrownBy(() -> JsonSchemas.of(Scored.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonValue");
    }

    @Test
    void jsonValueOnAnInheritedMethodIsRefusedToo() {
        // getDeclaredMethods() does not see an interface default method; Jackson does.
        record Scored(ValueInherited code) { }

        assertThatThrownBy(() -> JsonSchemas.of(Scored.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonValue");
    }

    @Test
    void jsonCreatorIsRefusedBecauseJacksonWouldReadThroughItInstead() {
        assertThatThrownBy(() -> JsonSchemas.of(ViaFactory.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonCreator");
    }

    @Test
    void aCompactConstructorIsNotMistakenForACreator() {
        // The canonical constructor is allowed to carry @JsonCreator; only a different one
        // changes what Jackson reads. Refusing this would break the ordinary record.
        record Validated(String title, int severity) {
            Validated {
                if (severity < 0) {
                    throw new IllegalArgumentException("severity");
                }
            }
        }

        assertThat(JsonSchemas.of(Validated.class).schema()).containsKey("properties");
    }

    @Test
    void jsonIgnoreFalseMeansKeepItAndIsNotRefused() {
        // Presence was tested rather than value, so the annotation that explicitly says
        // "do not ignore this" was read as "ignore this".
        record IgnoreFalse(String title, @JsonIgnore(false) int severity) { }

        assertThat(JsonSchemas.of(IgnoreFalse.class).schema())
                .extracting("required").asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactly("title", "severity");
    }

    @Test
    void aCharSequenceComponentStillWorks() {
        // Narrowing the string branch to String alone took CharSequence with it, though
        // Jackson fills it happily. A regression, not a decision.
        record Holds(CharSequence text) { }

        assertThat(JsonSchemas.of(Holds.class).schema()).containsKey("properties");
    }

    @Test
    void theCanonicalConstructorParameterWinsOverTheAccessor() throws Exception {
        // Jackson is asymmetric here — it writes the accessor's name and reads the
        // parameter's — and a schema describes what will be read.
        assertThat(json(JsonSchemas.of(Disagreeing.class))).isEqualTo(MAPPER.readTree("""
                {"type":"object",
                 "properties":{"from_ctor":{"type":"string"},"severity":{"type":"integer"}},
                 "required":["from_ctor","severity"],
                 "additionalProperties":false}"""));

        assertThat(MAPPER.readValue("{\"from_ctor\":\"x\",\"severity\":1}", Disagreeing.class))
                .isEqualTo(new Disagreeing("x", 1));
    }

    @Test
    void aNonRecordIsToldItIsNotARecordRatherThanToPickABetterName() {
        // The name pre-check used to run first, so an anonymous class was told to call
        // of(String, Class) — and doing so then said the name was never the problem.
        Class<?> anonymous = new Object() { }.getClass();

        assertThatThrownBy(() -> JsonSchemas.of(anonymous))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a record");
    }

    @Test
    void anIgnoreListIsRefusedBecauseTheSchemaWouldStillRequireWhatJacksonDrops() {
        // The failure @JsonIgnore is refused for, reached by a different annotation: the
        // model is asked for "severity", Jackson discards it, and the component comes back
        // null with no error anywhere.
        assertThatThrownBy(() -> JsonSchemas.of(WithIgnoreList.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonIgnoreProperties")
                .hasMessageContaining("severity");
    }

    @Test
    void anIgnoreListOnAComponentIsRefusedToo() {
        // Legal there as well, where it filters the nested record's properties instead.
        record Outer(@JsonIgnoreProperties("severity") Finding finding) { }

        assertThatThrownBy(() -> JsonSchemas.of(Outer.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Outer.finding")
                .hasMessageContaining("@JsonIgnoreProperties");
    }

    @Test
    void ignoreUnknownAloneIsAllowedBecauseItCannotMakeTheTwoDisagree() throws Exception {
        // A different statement — "tolerate a field I did not declare" — and the caller's
        // decision to make about their own type. Refusing it would be refusing something
        // that works.
        assertThat(json(JsonSchemas.of(Tolerant.class))).isEqualTo(MAPPER.readTree("""
                {"type":"object",
                 "properties":{"title":{"type":"string"},"severity":{"type":"integer"}},
                 "required":["title","severity"],
                 "additionalProperties":false}"""));
    }

    @Test
    void anIgnoreListNamingNothingRealIsNotRefused() {
        // It drops nothing, so there is nothing to refuse — and refusing it sent a caller who
        // had just removed the offending component straight back to the same message.
        @JsonIgnoreProperties("no_such_property")
        record Inert(String title, int severity) { }

        assertThat(JsonSchemas.of(Inert.class).schema()).containsKey("properties");
    }

    @Test
    void theRefusalOnARecordSaysWhatIsDroppedAndHow() {
        assertThatThrownBy(() -> JsonSchemas.of(WithIgnoreList.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("on the record")
                .hasMessageContaining("drops [severity]")
                .hasMessageContaining("hand-write the schema");
    }

    @Test
    void theRefusalOnAComponentSaysItWouldNotHaveWorkedAnyway() {
        // On a record it is a no-op: Jackson consults a property-level ignore-list only for
        // names that are not creator properties, and every record component is one. So the
        // author intended something that would not have happened, and saying "your data
        // will be dropped" — as this used to — is the opposite of what occurs.
        record Outer(@JsonIgnoreProperties("severity") Finding finding) { }

        assertThatThrownBy(() -> JsonSchemas.of(Outer.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("on Outer.finding")
                .hasMessageContaining("does nothing here")
                .hasMessageContaining("move it to Finding");
    }

    @Test
    void readOnlyAccessIsRefusedBecauseJacksonNeverReadsIt() {
        // The same silent under-fill the ignore-list is refused for, reached through the
        // annotation this generator already parses for its name.
        record Silent(String title, @JsonProperty(access = JsonProperty.Access.READ_ONLY) String note) { }

        assertThatThrownBy(() -> JsonSchemas.of(Silent.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("READ_ONLY")
                .hasMessageContaining("Silent.note");
    }

    @Test
    void anIgnoreListInheritedFromAnInterfaceIsSeenToo() {
        // A record's one inheritance path is an interface, which is where a sealed hierarchy
        // or a marker puts shared Jackson configuration. Class.getAnnotation does not see it
        // and Jackson does, so the list was invisible here and fully in force there.
        assertThatThrownBy(() -> JsonSchemas.of(ViaInterface.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonIgnoreProperties");
    }

    @Test
    void aNamingStrategyInheritedFromAnInterfaceIsSeenToo() {
        assertThatThrownBy(() -> JsonSchemas.of(SnakeViaInterface.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonNaming");
    }

    @Test
    void allowSettersKeepsTheListSerializationOnlySoItIsNotRefused() throws Exception {
        // Jackson still reads every property, so the schema and the parser agree. Refusing
        // it would be refusing working code, which is the worse mistake of the two.
        assertThat(JsonSchemas.of(WriteOnlyList.class).schema()).containsKey("properties");
        assertThat(MAPPER.readValue("{\"title\":\"t\",\"severity\":1}", WriteOnlyList.class))
                .isEqualTo(new WriteOnlyList("t", 1));
    }

    // --- the allowlist ---------------------------------------------------------

    @Test
    void aJacksonAnnotationTheGeneratorDoesNotModelIsRefused() {
        // Each of these makes Jackson read the type differently, and a denylist of the ones
        // anyone happened to think of said nothing about them. They were all found by
        // review rather than by use, which is the argument for inverting it.
        record Renamed(@JsonSetter("wire_title") String title) { }
        @JsonFormat(shape = JsonFormat.Shape.ARRAY)
        record AsArray(String title, int severity) { }
        record Injected(@JacksonInject String title, int severity) { }

        assertThatThrownBy(() -> JsonSchemas.of(Renamed.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonSetter")
                // Its own message now: renaming is the one thing @JsonSetter does that the
                // schema cannot describe, and saying so beats "not modelled".
                .hasMessageContaining("renames the property");
        assertThatThrownBy(() -> JsonSchemas.of(AsArray.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonFormat");
        assertThatThrownBy(() -> JsonSchemas.of(Injected.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JacksonInject");
    }

    @Test
    void anUnmodelledAnnotationOnANestedTypeIsRefusedToo() {
        record HoldsPolymorphic(Shape shape) { }
        record HoldsCustom(CustomRead custom) { }

        assertThatThrownBy(() -> JsonSchemas.of(HoldsPolymorphic.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonTypeInfo");
        assertThatThrownBy(() -> JsonSchemas.of(HoldsCustom.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonDeserialize");
    }

    @Test
    void theRefusalIsReportedAgainstTheComponentThatCarriesIt() {
        // An annotation written in a record header lands on the field and the constructor
        // parameter, so scanning those at the class level would blame the whole record.
        record Renamed(String title, @JsonSetter("s") int severity) { }

        assertThatThrownBy(() -> JsonSchemas.of(Renamed.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Renamed.severity");
    }

    /**
     * One record per allowlisted annotation, and a conforming reply for each.
     *
     * <p>A single composite fixture did not guard the allowlist: adding a genuinely unsafe
     * entry left the suite green, because nothing exercised the new entry. Keyed by
     * annotation so the coverage assertion below can prove the set and the fixtures agree.
     */
    private static final Map<Class<? extends Annotation>, Object[]> ALLOWLIST_FIXTURES = Map.ofEntries(
            Map.entry(JsonProperty.class, new Object[]{PropertyRenamed.class,
                "{\"issue_title\":\"t\",\"severity\":1}", new PropertyRenamed("t", 1)}),
            Map.entry(JsonPropertyDescription.class, new Object[]{Described.class,
                "{\"title\":\"t\"}", new Described("t")}),
            Map.entry(JsonClassDescription.class, new Object[]{ClassDescribed.class,
                "{\"title\":\"t\"}", new ClassDescribed("t")}),
            Map.entry(JsonAlias.class, new Object[]{Aliased.class,
                "{\"title\":\"t\"}", new Aliased("t")}),
            Map.entry(JsonInclude.class, new Object[]{Included.class,
                "{\"title\":\"t\"}", new Included("t")}),
            Map.entry(JsonAutoDetect.class, new Object[]{AutoDetected.class,
                "{\"title\":\"t\"}", new AutoDetected("t")}),
            Map.entry(JsonPropertyOrder.class, new Object[]{Ordered.class,
                "{\"title\":\"t\",\"severity\":1}", new Ordered("t", 1)}),
            Map.entry(JsonSerialize.class, new Object[]{Serialized.class,
                "{\"severity\":1}", new Serialized(1)}),
            Map.entry(JsonIgnoreType.class, new Object[]{HoldsIgnoredType.class,
                "{\"leaf\":{\"title\":\"t\"}}", new HoldsIgnoredType(new IgnoredType("t"))}),
            Map.entry(JsonRawValue.class, new Object[]{Raw.class,
                "{\"payload\":\"{}\"}", new Raw("{}")}),
            Map.entry(JsonAnyGetter.class, new Object[]{AnyGetter.class,
                "{\"title\":\"t\"}", new AnyGetter("t")}),
            Map.entry(JsonFilter.class, new Object[]{Filtered.class,
                "{\"title\":\"t\"}", new Filtered("t")}),
            Map.entry(JsonTypeName.class, new Object[]{TypeNamed.class,
                "{\"title\":\"t\"}", new TypeNamed("t")}));

    /** The allowlist entries that exist to reach a refusal of their own, so they cannot round-trip. */
    private static final Set<Class<?>> REFUSED_WITH_THEIR_OWN_MESSAGE = Set.of(
            JsonIgnore.class, JsonIgnoreProperties.class, JsonNaming.class, JsonCreator.class,
            JsonValue.class);

    @SuppressWarnings("unchecked")
    private static Set<Class<? extends Annotation>> understood() throws Exception {
        java.lang.reflect.Field field = JsonSchemas.class.getDeclaredField("UNDERSTOOD");
        field.setAccessible(true);
        return (Set<Class<? extends Annotation>>) field.get(null);
    }

    @Test
    void everyAllowlistedAnnotationHasEvidenceForBeingOnTheAllowlist() throws Exception {
        // The property the old version of this test claimed and did not have: adding an
        // unsafe entry to the allowlist must fail here. It cannot sail through unnoticed,
        // because an entry with no fixture fails this assertion before any parsing happens.
        Set<Class<?>> accounted = new java.util.HashSet<>(ALLOWLIST_FIXTURES.keySet());
        accounted.addAll(REFUSED_WITH_THEIR_OWN_MESSAGE);

        assertThat(understood()).allSatisfy(entry -> assertThat(accounted)
                .describedAs("@%s is allowlisted with no round-trip fixture and no refusal of "
                        + "its own — add one or the other", entry.getSimpleName())
                .contains(entry));
    }

    @Test
    void everyAllowlistedAnnotationReallyDoesRoundTrip() throws Exception {
        // Evidence, not assertion. Two things have to hold and only one used to be checked:
        // the reply must actually satisfy the derived schema, and it must then parse through
        // the strict mapper replies really go through. Without the first, a fixture author
        // adding an entry writes the JSON Jackson wants rather than the JSON the schema asks
        // for — which is the natural thing to do, and exactly how an unsafe entry would slip
        // past the test written to catch it. (The @JsonAlias fixture did precisely that.)
        for (var entry : ALLOWLIST_FIXTURES.entrySet()) {
            String annotation = entry.getKey().getSimpleName();
            Class<?> type = (Class<?>) entry.getValue()[0];
            String reply = (String) entry.getValue()[1];

            Map<String, Object> schema = JsonSchemas.of(type).schema();
            assertThat(schema).describedAs("@%s derives", annotation).containsKey("properties");
            assertConforms(annotation, schema, MAPPER.readTree(reply));
            assertThat(StructuredOutput.parse(reply, type))
                    .describedAs("@%s round-trips through the strict mapper", annotation)
                    .isEqualTo(entry.getValue()[2]);
        }
    }

    /** Every required property present, nothing the closed schema forbids, recursively. */
    @SuppressWarnings("unchecked")
    private static void assertConforms(String annotation, Map<String, Object> schema,
                                       com.fasterxml.jackson.databind.JsonNode node) {
        if (!"object".equals(schema.get("type"))) {
            return;
        }
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(node.properties().stream().map(Map.Entry::getKey))
                .describedAs("@%s: the reply carries keys the closed schema forbids", annotation)
                .allMatch(properties::containsKey);
        assertThat((List<String>) schema.get("required"))
                .describedAs("@%s: the reply is missing a required property", annotation)
                .allMatch(node::has);
        properties.forEach((name, property) -> {
            if (node.has(name)) {
                assertConforms(annotation, (Map<String, Object>) property, node.get(name));
            }
        });
    }

    @Test
    void anAliasIsAdditiveWhichIsWhyItIsTolerated() {
        // The schema asks for the declared name and the fixture above proves that parses.
        // This is the other half: the alias is accepted as well, which is the property that
        // makes @JsonAlias safe to allow — it adds a name, it never replaces one.
        assertThatCode(() -> assertThat(MAPPER.readValue("{\"alias_title\":\"t\"}", Aliased.class))
                .isEqualTo(new Aliased("t"))).doesNotThrowAnyException();
    }

    @Test
    void jsonValueOnARecordsOwnMethodIsRefused() {
        // Every @JsonValue test was an enum test, so deleting the record check left the
        // suite green. Jackson reads such a record from a bare scalar, not an object.
        record Scalar(String value) {
            @JsonValue
            public String value() {
                return value;
            }
        }

        assertThatThrownBy(() -> JsonSchemas.of(Scalar.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonValue");
    }

    @Test
    void aRenameInheritedFromAnInterfaceAccessorIsHonoured() {
        // The other unguarded fix: without the overridden-method walk in annotationOn the
        // schema says "title" while Jackson reads "i", so every conforming reply fails.
        assertThat(JsonSchemas.of(ViaRename.class).schema())
                .extracting("required").asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactly("i");
    }

    @Test
    void jsonValueOnAnEnumOverridingAnInterfaceMethodIsRefused() {
        // getMethods() returns the enum's own override and not the interface's, so the
        // annotation was shadowed — and writing @JsonValue on the interface to force
        // implementors to supply it is the more idiomatic shape of the two.
        record Holds(Overriding v) { }

        assertThatThrownBy(() -> JsonSchemas.of(Holds.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonValue");
    }

    @Test
    void anInheritedAnySetterIsSweptToo() {
        // Declared it was refused, inherited it was not — and it silently waives the
        // unknown-property strictness StructuredOutput's javadoc promises.
        assertThatThrownBy(() -> JsonSchemas.of(WithAnySetter.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonAnySetter");
    }

    @Test
    void jsonViewIsNotOnTheAllowlistBecauseViewsApplyToReadingToo() {
        // It was, filed under "write-side only", which is wrong: a reader with an active
        // view drops a property the schema still requires, and under-fills in silence.
        record Viewed(@JsonView(Object.class) String title) { }

        assertThatThrownBy(() -> JsonSchemas.of(Viewed.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonView");
    }

    @Test
    void aJacksonAnnotationBundleIsRefused() {
        // @JacksonAnnotationsInside is a real Jackson feature and its own package is the
        // caller's, so the prefix test never saw it. Worse than a gap in the new sweep: a
        // @JsonIgnore hidden inside a bundle defeated the refusal that already existed.
        record Bundled(@Wire String title, int severity) { }

        assertThatThrownBy(() -> JsonSchemas.of(Bundled.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bundle");
    }

    @Test
    void aVouchDoesNotRelieveABundle() {
        // Tried and reverted. Letting a vouch through first re-opened every refusal the
        // bundle check exists to reach: the five load-bearing ones all resolve annotations
        // with getAnnotation, which does not see meta-annotations, so @JsonIgnore hidden
        // inside a vouched bundle silently under-filled a conforming reply again. The vouch
        // means "I have read this and it does not change what is read", and a caller cannot
        // make that statement about an expansion neither they nor this can see.
        record Bundled(@Wire String title, int severity) { }

        assertThatThrownBy(() -> JsonSchemas.of(Bundled.class, Set.of(Wire.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bundle");
    }

    @Test
    void aNullPolicyOnASetterIsRefusedBecauseItDisarmsTheStrictReader() {
        // Not inert, though it reads that way: nulls is a read-side default and it overrides
        // the Nulls.FAIL that StructuredOutput installs. It also fires on a MISSING property,
        // and AS_EMPTY invents a value rather than tolerating the gap — so a component the
        // model declined to answer comes back as "" and nothing anywhere raises.
        record Skipping(@JsonSetter(nulls = com.fasterxml.jackson.annotation.Nulls.AS_EMPTY)
                        String title) { }

        assertThatThrownBy(() -> JsonSchemas.of(Skipping.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nulls.FAIL");
    }

    @Test
    void aReadFeatureOnAFormatIsRefusedEvenThoughTheShapeIsUnchanged() {
        // shape() was the only element checked, and it is not the only one that widens what
        // Jackson accepts: this one turns a value outside the schema's enum into a silent
        // null, which is the exact failure the allowlist exists to prevent.
        record Loose(@JsonFormat(with = JsonFormat.Feature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                     Severity level) { }

        assertThatThrownBy(() -> JsonSchemas.of(Loose.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonFormat");
    }

    @Test
    void vouchingForSetterOrFormatDoesNotReachTheirOwnRefusals() {
        // The invariant the class documents: an annotation with a reason of its own still
        // fires when named in alsoUnderstood. Placing the two checks after the vouch broke
        // it for both, and "nulls and contentNulls are fine" is exactly the belief that
        // would make a caller try.
        record Renamed(@JsonSetter("wire_title") String title) { }
        @JsonFormat(shape = JsonFormat.Shape.ARRAY)
        record AsArray(String title, int severity) { }

        assertThatThrownBy(() -> JsonSchemas.of(Renamed.class, Set.of(JsonSetter.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("renames the property");
        assertThatThrownBy(() -> JsonSchemas.of(AsArray.class, Set.of(JsonFormat.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonFormat");
    }

    @Test
    void theSweepRunsOnEnumsToo() {
        // It did not, which made "refuses any Jackson annotation it does not model" false
        // for every enum in the graph.
        record Holds(Unmodelled colour) { }

        assertThatThrownBy(() -> JsonSchemas.of(Holds.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonFormat");
    }

    @Test
    void aCreatorOnAnEnumIsRefusedBecauseItReadsFromSomethingOtherThanTheName() {
        // The silent-wrong-value case: the schema listed RED and GREEN, the model returned
        // "RED", and the factory mapped it to GREEN with nothing raised anywhere.
        record Holds(CodedEnum colour) { }

        assertThatThrownBy(() -> JsonSchemas.of(Holds.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonCreator")
                .hasMessageContaining("other than its name");
    }

    @Test
    void anAnnotationOnAnInterfaceAccessorIsSeen() {
        // Jackson resolves a property's annotations from the methods it overrides, so a
        // sealed interface declaring the accessor is in force there and was invisible here.
        assertThatThrownBy(() -> JsonSchemas.of(Titled.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonDeserialize");
    }

    @Test
    void anAnnotationOnANonAccessorMethodOrAConstructorIsSeen() {
        // Two of the scan sites the sweep added, neither of which any test reached.
        record OnMethod(String title) {
            @JsonSetter("wire")
            void helper() {
            }
        }
        record OnConstructor(String title) {
            // One of the few Jackson annotations that targets a constructor at all, and
            // unmodelled — so it is what reaches the constructor scan.
            @JsonIncludeProperties("title")
            OnConstructor {
            }
        }

        assertThatThrownBy(() -> JsonSchemas.of(OnMethod.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonSetter");
        assertThatThrownBy(() -> JsonSchemas.of(OnConstructor.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonIncludeProperties");
    }

    @Test
    void anAnnotationInheritedFromAnInterfaceTypeIsSeen() {
        assertThatThrownBy(() -> JsonSchemas.of(Rooted.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonRootName");
    }

    @Test
    void aCallerCanVouchForAnAnnotationTheyHaveChecked() {
        // The pressure valve. Naming it is the point: a blanket opt-out would also switch
        // off @JsonIgnore, whose refusal is load-bearing.
        assertThat(JsonSchemas.of("Rooted", Vouched.class, Set.of(JsonRootName.class)).schema())
                .containsKey("properties");
        // Vouching does not reach the refusals that have a reason of their own.
        assertThatThrownBy(() -> JsonSchemas.of("Hidden", HasIgnore.class, Set.of(JsonIgnore.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonIgnore means Jackson will never fill it");
    }

    @Test
    void jsonValueIsSeenAtAnyVisibility() {
        // getMethods() is public-only, and the idiomatic enum accessor is package-private —
        // nothing outside the enum calls it. So the check the enum sweep was built around
        // did not fire on the shape it was written for: the schema listed exactly the
        // constants Jackson rejects and none of the values it accepts.
        record Triage(Severity severity) { }

        assertThatThrownBy(() -> JsonSchemas.of(Triage.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonValue");
    }

    @Test
    void jsonValueInheritedByARecordIsSeenToo() {
        // The mirror gap: recordSchema looked only at declared methods, so an interface
        // default method carrying it was invisible, and a conforming reply failed with
        // "cannot deserialize from Object value".
        assertThatThrownBy(() -> JsonSchemas.of(ScalarRecord.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@JsonValue");
    }

    @Test
    void vouchingHasAConvenienceFormToo() {
        // Without of(Class, Set) a caller who vouches has to restate the simple name by hand.
        assertThat(JsonSchemas.of(Vouched.class, Set.of(JsonRootName.class)).name())
                .isEqualTo("Vouched");
    }

    @Test
    void theDerivedSchemaSatisfiesOutputSchemasOwnValidation() {
        // OutputSchema rejects non-JSON values and names outside the provider charset, so
        // deriving one that it then refuses would be a generator bug rather than a caller's.
        assertThat(JsonSchemas.of(Review.class)).isNotNull();
        assertThatThrownBy(() -> JsonSchemas.of("not a valid name!", Finding.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[A-Za-z0-9_-]");
    }
}
