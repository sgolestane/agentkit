package dev.agentkit.examples.routine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.examples.routine.UnlockDesk.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A morning's queue through the unlock desk, with a scripted model standing in for a real one: the desk learns the
 * procedure from the first tickets, stops paying the model once it has settled, and — when a ticket does not fit —
 * hands the job back to the model without doing any of it twice. Judged by what happened to the accounts, not by
 * what anybody said happened.
 */
class UnlockDeskTest {

    private final AtomicInteger modelCalls = new AtomicInteger();

    /**
     * Follows {@link UnlockDesk#PROCEDURE} the way a well-behaved model would. Stateless: where a run has got to is
     * read from how many turns it has had, so one client can serve every run.
     */
    private final LlmClient procedure = new LlmClient() {
        @Override
        public LlmResponse generate(LlmRequest request) {
            modelCalls.incrementAndGet();
            String firstMessage = request.messages().getFirst().content().toString();
            String employee = parameter(firstMessage, "employee_email");
            String manager = parameter(firstMessage, "manager_email");
            int turn = (int) request.messages().stream().filter(m -> m.role() == Role.ASSISTANT).count();
            boolean hardwareToken = employee.startsWith("gus.");
            List<LlmResponse> steps = new ArrayList<>();
            if (firstMessage.contains("already been done")) {
                // Picking up a replay that stopped at the MFA reset: finish the job, redo nothing.
                steps.add(call("it_create_ticket", Map.of("for_email", employee, "category", "hardware_token_reset")));
                steps.add(notice(employee, "token_reset_pending", employee));
                steps.add(notice(manager, "manager_copy", employee));
            } else {
                steps.add(call("directory_lookup", Map.of("email", employee)));
                steps.add(call("okta_unlock", Map.of("email", employee)));
                steps.add(call("okta_reset_mfa", Map.of("email", employee)));
                if (hardwareToken) {
                    steps.add(call("it_create_ticket", Map.of("for_email", employee, "category", "hardware_token_reset")));
                }
                steps.add(notice(employee, hardwareToken ? "token_reset_pending" : "account_unlocked", employee));
                steps.add(notice(manager, "manager_copy", employee));
            }
            return turn < steps.size() ? steps.get(turn) : text("Unlocked " + employee + " and told their manager.");
        }
    };

    @Test
    void theDeskLearnsTheProcedureThenStopsPayingForItAndHandsBackWhatDoesNotFit() {
        UnlockDeskSystems systems = UnlockDeskSystems.seeded();
        UnlockDesk desk = new UnlockDesk(procedure, "scripted", systems, AgentObserver.NONE);

        List<UnlockDesk.Outcome> outcomes = new ArrayList<>();
        systems.accounts().forEach(account -> outcomes.add(desk.handle(account)));

        assertThat(outcomes).extracting(UnlockDesk.Outcome::path).containsExactly(
                Path.MODEL, Path.MODEL, Path.MODEL,                 // ana, ben, cara: learning
                Path.REPLAYED, Path.REPLAYED, Path.REPLAYED,        // dev, eve, finn: settled, no model
                Path.REPLAY_THEN_MODEL,                             // gus: hardware token, replay stops
                Path.MODEL, Path.MODEL, Path.MODEL);                // hana, ivan, jo: learning again
        assertThat(outcomes).filteredOn(o -> o.path() == Path.REPLAYED)
                .allSatisfy(o -> assertThat(o.modelCalls()).isZero());
        assertThat(outcomes.get(0).modelCalls()).isEqualTo(6);     // five tool calls and an answer
        assertThat(desk.routine()).isEqualTo(UnlockDesk.JOB + ": directory_lookup → okta_unlock → okta_reset_mfa"
                + " → notify → notify (seen 3 times)");
    }

    @Test
    void everyoneIsUnlockedOnceAndNothingIsDoneTwiceWhenTheModelTakesOver() {
        UnlockDeskSystems systems = UnlockDeskSystems.seeded();
        UnlockDesk desk = new UnlockDesk(procedure, "scripted", systems, AgentObserver.NONE);

        systems.accounts().forEach(desk::handle);

        List<String> everyone = systems.accounts().stream().map(UnlockDeskSystems.Account::email).toList();
        assertThat(systems.unlocked()).containsExactlyElementsOf(everyone);
        assertThat(systems.accounts()).noneMatch(UnlockDeskSystems.Account::locked);
        assertThat(systems.mfaReset()).hasSize(9).doesNotContain("gus.reyes@acme.example");
        assertThat(systems.tickets()).containsExactly(
                new UnlockDeskSystems.Ticket("gus.reyes@acme.example", "hardware_token_reset"));
        assertThat(systems.notices()).hasSize(20);
        assertThat(systems.notices()).contains(
                new UnlockDeskSystems.Notice("gus.reyes@acme.example", "token_reset_pending", "gus.reyes@acme.example"),
                new UnlockDeskSystems.Notice("dana.kim@acme.example", "manager_copy", "gus.reyes@acme.example"),
                new UnlockDeskSystems.Notice("dev.patel@acme.example", "account_unlocked", "dev.patel@acme.example"),
                new UnlockDeskSystems.Notice("sam.okafor@acme.example", "manager_copy", "dev.patel@acme.example"));
    }

    @Test
    void theModelIsPaidForSevenTicketsOutOfTen() {
        UnlockDeskSystems systems = UnlockDeskSystems.seeded();
        UnlockDesk desk = new UnlockDesk(procedure, "scripted", systems, AgentObserver.NONE);

        systems.accounts().forEach(desk::handle);

        // Six turns for each of the six tickets worked out from scratch, and four for the one the model finished.
        assertThat(modelCalls.get()).isEqualTo(6 * 6 + 4);
    }

    private static String parameter(String text, String name) {
        Matcher matcher = Pattern.compile(name + ": ([\\w.@-]+)").matcher(text);
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    private static LlmResponse notice(String to, String template, String about) {
        return call("notify", Map.of("to_email", to, "template", template, "about_email", about));
    }

    private static int ids;

    private static LlmResponse call(String name, Map<String, Object> input) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, ProposedCall.of("t" + (++ids), name, input)),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO);
    }

    private static LlmResponse text(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)), LlmStopReason.END_TURN, TokenUsage.ZERO);
    }
}
