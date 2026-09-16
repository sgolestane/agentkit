package dev.agentkit.core.llm;

import dev.agentkit.core.message.Message;
import dev.agentkit.core.tool.ToolSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A provider-agnostic request to an {@link LlmClient}.
 *
 * <p>Construct via {@link #builder(String)}. The {@code options} map carries
 * provider-specific knobs (e.g. reasoning effort) without leaking them into the
 * core API; unknown options are ignored by adapters that do not understand them.
 */
public final class LlmRequest {

    private final String model;
    private final String system;
    private final List<Message> messages;
    private final List<ToolSpec> tools;
    private final int maxTokens;
    private final OutputSchema outputSchema;
    private final Map<String, Object> options;

    private LlmRequest(Builder b) {
        this.model = b.model;
        this.system = b.system;
        this.messages = List.copyOf(b.messages);
        this.tools = List.copyOf(b.tools);
        this.maxTokens = b.maxTokens;
        this.outputSchema = b.outputSchema;
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(b.options));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("An LLM request must contain at least one message");
        }
    }

    public String model() {
        return model;
    }

    public Optional<String> system() {
        return Optional.ofNullable(system);
    }

    public List<Message> messages() {
        return messages;
    }

    public List<ToolSpec> tools() {
        return tools;
    }

    public int maxTokens() {
        return maxTokens;
    }

    /**
     * A JSON Schema the reply must conform to, if structured output was requested.
     * Combinable with {@link #tools()}: the model may still call a tool, and when it
     * answers instead, the answer is schema-constrained. See {@link OutputSchema} for
     * which adapters honour it.
     */
    public Optional<OutputSchema> outputSchema() {
        return Optional.ofNullable(outputSchema);
    }

    public Map<String, Object> options() {
        return options;
    }

    public static Builder builder(String model) {
        return new Builder(model);
    }

    /** Builder for {@link LlmRequest}. */
    public static final class Builder {
        private final String model;
        private String system;
        private final List<Message> messages = new ArrayList<>();
        private final List<ToolSpec> tools = new ArrayList<>();
        private int maxTokens = 4096;
        private OutputSchema outputSchema;
        private final Map<String, Object> options = new LinkedHashMap<>();

        private Builder(String model) {
            this.model = Objects.requireNonNull(model, "model");
            if (model.isBlank()) {
                throw new IllegalArgumentException("model must not be blank");
            }
        }

        public Builder system(String system) {
            this.system = system;
            return this;
        }

        public Builder messages(List<Message> messages) {
            Objects.requireNonNull(messages, "messages");
            this.messages.clear();
            this.messages.addAll(messages);
            return this;
        }

        public Builder addMessage(Message message) {
            this.messages.add(Objects.requireNonNull(message, "message"));
            return this;
        }

        public Builder tools(List<ToolSpec> tools) {
            Objects.requireNonNull(tools, "tools");
            this.tools.clear();
            this.tools.addAll(tools);
            return this;
        }

        public Builder addTool(ToolSpec tool) {
            this.tools.add(Objects.requireNonNull(tool, "tool"));
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            if (maxTokens <= 0) {
                throw new IllegalArgumentException("maxTokens must be > 0");
            }
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Constrains the reply to JSON matching {@code outputSchema}. Combinable with
         * tools — see {@link OutputSchema}.
         */
        public Builder outputSchema(OutputSchema outputSchema) {
            this.outputSchema = Objects.requireNonNull(outputSchema, "outputSchema");
            return this;
        }

        public Builder option(String key, Object value) {
            this.options.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }

        /**
         * An independent copy, so one more setting can be layered on a builder someone else
         * owns without the change leaking back to them. A builder handed to a helper is
         * usually a template the caller means to reuse, and a helper that quietly left a
         * schema or a tool on it would poison the next call rather than fail.
         *
         * <p>Shallow over {@code options}, as {@code LlmRequest}'s own constructor is: a
         * mutable value put in the map is reachable from both builders.
         */
        public Builder copy() {
            Builder copy = new Builder(model);
            copy.system = system;
            copy.messages.addAll(messages);
            copy.tools.addAll(tools);
            copy.maxTokens = maxTokens;
            copy.outputSchema = outputSchema;
            copy.options.putAll(options);
            return copy;
        }

        public LlmRequest build() {
            return new LlmRequest(this);
        }
    }
}
