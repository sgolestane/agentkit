package dev.agentkit.mcp.server;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code tools/call} a tool is running inside, when the client can be asked something while it runs.
 *
 * <p>MCP lets a server ask the client's person a question in the middle of a call ({@code elicitation/create}): the
 * client shows it to them — not to its model — and sends back their answer. That is how a tool served over MCP gets
 * a person's confirmation without sending them somewhere else. A tool reaches it through {@link #current()}, which is
 * present only while a call is being served by {@link HttpMcpEndpoint} to a client that said, when it connected, that it
 * can be asked.
 *
 * <p>The question waits for the answer; {@link #elicit} gives up after its patience and reports {@link Action#CANCEL}.
 */
public interface McpCall {

    /** How the person answered. */
    enum Action {
        /** They answered; the content holds what they entered. */
        ACCEPT,
        /** They said no. */
        DECLINE,
        /** They dismissed it, or it could not be put to them. */
        CANCEL
    }

    /** An answer. {@code content} is empty unless {@code action} is {@link Action#ACCEPT}. */
    record Answer(Action action, Map<String, Object> content) {
        public Answer {
            Objects.requireNonNull(action, "action");
            content = content == null ? Map.of() : Map.copyOf(content);
        }

        public boolean accepted() {
            return action == Action.ACCEPT;
        }
    }

    /**
     * Asks the person {@code message}, with a form described by {@code requestedSchema} — a flat JSON Schema object of
     * strings, numbers, booleans and enums, as the protocol allows — and waits up to {@code patience} for the answer.
     */
    Answer elicit(String message, Map<String, Object> requestedSchema, Duration patience);

    /** The call being served on this thread, if its client can be asked something. */
    static Optional<McpCall> current() {
        return Optional.ofNullable(HttpMcpEndpoint.CURRENT.get());
    }
}
