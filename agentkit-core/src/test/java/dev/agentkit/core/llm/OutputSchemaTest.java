package dev.agentkit.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.message.Message;
import dev.agentkit.core.tool.ToolSpec;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutputSchemaTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("label", Map.of("type", "string")),
            "required", List.of("label"),
            "additionalProperties", false);

    @Test
    void carriesItsNameAndSchema() {
        OutputSchema schema = OutputSchema.of("sentiment", SCHEMA);
        assertThat(schema.name()).isEqualTo("sentiment");
        assertThat(schema.schema()).containsEntry("type", "object");
    }

    @Test
    void ofPropertiesBuildsAClosedObjectWithEveryPropertyRequired() {
        OutputSchema schema = OutputSchema.ofProperties("person", Map.of(
                "name", Map.of("type", "string"),
                "age", Map.of("type", "integer")));

        assertThat(schema.schema())
                .containsEntry("type", "object")
                // Both providers reject an object schema that omits this; it is not optional.
                .containsEntry("additionalProperties", false);
        assertThat(schema.schema().get("properties")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.schema().get("required");
        assertThat(required).containsExactlyInAnyOrder("name", "age");
    }

    @Test
    void ofPropertiesRejectsAnEmptyPropertyMap() {
        assertThatThrownBy(() -> OutputSchema.ofProperties("p", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty");
    }

    @Test
    void aBlankNameOrEmptySchemaIsRejected() {
        assertThatThrownBy(() -> OutputSchema.of(" ", SCHEMA))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blank");
        assertThatThrownBy(() -> OutputSchema.of("s", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> OutputSchema.of(null, SCHEMA))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aNameOutsideTheProviderCharsetIsRejected() {
        // OpenAI-shaped providers enforce [A-Za-z0-9_-]{1,64} on json_schema.name and
        // 400 otherwise; catching it here beats a rejected call.
        assertThatThrownBy(() -> OutputSchema.of("my schema!", SCHEMA))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[A-Za-z0-9_-]");
        assertThatThrownBy(() -> OutputSchema.of("x".repeat(65), SCHEMA))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(OutputSchema.of("a-valid_Name-1", SCHEMA).name()).isEqualTo("a-valid_Name-1");
        assertThat(OutputSchema.of("x".repeat(64), SCHEMA).name()).hasSize(64);
    }

    @Test
    void theSchemaIsCopiedDeeplyAndIsUnmodifiableThroughout() {
        Map<String, Object> nested = new LinkedHashMap<>(Map.of("type", "string"));
        Map<String, Object> properties = new LinkedHashMap<>(Map.of("label", nested));
        List<Object> required = new ArrayList<>(List.of("label"));
        Map<String, Object> mutable = new LinkedHashMap<>();
        mutable.put("type", "object");
        mutable.put("properties", properties);
        mutable.put("required", required);

        OutputSchema schema = OutputSchema.of("s", mutable);

        // None of this may reach the wire.
        mutable.put("type", "tampered");
        properties.put("injected", Map.of("type", "string"));
        nested.put("type", "TAMPERED");
        required.add("injected");

        assertThat(schema.schema()).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        Map<String, Object> copiedProperties = (Map<String, Object>) schema.schema().get("properties");
        assertThat(copiedProperties).containsOnlyKeys("label");
        assertThat(copiedProperties.get("label")).isEqualTo(Map.of("type", "string"));
        assertThat(schema.schema().get("required")).isEqualTo(List.of("label"));

        assertThatThrownBy(() -> schema.schema().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> copiedProperties.put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aValueThatCannotBeRenderedAsJsonIsRejectedWithItsPath() {
        // Adapters hand the map straight to a JSON mapper, where this would surface as
        // a mapper-specific error mid-call instead of at the call site.
        assertThatThrownBy(() -> OutputSchema.of("s", Map.of(
                        "properties", Map.of("label", Map.of("type", new Object())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema.properties.label.type");

        assertThatThrownBy(() -> OutputSchema.of("s", Map.of("enum", List.of(new Object()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema.enum[0]");
    }

    @Test
    void jsonRepresentableValuesAllSurvive() {
        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("type", "object");
        mixed.put("additionalProperties", false);
        mixed.put("maxItems", 3);
        mixed.put("factor", 1.5);
        mixed.put("bigger", new BigDecimal("1.25"));
        mixed.put("default", null);
        mixed.put("enum", List.of("a", "b"));

        assertThat(OutputSchema.of("s", mixed).schema()).isEqualTo(mixed);
    }

    @Test
    void anExoticNumberIsRejectedRatherThanTrustedForBeingANumber() {
        // A Number is neither necessarily JSON-safe nor necessarily immutable: this one
        // renders as a non-numeric string and blows up inside the mapper mid-call, and
        // an AtomicInteger would let a caller keep changing what is sent.
        Number notReallyANumber = new Number() {
            @Override public int intValue() {
                return 0;
            }

            @Override public long longValue() {
                return 0;
            }

            @Override public float floatValue() {
                return 0;
            }

            @Override public double doubleValue() {
                return 0;
            }

            @Override public String toString() {
                return "oops";
            }
        };

        assertThatThrownBy(() -> OutputSchema.of("s", Map.of("maxItems", notReallyANumber)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema.maxItems");
        assertThatThrownBy(() -> OutputSchema.of("s", Map.of("maxItems", new AtomicInteger(1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema.maxItems");
    }

    @Test
    void aNonFiniteNumberIsRejected() {
        // JSON has no NaN or Infinity; mappers render them as strings, so the provider
        // sees a string where it expects a number.
        assertThatThrownBy(() -> OutputSchema.of("s", Map.of("minimum", Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finite");
        assertThatThrownBy(() -> OutputSchema.of("s", Map.of("maximum", Float.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finite");
    }

    @Test
    void aSelfReferentialSchemaFailsWithAnExceptionRatherThanKillingTheStack() {
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("type", "object");
        cycle.put("properties", cycle);

        assertThatThrownBy(() -> OutputSchema.of("s", cycle))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nesting exceeds");
    }

    @Test
    void aRequestCarriesTheSchema() {
        LlmRequest request = LlmRequest.builder("m")
                .addMessage(Message.user("classify this"))
                .outputSchema(OutputSchema.of("sentiment", SCHEMA))
                .build();

        assertThat(request.outputSchema()).isPresent();
        assertThat(request.outputSchema().orElseThrow().name()).isEqualTo("sentiment");
    }

    @Test
    void aRequestWithoutASchemaIsUnconstrained() {
        assertThat(LlmRequest.builder("m").addMessage(Message.user("hi")).build()
                .outputSchema()).isEmpty();
    }

    @Test
    void aNullSchemaIsRejectedRatherThanSilentlyUnconstraining() {
        assertThatThrownBy(() -> LlmRequest.builder("m").outputSchema(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aSchemaCombinesWithTools() {
        // Both supported providers accept the pair: the model may call a tool, and when
        // it answers instead, the answer is schema-constrained.
        LlmRequest request = LlmRequest.builder("m")
                .addMessage(Message.user("go"))
                .outputSchema(OutputSchema.of("s", SCHEMA))
                .tools(List.of(new ToolSpec("search", "searches", Map.of("type", "object"))))
                .build();

        assertThat(request.tools()).hasSize(1);
        assertThat(request.outputSchema()).isPresent();
    }

    @Test
    void toolsWithoutASchemaRemainFine() {
        LlmRequest request = LlmRequest.builder("m")
                .addMessage(Message.user("go"))
                .tools(List.of(new ToolSpec("search", "searches", Map.of("type", "object"))))
                .build();

        assertThat(request.tools()).hasSize(1);
        assertThat(request.outputSchema()).isEmpty();
    }
}
