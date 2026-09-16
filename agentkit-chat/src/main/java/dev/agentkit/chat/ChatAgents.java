package dev.agentkit.chat;

import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.tool.ToolRegistry;
import java.util.Objects;

/**
 * The one door a chat console builds an agent through.
 *
 * <h2>Streaming, which is off by default and must not be</h2>
 *
 * <p>{@code Agent.Builder.streaming} defaults to {@code false}, and with it off
 * {@link dev.agentkit.core.agent.AgentObserver#onTextDelta} never fires — so a console wired
 * to every other callback correctly shows nothing at all until the whole answer lands, and
 * looks like a hang rather than a bug. Both shipped adapters emit real deltas already; the
 * only thing between this repository and a streaming console was one builder call that is
 * easy to leave out and produces no error when you do.
 *
 * <p>So it is not a parameter here. A console that wanted it off would be a console with no
 * reason to exist, and making it a choice means a later caller can make the wrong one
 * silently. {@code AChatAgentAlwaysStreamsTest} pins it.
 *
 * <h2>What else this fixes in one place</h2>
 *
 * <p>The {@link ChatObserver} is attached here rather than by each caller, because an agent
 * built without it runs perfectly and records nothing — the failure mode
 * {@code AgentObserver}'s own javadoc calls "silently never called". Everything else about
 * the agent is the caller's: the gate, the trust floor, the context strategy and the system
 * prompt are all decisions a deployment makes, and this returns the builder so it can make
 * them.
 */
public final class ChatAgents {

    private ChatAgents() {
    }

    /**
     * A builder that already streams and already records into the conversation.
     *
     * <p>Returned rather than built, so the caller still chooses its gate, its trust floor and
     * its name. Calling {@code streaming(false)} on it is possible and is the caller saying
     * they mean it; nothing here can stop that, and nothing should.
     */
    public static Agent.Builder builder(LlmClient llm, ToolRegistry tools, AgentConfig config,
            ChatStore store, ChatEvents events, String tenantId, String conversationId,
            String turnId) {
        Objects.requireNonNull(llm, "llm");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(config, "config");
        return Agent.builder(llm, tools, config)
                .streaming(true)
                .observer(new ChatObserver(store, events, tenantId, conversationId, turnId));
    }
}
