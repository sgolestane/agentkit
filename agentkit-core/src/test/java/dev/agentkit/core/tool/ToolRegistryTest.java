package dev.agentkit.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    private static Tool tool(String name) {
        return FunctionTool.builder(name, "desc of " + name)
                .handler(inv -> ToolResult.ok("ok"))
                .build();
    }

    @Test
    void registersAndFindsByName() {
        SimpleToolRegistry registry = new SimpleToolRegistry().register(tool("a")).register(tool("b"));

        assertThat(registry.find("a")).isPresent();
        assertThat(registry.find("missing")).isEmpty();
        assertThat(registry.tools()).hasSize(2);
    }

    @Test
    void advertisesAllSpecsInRegistrationOrder() {
        SimpleToolRegistry registry = new SimpleToolRegistry(java.util.List.of(tool("a"), tool("b")));

        assertThat(registry.advertisedSpecs())
                .extracting(ToolSpec::name)
                .containsExactly("a", "b");
    }

    @Test
    void duplicateRegistrationIsRejected() {
        SimpleToolRegistry registry = new SimpleToolRegistry().register(tool("dup"));
        assertThatThrownBy(() -> registry.register(tool("dup")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void functionToolSchemaIsImmutable() {
        java.util.Map<String, Object> schema = new java.util.HashMap<>();
        schema.put("type", "object");
        Tool t = FunctionTool.builder("t", "d").schema(schema).handler(inv -> ToolResult.ok("x")).build();

        schema.put("mutated", true); // must not affect the built tool
        assertThat(t.inputSchema()).doesNotContainKey("mutated");
        assertThatThrownBy(() -> t.inputSchema().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void advertisedSpecsAreImmutable() {
        SimpleToolRegistry registry = new SimpleToolRegistry().register(tool("a"));
        assertThatThrownBy(() -> registry.advertisedSpecs().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void functionToolExposesSpec() {
        Tool t = tool("echo");
        ToolSpec spec = t.spec();
        assertThat(spec.name()).isEqualTo("echo");
        assertThat(spec.description()).isEqualTo("desc of echo");
        assertThat(spec.inputSchema()).containsEntry("type", "object");
    }
}
