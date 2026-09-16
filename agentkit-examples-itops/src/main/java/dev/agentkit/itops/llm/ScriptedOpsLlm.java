package dev.agentkit.itops.llm;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic stand-in for the model, so the whole platform runs with no API key.
 *
 * <p>It is not a mock in the testing sense: it implements {@code LlmClient} and the runtime
 * cannot tell the difference, so every part of the system under it — the tool registry,
 * progressive disclosure, the supervisor, parking, the audit trail — runs for real. What is
 * scripted is only the judgement, and it is scripted as rules over the transcript rather
 * than as a fixed sequence, so it reacts: a denied action ends the run, an ambiguous
 * directory lookup ends the run with a question, and an approved action resumes where it
 * left off.
 *
 * <p>Set {@code ITOPS_LLM=anthropic} with {@code ANTHROPIC_API_KEY} to run the same platform
 * against a real model. Nothing else changes, which is the property worth demonstrating —
 * the safety story does not depend on which model is behind this interface, because none of
 * it is enforced by the model.
 */
public final class ScriptedOpsLlm implements LlmClient {

    private static final Pattern TICKET = Pattern.compile("INC\\d+");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}");

    /** The groups this stand-in can recognise by name; a real model reads them from the text. */
    private static final List<String> KNOWN_GROUPS = List.of(
            "Finance Application Users", "Production-Administrators", "Finance Admin",
            "Employees-All");

    /** One thing to do next: a tool call, or the closing answer. */
    private record Step(String tool, Map<String, Object> arguments, String text) {
        static Step call(String tool, Map<String, Object> arguments) {
            return new Step(tool, arguments, null);
        }

        static Step say(String text) {
            return new Step(null, Map.of(), text);
        }
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        List<Message> messages = request.messages();
        String goal = firstUserText(messages);
        List<String> calls = toolCallsSoFar(messages);
        String lastResult = lastToolResult(messages);

        Step step = decide(goal, knowledge(messages), calls, lastResult);
        if (step.tool() == null) {
            return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(step.text())),
                    LlmStopReason.END_TURN, TokenUsage.ZERO);
        }
        // Through ProposedCall.of, which is what every provider adapter in this repository
        // does at its parse site (#277). This one is a demo's model rather than a test's
        // fixture, and that makes it matter more rather than less: the whole claim of this
        // module is that "nothing else changes" when ITOPS_LLM=anthropic swaps a real
        // client in behind this interface, and a stand-in that throws where every real
        // client carries a refusal is a place where something else did change.
        return LlmResponse.of(Message.of(Role.ASSISTANT,
                        ProposedCall.of("call-" + (calls.size() + 1), step.tool(),
                                step.arguments())),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO);
    }

    /**
     * What to do next, given the goal and what has happened.
     *
     * <p>The early exit comes first and matters more than the plan. A denial is final — the
     * platform's instruction is to stop rather than look for another route, and a stand-in
     * that ignored that would make the demo prove less than it appears to.
     *
     * <p><strong>There used to be two more, and removing them is the point of #157.</strong>
     * They matched the supervisor's park messages — {@code "requires human approval"} and
     * {@code "parked awaiting human approval"} — and ended the run politely. They were the
     * stand-in cooperating with a guarantee it was not supposed to be part of: the
     * supervisor could only deny a parked call, so the run went on being asked what to do
     * next, and only the stand-in's good manners stopped it proposing the same privileged
     * action twenty-two more times. Measured with a stand-in that did not cooperate: 24
     * model turns and 23 refused proposals for one approval.
     *
     * <p>The {@link dev.agentkit.itops.runtime.Supervisor} now returns
     * {@code GateResult.needsAPerson}, so the agent loop ends the run itself and this is
     * never asked again after a park. Deleting the branches is what makes the demo prove the
     * thing it claims — that none of the safety properties are enforced by the model — and
     * the scenario tests are the measurement: a regression to a plain denial walks this
     * stand-in straight on through the rest of its plan.
     */
    private Step decide(String goal, String knowledge, List<String> calls, String lastResult) {
        String result = lastResult == null ? "" : lastResult;
        if (result.contains("identity is NOT established")) {
            return Step.say("More than one person matches that name, so I have not acted. Tell me "
                    + "which account is meant and I will continue.");
        }

        List<Step> plan = plan(goal, knowledge);
        int done = calls.size();
        return done < plan.size() ? plan.get(done)
                : Step.say("Done. See the ticket comment and the execution's audit trail for what "
                        + "was checked, changed and verified.");
    }

    /**
     * The sequence this stand-in would follow for the goal it was given.
     *
     * <p>Written as data rather than as control flow so the reading is obvious: read, declare
     * capability, take ownership, establish the facts, act, <em>verify the effect</em>,
     * write it down, close. The verification step after the change is not decoration — the
     * platform's rule is that a tool returning success proves a call was accepted, not that
     * the world changed.
     */
    private List<Step> plan(String goal, String knowledge) {
        // The ticket id comes from the goal, which the platform wrote. Everything else comes
        // from what has been read back so far, because in chat the goal is three words —
        // "fix INC0012345" — and the details only exist once the ticket has been retrieved.
        // A real model works the same way; scripting it otherwise would let the demo skip
        // the read and prove less than it appears to.
        String ticket = match(TICKET, goal);
        String email = match(EMAIL, knowledge);
        String group = KNOWN_GROUPS.stream().filter(knowledge::contains).findFirst().orElse(null);
        String lower = knowledge.toLowerCase(Locale.ROOT);
        boolean resumingApproved = goal.contains("A human has approved the action");
        // The scheduled path hands the ticket over already fenced inside the goal; the chat
        // path does not, so the first thing to do there is read it.
        //
        // Sniffed on IntakeWorker.goalFor's own heading rather than on "<untrusted", which
        // is what this used to look for. #141 fences the approved arguments into every
        // RESUMED goal, so a fence in the goal stopped meaning "the ticket is here": a
        // chat-triggered resume flipped this false and skipped get_ticket, which is the
        // module's own first rule. No test caught it, because the scenario tests resume from
        // the scheduled path where the flag was already false.
        boolean mustReadFirst = ticket != null && !goal.contains("The ticket, as filed:");
        List<Step> read = mustReadFirst
                ? List.of(Step.call("ticketing.get_ticket", args("ticket_id", ticket)))
                : List.of();

        if (lower.contains("replace") && lower.contains("monitor")) {
            return concat(read,
                    Step.call("report_capability", args("verdict", "UNSUPPORTED",
                            "reason", "This needs someone physically present to swap hardware. "
                                    + "I have no capability that reaches a desk, so I am leaving "
                                    + "the ticket untouched for a human to pick up.")),
                    Step.say("I cannot handle this one: replacing a monitor needs a person at the "
                            + "desk. I have left the ticket unchanged and unassigned."));
        }

        if (lower.contains("delete") && email != null) {
            List<Step> steps = new ArrayList<>(read);
            steps.addAll(List.of(
                    Step.call("search_tools", args("query",
                            "identity lifecycle write delete account directory lookup")),
                    Step.call("report_capability", args("verdict", "SUPPORTED",
                            "reason", "Account lifecycle is within my identity capability; I will "
                                    + "confirm employment status before proposing anything.")),
                    Step.call("directory.search_employee", args("query", email)),
                    Step.call("identity.find_user", args("email", email))));
            if (ticket != null && !resumingApproved) {
                steps.add(read.size() + 2,
                        Step.call("ticketing.assign_ticket", args("ticket_id", ticket)));
            }
            steps.add(Step.call("identity.delete_user", args("email", email)));
            // Reached only once the deletion actually ran, i.e. after an approval.
            steps.add(Step.call("identity.find_user", args("email", email)));
            if (ticket != null) {
                steps.add(Step.call("ticketing.add_comment", args("ticket_id", ticket,
                        "body", "Confirmed in the employee directory that this person is "
                                + "terminated, then deleted the account " + email + " after human "
                                + "approval. Verified afterwards that the account no longer "
                                + "resolves.")));
                steps.add(Step.call("ticketing.resolve_ticket", args("ticket_id", ticket)));
                steps.add(Step.call("ticketing.close_ticket", args("ticket_id", ticket)));
            }
            return steps;
        }

        if (group != null && email != null) {
            List<Step> steps = new ArrayList<>(read);
            steps.addAll(List.of(
                    Step.call("search_tools", args("query",
                            "identity group membership write directory lookup")),
                    Step.call("report_capability", args("verdict", "SUPPORTED",
                            "reason", "This is a group membership change, which my identity "
                                    + "capability covers.")),
                    Step.call("directory.search_employee", args("query", email)),
                    Step.call("identity.find_group", args("group", group)),
                    Step.call("identity.get_group_members", args("group", group)),
                    Step.call("identity.add_user_to_group", args("user", email, "group", group)),
                    // Post-action verification: read the membership back rather than
                    // trusting the write's own report of itself.
                    Step.call("identity.get_group_members", args("group", group))));
            if (ticket != null) {
                steps.add(read.size() + 2,
                        Step.call("ticketing.assign_ticket", args("ticket_id", ticket)));
                steps.add(Step.call("ticketing.add_comment", args("ticket_id", ticket,
                        "body", "Added " + email + " to " + group + ". Confirmed the account and "
                                + "the group both exist, that the group is not privileged, and "
                                + "read the membership back afterwards to verify the change.")));
                steps.add(Step.call("ticketing.resolve_ticket", args("ticket_id", ticket)));
                steps.add(Step.call("ticketing.close_ticket", args("ticket_id", ticket)));
            }
            return steps;
        }

        if (ticket != null) {
            return concat(read,
                    Step.say("I have the ticket but cannot tell what it needs. Tell me what you "
                            + "would like done with it."));
        }

        if (lower.contains("ticket") || lower.contains("changed") || lower.contains("assigned")) {
            return List.of(
                    Step.call("ticketing.search_tickets", args("lookback_minutes", 1440,
                            "limit", 10)),
                    Step.say("Those are the tickets I can see. Ask me to work one by number, for "
                            + "example \"fix INC0012345\"."));
        }

        return List.of(Step.say("I can search and work tickets, look people up in the directory, "
                + "and change group membership or account status through the identity provider. "
                + "Try \"show me recent tickets\" or \"fix INC0012345\"."));
    }

    /** Everything this run has read, which is what a real model would be reasoning over. */
    private static String knowledge(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            for (ContentBlock block : message.content()) {
                if (block instanceof TextBlock text) {
                    sb.append(text.text()).append('\n');
                } else if (block instanceof ToolResultBlock result) {
                    sb.append(result.content()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static List<Step> concat(List<Step> head, Step... tail) {
        List<Step> steps = new ArrayList<>(head);
        steps.addAll(List.of(tail));
        return steps;
    }

    private static Map<String, Object> args(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }

    private static String match(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group() : null;
    }

    private static String firstUserText(List<Message> messages) {
        for (Message message : messages) {
            if (message.role() == Role.USER) {
                for (ContentBlock block : message.content()) {
                    if (block instanceof TextBlock text) {
                        return text.text();
                    }
                }
            }
        }
        return "";
    }

    private static List<String> toolCallsSoFar(List<Message> messages) {
        List<String> names = new ArrayList<>();
        for (Message message : messages) {
            if (message.role() != Role.ASSISTANT) {
                continue;
            }
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolUseBlock use) {
                    names.add(use.name());
                }
            }
        }
        return names;
    }

    private static String lastToolResult(List<Message> messages) {
        String last = null;
        for (Message message : messages) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolResultBlock result) {
                    last = result.content();
                }
            }
        }
        return last;
    }
}
