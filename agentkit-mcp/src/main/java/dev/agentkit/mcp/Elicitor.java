package dev.agentkit.mcp;

import java.util.Map;
import java.util.Objects;

/**
 * How a client answers a server that asks its person something in the middle of a call ({@code elicitation/create}):
 * shows them the message and a form, and returns what they chose. A client with one says so when it connects, and a
 * server may then ask; a client without one is never asked.
 */
@FunctionalInterface
public interface Elicitor {

    Reply answer(String message, Map<String, Object> requestedSchema);

    /** What the person chose: {@code accept} with what they entered, {@code decline}, or {@code cancel}. */
    record Reply(String action, Map<String, Object> content) {
        public Reply {
            Objects.requireNonNull(action, "action");
            if (!action.equals("accept") && !action.equals("decline") && !action.equals("cancel")) {
                throw new IllegalArgumentException("An answer is accept, decline or cancel, not " + action);
            }
            content = content == null ? Map.of() : Map.copyOf(content);
        }

        public static Reply accept(Map<String, Object> content) {
            return new Reply("accept", content);
        }

        public static Reply decline() {
            return new Reply("decline", Map.of());
        }

        public static Reply cancel() {
            return new Reply("cancel", Map.of());
        }
    }
}
