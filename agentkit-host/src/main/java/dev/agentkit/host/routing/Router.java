package dev.agentkit.host.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.OutputSchema;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.OneLine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decides, for one message in a conversation pinned to no agent, who answers it: one of the agents the person may use,
 * or the router itself — with a short answer about what the agents do, or a question when it cannot tell.
 *
 * <p>It chooses and does nothing else. It has no tools; its choice is constrained to the ids of the agents it is shown,
 * which are the ones the person is in the audience of; and the agent it chooses carries the message out under its own
 * rules, as if the person had chosen it.
 */
public final class Router {

    /** How much of an earlier answer the router is shown. */
    static final int EARLIER_ANSWER_CHARS = 400;

    /** How many earlier turns the router is shown. */
    static final int EARLIER_TURNS = 6;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The router's own rules; an organization adds to them, and cannot take them away. */
    public static final String INSTRUCTIONS = """
            You route one message in a conversation to the agent that should handle it, or answer it yourself.

            - Choose an agent ("agent") when the message asks for something to be done, or continues what an agent is \
            already doing: a reply to its question, a "yes", a correction. A follow-up stays with the agent that \
            answered last unless it clearly asks for something another agent does.
            - Answer yourself ("answer") only when the message is about the agents themselves: what they can do, how to \
            start one, what a form asks for. You may write a form out as a fill-in text template. Anything about a \
            request, a grant, a task or its status goes to the agent that handled it, even if an earlier answer seems \
            to say it: only that agent can check. Never say something was done, and never make up a policy.
            - Ask ("ask") one short question when two agents could fit, or when you cannot tell what is wanted. Name the \
            agents that could help.
            - "why" is one short sentence. "agent" is the chosen agent's id, or "" when you answer or ask. "text" is your \
            answer or question, or "" when you choose an agent.
            - "text" is Markdown, read in a chat window: short paragraphs and lists. Write a form out as a template \
            inside a fenced code block, one field per line, so it can be copied as it is.
            """;

    /** An agent the person may use, as the router is shown it. */
    public record Offered(String id, String name, String description, String form) {
        public Offered {
            Objects.requireNonNull(id, "id");
            name = name == null ? id : name;
            description = description == null ? "" : description;
            form = form == null ? "" : form;
        }
    }

    /** An earlier turn: what was said, which agent answered (null for the router), and what it answered. */
    public record Earlier(String said, String agent, String answer) {
    }

    /** What the router decided. */
    public sealed interface Decision {
        String why();
    }

    /** The message goes to {@code agent}. */
    public record ToAgent(String agent, String why) implements Decision {
    }

    /** The router answers, and no agent runs. */
    public record Answer(String text, String why) implements Decision {
    }

    /** The router asks the person which they mean. */
    public record Ask(String text, String why) implements Decision {
    }

    private Router() {
    }

    /**
     * Decides who answers {@code message}.
     *
     * @param extra the organization's own routing instructions, added after the router's; empty for none
     * @throws IllegalStateException when the model's answer is not a decision about the agents it was shown
     */
    public static Decision decide(LlmClient llm, String model, String extra, List<Offered> agents, List<Earlier> earlier,
                                  String message) {
        if (agents.isEmpty()) {
            return new Answer("No agent is available to you here.", "no agents");
        }
        List<Object> ids = new ArrayList<>(agents.stream().map(Offered::id).toList());
        ids.add("");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("why", Map.of("type", "string"));
        properties.put("action", Map.of("type", "string", "enum", List.of("agent", "answer", "ask")));
        properties.put("agent", Map.of("type", "string", "enum", ids));
        properties.put("text", Map.of("type", "string"));

        StringBuilder system = new StringBuilder(INSTRUCTIONS);
        if (extra != null && !extra.isBlank()) {
            system.append("\nThis organization adds:\n").append(extra.strip()).append('\n');
        }
        StringBuilder catalog = new StringBuilder();
        for (Offered agent : agents) {
            catalog.append("- ").append(agent.id()).append(": ").append(OneLine.of(agent.name()));
            if (!agent.description().isBlank()) {
                catalog.append(" — ").append(OneLine.of(agent.description()));
            }
            catalog.append('\n');
            if (!agent.form().isBlank()) {
                catalog.append("  Its form:\n").append(agent.form().strip().indent(4));
            }
        }
        StringBuilder before = new StringBuilder();
        for (Earlier turn : earlier.subList(Math.max(0, earlier.size() - EARLIER_TURNS), earlier.size())) {
            before.append("Person: ").append(Cut.to(OneLine.of(turn.said()), EARLIER_ANSWER_CHARS)).append('\n')
                    .append(turn.agent() == null ? "Router" : "Agent " + turn.agent()).append(": ")
                    .append(Cut.to(OneLine.of(turn.answer()), EARLIER_ANSWER_CHARS)).append('\n');
        }
        // The agents' descriptions, the conversation and the message are all somebody else's words: evidence to route
        // by, not instructions to follow.
        String prompt = "The agents:\n" + Spotlight.wrap(Spotlight.Kind.EVIDENCE, Source.of("agents"), catalog.toString())
                + (before.isEmpty() ? "" : "\n\nThe conversation so far:\n"
                        + Spotlight.wrap(Spotlight.Kind.EVIDENCE, Source.of("conversation"), before.toString()))
                + "\n\nThe new message:\n" + Spotlight.wrap(Spotlight.Kind.EVIDENCE, Source.of("message"), message);

        LlmResponse response = llm.generate(LlmRequest.builder(model)
                .system(Spotlight.withInstruction(system.toString()))
                .maxTokens(1024)
                .addMessage(Message.user(prompt))
                .outputSchema(OutputSchema.ofProperties("route", properties))
                .build());
        return parse(response.message().text(), agents);
    }

    /** The model's answer as a decision about {@code agents}, or an exception if it is not one. */
    static Decision parse(String text, List<Offered> agents) {
        JsonNode node;
        try {
            node = JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("The router's answer was not JSON: " + Cut.to(text, 200), e);
        }
        String action = node.path("action").asText("");
        String why = node.path("why").asText("");
        String reply = node.path("text").asText("");
        String agent = node.path("agent").asText("");
        return switch (action) {
            case "agent" -> {
                if (agents.stream().noneMatch(one -> one.id().equals(agent))) {
                    throw new IllegalStateException("The router chose " + agent + ", which is not an agent it was shown");
                }
                yield new ToAgent(agent, why);
            }
            case "answer" -> new Answer(reply.isBlank() ? "I can't help with that here." : reply, why);
            case "ask" -> new Ask(reply.isBlank() ? "Which of the agents is this for?" : reply, why);
            default -> throw new IllegalStateException("The router's answer had no action it knows: " + action);
        };
    }
}
