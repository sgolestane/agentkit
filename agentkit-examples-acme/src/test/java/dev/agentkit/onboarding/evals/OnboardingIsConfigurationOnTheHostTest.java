package dev.agentkit.onboarding.evals;

import static dev.agentkit.acme.ScriptedModel.text;
import static dev.agentkit.acme.ScriptedModel.toolUse;
import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.accessdesk.desk.AccessLedger;
import dev.agentkit.accessdesk.systems.CompanySystems;
import dev.agentkit.acme.AcmeOnTheHost;
import dev.agentkit.acme.ScriptedModel;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Turn;
import dev.agentkit.core.deferred.DeferredActionStore;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.ToolSpec;
import dev.agentkit.host.DeferredWork;
import dev.agentkit.host.HostedAgent;
import dev.agentkit.host.Principal;
import dev.agentkit.onboarding.OnboardingSystems;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Onboarding with no onboarding code in the product path: {@code orgs/acme}'s agent on the host, the onboarding systems
 * as its connector over HTTP — without a model, so what is pinned is the wiring the evals depend on: who is offered it,
 * the form, the confirmations, whom grants are done for, and the offboarding it schedules.
 */
class OnboardingIsConfigurationOnTheHostTest {

    private static final String MARCUS = "marcus.bell@acme.example";
    private static final String LENA = "lena.ortiz@acme.example";
    private static final String DANA = "dana.kim@acme.example";
    private static final String PRIYA = "priya.natarajan@acme.example";

    private final OnboardingSystems systems = OnboardingSystems.open("onboarding", 0);
    private final DeferredActionStore store = DeferredActionStore.inMemory();
    private final List<String> confirmations = new CopyOnWriteArrayList<>();

    private AcmeOnTheHost start(LlmClient llm) {
        return AcmeOnTheHost.start(CompanySystems.open(null), AccessLedger.open(null), systems, AcmeOnTheHost.storeFor("onboarding", store),
                () -> OnboardingEvalTest.NOW, llm);
    }

    @Test
    void itIsOfferedToManagersOnly() {
        try (AcmeOnTheHost acme = start(new ScriptedModel())) {
            assertThat(acme.chat().available(acme.tenant(LENA))).extracting(a -> a.get("id"))
                    .contains("onboarding", "access-desk");
            assertThat(acme.chat().available(acme.tenant(PRIYA))).extracting(a -> a.get("id"))
                    .containsExactly("access-desk");
        }
    }

    @Test
    void aManagerStartsItFromTheFormAndEachGrantWaitsForThemAndIsDoneAsThem() throws Exception {
        ScriptedModel llm = new ScriptedModel(
                text("1. Assign a Salesforce seat to marcus.bell@acme.example.\n"
                        + "2. Send lena.ortiz@acme.example a Slack message reporting what was done."),
                // The model names someone else as asking; the host puts the person back.
                toolUse("salesforce_assign_seat", Map.of("email", MARCUS, "requested_by", DANA)),
                text("Assigned a Salesforce seat."),
                toolUse("slack_send_message", Map.of("to_email", LENA, "text", "Marcus has a Salesforce seat.")),
                text("Told Lena."));
        try (AcmeOnTheHost acme = start(llm)) {
            Turn turn = onboard(acme, LENA, OnboardingEvalTest.form(systems.worker("W-1002")));

            assertThat(turn.state()).as(turn.detail()).isEqualTo(Turn.State.COMPLETED);
            assertThat(llm.requests.get(0).system()).hasValueSatisfying(system -> assertThat(system)
                    .contains("IT onboarding planner").contains("Onboarding policy:").contains(LENA));
            assertThat(llm.requests.get(0).messages().toString()).contains("I am their manager")
                    .contains("- employee_id: W-1002").contains("- termination_date: 2026-12-31")
                    .contains("- rehire: false").doesNotContain("- github_username:");
            List<ToolSpec> tools = llm.requests.get(1).tools();
            assertThat(tools).extracting(ToolSpec::name).contains("hris_get_worker", "okta_create_user",
                    "okta_deactivate_user", "slack_request_github_username", "schedule_deferred_action", "ask_person");
            assertThat(tools).allSatisfy(t -> assertThat(t.inputSchema().toString()).doesNotContain("requested_by"));
            assertThat(confirmations).containsExactly("salesforce_assign_seat");
            assertThat(systems.salesforceSeats()).containsExactly(MARCUS);
        }
    }

    @Test
    void aManagerCannotOnboardSomeoneElsesHire() throws Exception {
        ScriptedModel llm = new ScriptedModel();
        try (AcmeOnTheHost acme = start(llm)) {
            Turn turn = onboard(acme, DANA, OnboardingEvalTest.form(systems.worker("W-1002")));

            // Refused by the check before planning: no plan is made, no model is asked, and nothing is done.
            assertThat(turn.answer()).isEqualTo("Nothing was done. Only Marcus Bell's manager can onboard them, and "
                    + "dana.kim@acme.example is not.");
            assertThat(llm.requests).isEmpty();
            assertThat(systems.salesforceSeats()).isEmpty();
        }
    }

    @Test
    void theOffboardingItSchedulesRunsLaterAsTheAgentWithOnlyRemovals() throws Exception {
        ScriptedModel llm = new ScriptedModel(
                toolUse("okta_create_user", Map.of("email", "eve@evil.example", "first_name", "Eve", "last_name", "X",
                        "groups", List.of("all-staff"))),
                toolUse("salesforce_release_seat", Map.of("email", MARCUS)),
                text("Released Marcus's Salesforce seat."));
        try (AcmeOnTheHost acme = start(llm)) {
            call("salesforce_assign_seat", Map.of("email", MARCUS, "requested_by", LENA));

            ToolResult byDana = schedule(acme, DANA, "W-1002");
            ToolResult byLena = schedule(acme, LENA, "W-1002");

            assertThat(byDana.isError()).isTrue();
            assertThat(byLena.isError()).as(byLena.content()).isFalse();
            assertThat(byLena.content()).contains("[salesforce]");
            assertThat(store.all()).singleElement().satisfies(action -> {
                assertThat(action.subjectId()).isEqualTo("W-1002");
                assertThat(action.runAt()).isEqualTo(Instant.parse("2026-12-31T00:00:00Z"));
            });

            DeferredWork later = new DeferredWork(acme.org(), AcmeOnTheHost.storeFor("onboarding", store),
                    () -> Instant.parse("2026-12-31T00:01:00Z"), Optional.of(llm));
            assertThat(later.runDue()).isEqualTo(1);
            assertThat(store.all()).singleElement().satisfies(action -> assertThat(action.status())
                    .as(action.outcome()).isEqualTo(dev.agentkit.core.deferred.DeferredAction.Status.DONE));

            assertThat(llm.requests.get(0).tools()).extracting(ToolSpec::name).contains("salesforce_release_seat")
                    .doesNotContain("okta_create_user", "salesforce_assign_seat", "schedule_deferred_action");
            assertThat(systems.salesforceSeats()).isEmpty();
            assertThat(systems.oktaCreated()).isEmpty();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Starts Onboarding from its form as {@code who}, approving every confirmation, and waits for the turn. */
    private Turn onboard(AcmeOnTheHost acme, String who, Map<String, Object> form) throws InterruptedException {
        ChatRuntime runtime = acme.runtime();
        String tenant = acme.tenant(who);
        Conversation conversation = acme.start(who, "onboarding", "test");
        Turn turn = runtime.say(tenant, conversation.id(), acme.chat().message(tenant, conversation, form), List.of());
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            for (ChatRuntime.PendingDecision pending : runtime.pending(tenant)) {
                confirmations.add(pending.tool());
                runtime.decide(tenant, pending.id(), ApprovalDecision.approve(), tenant);
            }
            Optional<Turn> now = runtime.store().turn(tenant, conversation.id(), turn.id());
            if (now.isPresent() && now.get().state().isTerminal()) {
                return now.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("The turn did not finish");
    }

    /** Schedules Marcus's offboarding as {@code who}, through the tool the host gives Onboarding. */
    private static ToolResult schedule(AcmeOnTheHost acme, String who, String subject) {
        HostedAgent onboarding = acme.org().current().agent("onboarding").orElseThrow();
        Principal principal = acme.org().current().principal(who).orElseThrow();
        return acme.deferred().schedulerFor(onboarding, principal).orElseThrow().execute(new ToolInvocation("s",
                "schedule_deferred_action", new HashMap<>(Map.of("subject_kind", "worker", "subject_id", subject,
                "goal", "Release the Salesforce seat of " + MARCUS + " and tell " + LENA + ".",
                "relative_to", "termination_date", "offset_days", 0))));
    }

    private void call(String name, Map<String, Object> args) {
        ToolResult result = systems.catalog().entry(name).orElseThrow().tool()
                .execute(new ToolInvocation("setup", name, new HashMap<>(args)));
        assertThat(result.isError()).as(result.content()).isFalse();
    }
}
