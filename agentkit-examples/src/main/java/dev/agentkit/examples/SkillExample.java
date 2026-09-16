package dev.agentkit.examples;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.reliability.RetryPolicy;
import dev.agentkit.core.reliability.RetryingLlmClient;
import dev.agentkit.core.skill.Skill;
import dev.agentkit.core.skill.SkillLibrary;
import dev.agentkit.core.skill.Skills;
import dev.agentkit.core.tool.SimpleToolRegistry;

/**
 * Demonstrates the skills subsystem end-to-end against a live model.
 *
 * <p>Skills disclose progressively to protect the context window. Only tier-1
 * metadata — each skill's {@code name} and {@code description} — sits in the
 * system prompt from the start. When the model decides a skill is relevant it
 * calls the {@code read_skill} tool, and only then does that skill's full tier-2
 * instructions enter the context. Here the library holds two writing skills; the
 * goal asks for a haiku, so a well-behaved run reads <em>only</em> the
 * {@code haiku} skill and follows its rules.
 *
 * <p>The wiring is one call each: {@link Skills#systemPrompt} folds the tier-1
 * catalog into the base prompt, and {@link Skills#registerInto} registers the
 * always-available skill tools — the two halves that must both be present for
 * skills to work.
 */
public final class SkillExample {

    private SkillExample() {
    }

    /** A two-skill writing library the agent progressively discloses from. */
    public static SkillLibrary library() {
        return new SkillLibrary()
                .add(Skill.of("haiku",
                        "Write a haiku about a given topic",
                        "Compose a haiku: three lines with a 5, 7, 5 syllable structure. "
                                + "Evoke a single vivid image and a seasonal or natural touch. "
                                + "Output only the three lines, nothing else."))
                .add(Skill.of("limerick",
                        "Write a limerick about a given topic",
                        "Compose a limerick: five lines rhyming AABBA, with a bouncy, humorous "
                                + "anapaestic metre. Output only the five lines, nothing else."));
    }

    /** An agent whose system prompt carries the skill catalog and whose registry holds the skill tools. */
    public static Agent build(LlmClient llm, String model) {
        return build(llm, model, AgentObserver.NONE);
    }

    /** As {@link #build(LlmClient, String)}, with an observer on the agent (for tests/telemetry). */
    static Agent build(LlmClient llm, String model, AgentObserver observer) {
        SkillLibrary library = library();

        // Both halves from one library: tools registered + catalog in the prompt.
        SimpleToolRegistry tools = Skills.registerInto(new SimpleToolRegistry(), library);
        String systemPrompt = Skills.systemPrompt(
                "You are a concise writing assistant. When a skill fits the request, read it "
                        + "and follow its instructions exactly.",
                library);

        AgentConfig config = AgentConfig.builder(model)
                .systemPrompt(systemPrompt)
                .maxSteps(6)
                .build();

        return Agent.builder(llm, tools, config).observer(observer).build();
    }

    /** Runs the example against the configured backend (see {@link ExampleBackend}). */
    public static void main(String[] args) {
        ExampleBackend backend = ExampleBackend.fromEnv();
        LlmClient reliable = new RetryingLlmClient(backend.llm(), RetryPolicy.defaults());
        Agent agent = build(reliable, backend.model());

        AgentResult result = agent.run(Goal.of("Write a haiku about durable execution."));
        System.out.println("stopReason=" + result.stopReason());
        System.out.println(result.output());
    }
}
