package dev.agentkit.core.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * A {@link Tool} backed by a plain function, for defining tools inline without a
 * dedicated class.
 *
 * <pre>{@code
 * Tool echo = FunctionTool.builder("echo", "Echoes its input")
 *         .schema(Map.of("type", "object",
 *                        "properties", Map.of("text", Map.of("type", "string")),
 *                        "required", List.of("text")))
 *         .handler(inv -> ToolResult.ok(inv.stringArgument("text")))
 *         .build();
 * }</pre>
 */
public final class FunctionTool implements Tool {

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final List<Map<String, Object>> examples;
    private final Function<ToolInvocation, ToolResult> handler;
    private final SideEffects sideEffects;
    private final Provenance provenance;

    private FunctionTool(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.inputSchema = java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(builder.inputSchema));
        List<Map<String, Object>> copiedExamples = new ArrayList<>(builder.examples.size());
        for (Map<String, Object> example : builder.examples) {
            copiedExamples.add(java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(example)));
        }
        this.examples = java.util.Collections.unmodifiableList(copiedExamples);
        this.handler = builder.handler;
        this.sideEffects = builder.sideEffects;
        this.provenance = builder.provenance;
    }

    @Override
    public Provenance provenance() {
        return provenance;
    }

    @Override
    public SideEffects sideEffects() {
        return sideEffects;
    }

    public static Builder builder(String name, String description) {
        return new Builder(name, description);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return inputSchema;
    }

    @Override
    public List<Map<String, Object>> inputExamples() {
        return examples;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        return handler.apply(invocation);
    }

    /** Builder for {@link FunctionTool}. */
    public static final class Builder {
        private final String name;
        private final String description;
        private Map<String, Object> inputSchema = ToolSpec.emptyObjectSchema();
        private final List<Map<String, Object>> examples = new ArrayList<>();
        private Function<ToolInvocation, ToolResult> handler;
        private SideEffects sideEffects = SideEffects.UNKNOWN;
        private Provenance provenance = Provenance.UNKNOWN;

        private Builder(String name, String description) {
            this.name = Objects.requireNonNull(name, "name");
            this.description = Objects.requireNonNull(description, "description");
        }

        public Builder schema(Map<String, Object> inputSchema) {
            this.inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
            return this;
        }

        /** Adds a few-shot example argument set shown to the model alongside the schema. */
        public Builder example(Map<String, Object> example) {
            this.examples.add(Objects.requireNonNull(example, "example"));
            return this;
        }

        /**
         * Declares what this tool does outside the process. Say {@link SideEffects#NONE}
         * for a tool that only reads or derives — that is what makes it eligible for a
         * read-only rehearsal, and what marks a step using it as safe to retry.
         */
        public Builder sideEffects(SideEffects sideEffects) {
            this.sideEffects = Objects.requireNonNull(sideEffects, "sideEffects");
            return this;
        }

        /** Who wrote what this tool returns. See {@link Provenance}. */
        public Builder provenance(Provenance provenance) {
            this.provenance = Objects.requireNonNull(provenance, "provenance");
            return this;
        }

        /** Shorthand for {@link #sideEffects}{@code (SideEffects.NONE)}. */
        public Builder readOnly() {
            return sideEffects(SideEffects.NONE);
        }

        public Builder handler(Function<ToolInvocation, ToolResult> handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        public FunctionTool build() {
            Objects.requireNonNull(handler, "handler must be set");
            return new FunctionTool(this);
        }
    }
}
