package dev.agentkit.core.reflect;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import java.util.Objects;

/**
 * A {@link Reflector} that asks a model to write the lesson.
 *
 * <p>Makes one tool-free call summarising the goal, the failed output, and the
 * feedback into a single imperative lesson. Using a separate call (ideally fresh
 * context) keeps the reflection honest rather than letting the same loop rationalise
 * its failure.
 */
public final class LlmReflector implements Reflector {

    private static final String SYSTEM = """
            You are reflecting on a failed attempt by an AI agent so it can do better next \
            time. Given the GOAL, the agent's OUTPUT, and the FEEDBACK on why it fell short, \
            write a single concise lesson — one or two imperative sentences — that the agent \
            should remember to avoid repeating this mistake. Output only the lesson, no preamble.""";

    private final LlmClient llm;
    private final String model;
    private final int maxTokens;

    public LlmReflector(LlmClient llm, String model) {
        this(llm, model, 256);
    }

    public LlmReflector(LlmClient llm, String model, int maxTokens) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.model = Objects.requireNonNull(model, "model");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be > 0");
        }
        this.maxTokens = maxTokens;
    }

    @Override
    public String reflect(Goal goal, AgentResult result, String feedback) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(feedback, "feedback");

        // Whatever this produces is written to a LessonBook and replayed into a later
        // run's goal, so an injection surviving here outlives the run it arrived in.
        //
        // Both spans are EVIDENCE, including the feedback that is ADVISORY elsewhere. The
        // kind is relative to the recipient: SelfVerifyingAgent hands this feedback to the
        // agent being asked to act on it, while a reflector is not the author under review
        // and is being asked to describe the failure. Material to summarise, not advice.
        String prompt = "GOAL:\n" + goal.description()
                + "\n\nOUTPUT:\n" + Spotlight.wrap(Source.of("agent-output"), result.output())
                + "\n\nFEEDBACK:\n" + Spotlight.wrap(Source.of("verifier-feedback"), feedback);
        LlmRequest request = LlmRequest.builder(model)
                .system(Spotlight.withInstruction(SYSTEM))
                .maxTokens(maxTokens)
                .addMessage(Message.user(prompt))
                .build();
        return llm.generate(request).message().text().strip();
    }
}
