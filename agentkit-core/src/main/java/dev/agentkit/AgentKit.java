package dev.agentkit;

/**
 * Top-level metadata for the AgentKit framework.
 *
 * <p>AgentKit is a provider-agnostic Java framework for building reliable,
 * unsupervised, tool-using AI agents. It provides progressive disclosure of
 * tools and skills, a pluggable knowledge base, working and long-term memory,
 * deliberate context engineering, verification of agent actions, and
 * supervisor/subagent orchestration, with an optional Temporal integration for
 * durable execution.
 */
public final class AgentKit {

    /** The current framework version. */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    private AgentKit() {
        // no instances
    }
}
