package dev.agentkit.core.agent;

import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.util.Frozen;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Configuration for an {@link Agent} run.
 *
 * @param model        the model identifier passed to the {@code LlmClient}
 * @param systemPrompt the system prompt, or {@code null} for none
 * @param maxSteps     the maximum number of model turns before the loop stops
 * @param maxTokens    the per-turn output token limit
 * @param options      provider-specific options forwarded on every request. Stored as a
 *                     defensive, unmodifiable, order-preserving copy that reaches every
 *                     nested map and collection; a value of any other type is shared. It
 *                     is {@code Frozen.containersOf} and deliberately not
 *                     {@code Frozen.deeply}: this map is hand-built Java, nothing came off
 *                     a wire, and {@code deeply} would refuse the {@code Duration}, retry
 *                     policy or client object a provider option legitimately holds —
 *                     {@code OpenRouterLlmClient} forwards each value to
 *                     {@code MAPPER.valueToTree}, which takes all three. What the copy is
 *                     for is the other failure: the options are forwarded on
 *                     <em>every</em> request, so a nested map shared with the caller let a
 *                     later mutation change requests the recorded {@code DurableAgentRun}
 *                     says were made with something else (#133). The copy is paid on every
 *                     replay for the same reason {@code Goal}'s is; measured at 416 B for
 *                     the one-level copy and 1,072 B for this one, on a sixteen-value map
 * @param explainsFencedContent whether the system prompt carries the clause explaining
 *                     fenced untrusted content; see {@link #explainsFencedContent()}.
 *                     Boxed, and {@code null} means the default of {@code true}: this
 *                     record is persisted through {@code DurableAgentRun}, so a run
 *                     started before the component existed is replayed by a worker that
 *                     has it, and a primitive would silently deserialize to {@code false}
 *                     — turning off the clause while the activities went on fencing.
 *                     {@code DurableJson} states the rule: added components stay nullable.
 *                     Its position carries no meaning: adding any component changes the
 *                     canonical constructor's arity whatever the order, and Jackson maps by
 *                     name, so the wire format does not depend on it either.
 */
public record AgentConfig(String model, String systemPrompt, int maxSteps, int maxTokens,
                          Map<String, Object> options,
                          Boolean explainsFencedContent) {

    public AgentConfig {
        // Normalised here rather than in the accessor, so the component is never null
        // afterwards and equals/hashCode do not distinguish "absent" from "explicitly on".
        explainsFencedContent = explainsFencedContent == null || explainsFencedContent;
        Objects.requireNonNull(model, "model");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be > 0");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be > 0");
        }
        Objects.requireNonNull(options, "options");
        options = Frozen.containersOf(options);
    }

    public Optional<String> systemPromptValue() {
        return Optional.ofNullable(systemPrompt);
    }

    /**
     * Whether the agent's system prompt carries {@link Spotlight#INSTRUCTION}, the clause
     * explaining fenced untrusted content. Default {@code true}.
     *
     * <p>Named for what it does. It does <em>not</em> switch spotlighting off: the
     * collaborators that fence — a knowledge search, a skill catalog, a planner threading
     * prior steps — hold no {@code AgentConfig} and go on fencing either way. This is the
     * control for the seam where the two can come apart under an {@link Agent}: a
     * single-purpose call like {@code LlmVerifier} puts the fence and the clause in the
     * same request and cannot desynchronise, while an agent receives fences from
     * collaborators it never sees.
     *
     * <p>It is not the <em>only</em> such seam, only the one with a switch. Fenced text
     * from public API — {@code NodeInput.renderDependencies()}, {@code SkillLibrary.catalog()},
     * {@code WorkingMemory.render()}, a framework tool's result — can be placed into a
     * request no {@code Agent} built, and then carrying the clause is on you: call
     * {@link Spotlight#withInstruction}. {@code WorkingMemory} is the one that invites it,
     * its own javadoc suggesting you inject the notes into a system prompt yourself.
     *
     * <p>So turn it off only when you are supplying equivalent wording yourself. Turning
     * it off to save tokens leaves the model reading markers nothing has defined, which is
     * the failure the first attempt at this shipped.
     */
    public Boolean explainsFencedContent() {
        // Boxed because a record accessor must return the component type, but never null:
        // the compact constructor has already replaced an absent value with the default,
        // so unboxing at a call site is safe.
        return explainsFencedContent;
    }

    public static Builder builder(String model) {
        return new Builder(model);
    }

    /** Builder for {@link AgentConfig}. */
    public static final class Builder {
        private final String model;
        private String systemPrompt;
        private Boolean explainsFencedContent = Boolean.TRUE;
        private int maxSteps = 10;
        private int maxTokens = 4096;
        private final Map<String, Object> options = new LinkedHashMap<>();

        private Builder(String model) {
            this.model = model;
        }

        /** See {@link AgentConfig#explainsFencedContent()}. */
        public Builder explainFencedContent(boolean explain) {
            this.explainsFencedContent = explain;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder option(String key, Object value) {
            this.options.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(model, systemPrompt, maxSteps, maxTokens,
                    options, explainsFencedContent);
        }
    }
}
