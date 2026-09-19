package dev.agentkit.host.repo;

import java.util.List;
import java.util.Objects;

/**
 * One case from {@code routing.yaml}: a person, what was said before, the message they send, and who should answer it
 * — an agent, or the router itself, answering or asking. A pull request's rehearsal asks the router, and nothing else
 * runs.
 *
 * @param as     the person, by email, whose agents the router chooses among
 * @param before earlier turns of the conversation, oldest first
 * @param say    the message
 * @param expect who should answer it
 */
public record RoutingCase(String name, String as, List<Earlier> before, String say, Expect expect) {

    public RoutingCase {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(as, "as");
        before = before == null ? List.of() : List.copyOf(before);
        Objects.requireNonNull(say, "say");
        Objects.requireNonNull(expect, "expect");
    }

    /** An earlier turn: what was said, the agent that answered (null for the router), and what it answered. */
    public record Earlier(String say, String agent, String answer) {
    }

    /**
     * Who should answer: exactly one of an agent's id, the router answering, or the router asking.
     */
    public record Expect(String agent, boolean answers, boolean asks) {

        public String describe() {
            return agent != null ? "goes to " + agent : answers ? "the router answers" : "the router asks";
        }
    }
}
