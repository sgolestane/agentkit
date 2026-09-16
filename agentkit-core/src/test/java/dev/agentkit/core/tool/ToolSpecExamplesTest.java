package dev.agentkit.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolSpecExamplesTest {

    @Test
    void theThreeArgConstructorLeavesExamplesEmpty() {
        ToolSpec spec = new ToolSpec("t", "d", ToolSpec.emptyObjectSchema());
        assertThat(spec.examples()).isEmpty();
    }

    @Test
    void examplesAreDefensivelyCopiedAndUnmodifiable() {
        List<Map<String, Object>> examples = new ArrayList<>();
        examples.add(Map.of("q", "hello"));
        ToolSpec spec = new ToolSpec("search", "d", ToolSpec.emptyObjectSchema(), examples);

        examples.clear(); // must not affect the stored copy
        assertThat(spec.examples()).hasSize(1).first().isEqualTo(Map.of("q", "hello"));
        assertThatThrownBy(() -> spec.examples().add(Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aNullExamplesListIsToleratedAsEmpty() {
        // Schema evolution: deserializing an older payload passes null for the
        // added component, which must default to empty rather than throw.
        ToolSpec spec = new ToolSpec("t", "d", ToolSpec.emptyObjectSchema(), null);
        assertThat(spec.examples()).isEmpty();
    }

    @Test
    void aNullExampleIsRejected() {
        List<Map<String, Object>> examples = new ArrayList<>();
        examples.add(null);
        assertThatThrownBy(() -> new ToolSpec("t", "d", ToolSpec.emptyObjectSchema(), examples))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void functionToolBuilderCarriesExamplesIntoItsSpec() {
        Tool tool = FunctionTool.builder("search", "search the web")
                .schema(Map.of("type", "object", "properties",
                        Map.of("q", Map.of("type", "string"))))
                .example(Map.of("q", "weather in Paris"))
                .example(Map.of("q", "latest AI news"))
                .handler(inv -> ToolResult.ok("ok"))
                .build();

        assertThat(tool.inputExamples()).hasSize(2);
        assertThat(tool.spec().examples()).containsExactly(
                Map.of("q", "weather in Paris"), Map.of("q", "latest AI news"));
    }

    @Test
    void functionToolExamplesAreUnmodifiableAndDoNotLeakBuilderMaps() {
        Map<String, Object> mutable = new java.util.HashMap<>();
        mutable.put("q", "before");
        Tool tool = FunctionTool.builder("search", "d")
                .example(mutable)
                .handler(inv -> ToolResult.ok("ok"))
                .build();

        mutable.put("q", "after"); // post-build mutation must not bleed in
        assertThat(tool.inputExamples()).containsExactly(Map.of("q", "before"));
        assertThatThrownBy(() -> tool.inputExamples().add(Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aToolWithoutExamplesAdvertisesNone() {
        Tool tool = FunctionTool.builder("noop", "no-op")
                .handler(inv -> ToolResult.ok("ok"))
                .build();
        assertThat(tool.inputExamples()).isEmpty();
        assertThat(tool.spec().examples()).isEmpty();
    }
}
