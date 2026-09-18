package dev.agentkit.host.repo;

import dev.agentkit.core.tool.ToolEffect;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One agent an organization offers, as its {@code agents/<id>/agent.yaml} and the prompt files it names say.
 *
 * <p>Everything here is text an operator writes. What must hold whatever the model concludes is not here: it is in
 * the connectors, which enforce their own rules, and in what the host enforces for every agent — tools chosen by
 * what they declare, confirmations, and arguments bound to the person asking.
 *
 * @param id           the directory name under {@code agents/}; stable, and what conversations and callers name
 * @param name         what a person sees
 * @param description  one sentence on what it is for
 * @param pattern      how a turn runs
 * @param model        the model id, or null for the organization's default
 * @param audience     who may use it: group names, or {@value #EVERYONE}
 * @param systemPrompt the system prompt's text
 * @param policy       the policy's text, appended to the system prompt; empty when there is none
 * @param tools        which connector tools it is given
 * @param confirm      tools that stop for the person's confirmation before they run
 * @param bind         arguments filled from the person asking, hidden from the model, by tool
 * @param maxSteps     how many steps one turn may take
 * @param maxTokens    the most a model call may produce
 */
public record AgentDefinition(String id, String name, String description, Pattern pattern, String model,
                              List<String> audience, String systemPrompt, String policy, List<ToolSelector> tools,
                              List<ToolRef> confirm, Map<ToolRef, Map<String, String>> bind, int maxSteps,
                              int maxTokens) {

    /** The audience that admits anyone in the organization. */
    public static final String EVERYONE = "everyone";

    /** How a turn runs. */
    public enum Pattern {
        /** One agent loop per turn, with the conversation so far. */
        CHAT
    }

    public AgentDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        Objects.requireNonNull(pattern, "pattern");
        audience = List.copyOf(audience);
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        policy = policy == null ? "" : policy;
        tools = List.copyOf(tools);
        confirm = List.copyOf(confirm);
        bind = Map.copyOf(bind);
    }

    /**
     * Which of a connector's tools an agent is given: all of them, or those with one of {@code effects}, or those named
     * in {@code tools} — or, with both, those named that also have one of the effects.
     */
    public record ToolSelector(String connector, Set<ToolEffect> effects, Set<String> tools) {
        public ToolSelector {
            Objects.requireNonNull(connector, "connector");
            effects = Set.copyOf(effects);
            tools = Set.copyOf(tools);
        }

        public boolean selects(String connectorName, String toolName, ToolEffect effect) {
            return connector.equals(connectorName) && (effects.isEmpty() || effects.contains(effect))
                    && (tools.isEmpty() || tools.contains(toolName));
        }
    }

    /** A connector's tool, written {@code connector/tool}; {@code connector/*} means every tool of it. */
    public record ToolRef(String connector, String tool) {
        public static final String ANY = "*";

        public ToolRef {
            Objects.requireNonNull(connector, "connector");
            Objects.requireNonNull(tool, "tool");
        }

        /** Parses {@code connector/tool}; empty on anything else. */
        public static java.util.Optional<ToolRef> parse(String text) {
            if (text == null) {
                return java.util.Optional.empty();
            }
            String[] parts = text.strip().split("/", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new ToolRef(parts[0].strip(), parts[1].strip()));
        }

        public boolean isWildcard() {
            return ANY.equals(tool);
        }

        public boolean matches(String connectorName, String toolName) {
            return connector.equals(connectorName) && (isWildcard() || tool.equals(toolName));
        }

        @Override
        public String toString() {
            return connector + "/" + tool;
        }
    }
}
