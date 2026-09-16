package dev.agentkit.chat;

import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tools an agent has because it is running inside a conversation.
 *
 * <p>There is one, and it is the one a console makes possible: the agent can ask the person
 * watching. Everything else an agent does is the application's to provide.
 */
public final class ChatTools {

    /** How long a question waits before the agent is told nobody answered. */
    public static final Duration DEFAULT_PATIENCE = Duration.ofMinutes(30);

    private ChatTools() {
    }

    /**
     * {@code ask_person} — put a question to whoever is watching, and wait.
     *
     * <p><strong>The answer is somebody else's words, and travels as such.</strong> It is typed
     * by an operator, and an operator can be wrong, hurried, or repeating something a requester
     * told them. So it comes back fenced through {@link Spotlight} and declared
     * {@link Provenance#THIRD_PARTY}, exactly as a fetched page would — which also means a run
     * that asks a question drops to the tightened policy afterwards, if the deployment sets a
     * trust floor. That is the correct reading: the run has taken in text it did not author.
     *
     * <p><strong>Nobody answering is not an error.</strong> The person closed the tab, or the
     * question was not worth their afternoon. The tool says so and the agent carries on with
     * what it had — an agent that treats silence as a failure and stops is one that needs
     * babysitting to finish anything.
     */
    public static Tool askPerson(ChatRuntime runtime, ChatRuntime.Session session) {
        return askPerson(runtime, session, DEFAULT_PATIENCE);
    }

    public static Tool askPerson(ChatRuntime runtime, ChatRuntime.Session session,
            Duration patience) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(session, "session");
        return FunctionTool.builder("ask_person",
                        "Ask the person you are talking to a question and wait for their "
                                + "answer. For something you cannot work out and they can — "
                                + "which of two people they mean, which ticket, whether a name "
                                + "is the same person. Not for permission to act: a gate asks "
                                + "that on your behalf. They may not answer.")
                .schema(Map.of("type", "object", "properties", Map.of(
                                "question", Map.of("type", "string",
                                        "description", "One question, in plain words.")),
                        "required", List.of("question")))
                // Reading an answer is not a side effect, and a rehearsal should still be able
                // to ask — a run being rehearsed is exactly when somebody is watching.
                .readOnly()
                .handler(invocation -> runtime
                        .ask(session.tenantId(), session.conversationId(), session.turnId(),
                                invocation.stringArgument("question"), patience)
                        .map(answer -> ToolResult.from(Provenance.THIRD_PARTY,
                                Spotlight.wrap(Source.of("person"), answer)))
                        .orElseGet(() -> ToolResult.ok(
                                "Nobody answered. Carry on with what you have, and say in your "
                                        + "reply what you assumed.")))
                .build();
    }
}
