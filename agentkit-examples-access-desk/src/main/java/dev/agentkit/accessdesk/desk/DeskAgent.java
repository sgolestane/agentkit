package dev.agentkit.accessdesk.desk;

import dev.agentkit.accessdesk.desk.CompanyClient.Person;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.reliability.Approver;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.ToolEffect;
import java.time.Instant;
import java.util.Set;

/**
 * How the desk's conversational agent is assembled — its tools, its prompt and its gate — shared by the
 * chat console, the MCP endpoint and the evals, so all three exercise the same agent.
 */
public final class DeskAgent {

    /** How many steps one turn may take before it has to answer. */
    public static final int MAX_STEPS = 20;

    /** Tools that stop for the person's confirmation before they run. */
    public static final Set<String> CONFIRMED = Set.of("decide_request");

    private DeskAgent() {
    }

    /**
     * The tools a conversation gets: the desk's own, plus the company systems' reads and notifications. The
     * company systems' grants and revocations are left out, so access only ever changes through the desk's
     * tools and their rules.
     */
    public static DeclaredTools conversationTools(DeskTools desk, DeclaredTools company) {
        return new DeclaredTools().addAll(desk.catalog())
                .addAll(company.where(info -> info.effect() == ToolEffect.READ || info.effect() == ToolEffect.NOTIFY));
    }

    /** The system prompt for a turn: the desk's prompt, the customer's policy, who is asking, and the time. */
    public static String systemPrompt(DeskConfig config, Person me, Instant now) {
        return config.systemPrompt() + "\n\n" + config.policy() + "\n\nYou are talking to:\n"
                + "- email: " + me.email() + "\n- name: " + me.name() + "\n- title: " + me.title() + "\n- department: "
                + me.department() + "\n- manager: " + (me.manager() == null || me.manager().isBlank() ? "none" : me.manager())
                + "\n\nThe time now is " + now + " (UTC).";
    }

    public static AgentConfig agentConfig(String model, String systemPrompt) {
        return AgentConfig.builder(model).systemPrompt(systemPrompt).maxSteps(MAX_STEPS).maxTokens(2048).build();
    }

    /** Stops {@link #CONFIRMED} tools for {@code approver}'s decision. */
    public static ToolGate gate(Approver approver) {
        return ToolGates.requireApproval(invocation -> CONFIRMED.contains(invocation.name()), approver);
    }
}
