package dev.agentkit.host.repo;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One case of an agent's evals, from {@code agents/<id>/evals.yaml}: a conversation with the agent, as someone in the
 * organization's directory, and what must be true of how it went.
 *
 * <pre>
 * cases:
 *   - name: low-risk-grant
 *     as: priya@acme.example
 *     say: I need read access to the payments dashboards for 3 hours.   # or input: {the agent's form}
 *     answers: ["It's for INC-4211."]                                   # what the person says when asked
 *     expect:
 *       - calls: grant_low_risk_access
 *         with: {resource_id: datadog-payments}
 *       - never: submit_access_request
 *       - asks: false
 *       - answer_contains: 3 hours
 *       - judge: The answer says the access ends on its own.
 * </pre>
 *
 * A pull request rehearses the cases of every agent it changes, with every tool that would change something refused and
 * recorded — so what is checked is what the agent set out to do, which is the part a change to a prompt or a policy
 * changes.
 *
 * @param name    unique among the agent's cases
 * @param as      the email of the person, in the directory
 * @param say     what they say; null when the case starts from {@code input}
 * @param input   the agent's form, filled in; null when the case starts from {@code say}
 * @param answers what the person answers when asked, in order
 * @param expect  what must be true, at least one
 */
public record EvalCase(String name, String as, String say, Map<String, Object> input, List<String> answers,
                       List<Expectation> expect) {

    public EvalCase {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(as, "as");
        answers = List.copyOf(answers);
        expect = List.copyOf(expect);
    }

    /**
     * One thing that must be true of a case's conversation.
     *
     * @param kind what is checked
     * @param tool the tool, for {@link Kind#CALLS} and {@link Kind#NEVER}
     * @param with arguments the call must carry (a value, or a list the argument's list holds all of); empty for any
     * @param asks for {@link Kind#ASKS}: whether the agent asks the person anything
     * @param text for {@link Kind#ANSWER_CONTAINS} (case-insensitive) and {@link Kind#JUDGE} (a rubric)
     */
    public record Expectation(Kind kind, String tool, Map<String, Object> with, boolean asks, String text) {

        public enum Kind {
            /** The agent calls the tool, with these arguments if given: refused or not, it set out to. */
            CALLS,
            /** The agent never calls the tool (with these arguments, if given). */
            NEVER,
            /** Whether the agent asks the person anything. */
            ASKS,
            /** The answer contains the text, ignoring case. */
            ANSWER_CONTAINS,
            /** A model rules that the conversation satisfies the rubric. */
            JUDGE
        }

        public Expectation {
            Objects.requireNonNull(kind, "kind");
            with = with == null ? Map.of() : Map.copyOf(with);
        }

        /** How it reads in a report. */
        public String describe() {
            return switch (kind) {
                case CALLS -> "calls " + tool + (with.isEmpty() ? "" : " with " + with);
                case NEVER -> "never calls " + tool + (with.isEmpty() ? "" : " with " + with);
                case ASKS -> asks ? "asks the person something" : "asks the person nothing";
                case ANSWER_CONTAINS -> "answer contains \"" + text + "\"";
                case JUDGE -> "judged: " + text;
            };
        }
    }
}
