package dev.agentkit.core.deferred;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolDeclaration;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A deferred action's goal is text a model wrote, run later on the operator's behalf. What it may do when it runs is
 * decided from the tools' declarations and the subject's record alone — shown here for a CRM account, with no HR or
 * access-control code anywhere.
 */
class ADeferredActionIsHeldToItsSubjectTest {

    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    private final List<String> ran = new CopyOnWriteArrayList<>();
    private final DeclaredTools tools = new DeclaredTools()
            .add(tool("crm_downgrade_account"), new ToolDeclaration("crm", ToolEffect.REVOKE, "account_id"))
            .add(tool("crm_extend_trial"), new ToolDeclaration("crm", ToolEffect.GRANT, "account_id"))
            .add(tool("crm_read_account"), new ToolDeclaration("crm", ToolEffect.READ, "account_id"))
            .add(tool("crm_export_all"), new ToolDeclaration("crm", ToolEffect.READ, null))
            .add(tool("email_send"), new ToolDeclaration("email", ToolEffect.NOTIFY, "to"))
            .add(tool("schedule_again"), new ToolDeclaration("scheduler", ToolEffect.SCHEDULE, "account_id"));

    private final AtomicReference<SubjectRecord> acme = new AtomicReference<>(new SubjectRecord("account", "ACC-42",
            Set.of("ACC-42"), Set.of("owner@vendor.example"), Map.of("name", "Acme Corp", "trial_end", "2026-10-15")));

    private final SubjectResolver crm = new SubjectResolver() {
        @Override
        public Set<String> kinds() {
            return Set.of("account");
        }

        @Override
        public Optional<SubjectRecord> resolve(String kind, String id) {
            SubjectRecord record = acme.get();
            return record != null && record.kind().equals(kind) && record.id().equals(id) ? Optional.of(record)
                    : Optional.empty();
        }
    };

    private final DeferredAction action = new DeferredAction("account_ACC-42_202610150000", "account", "ACC-42", NOW,
            "at trial_end", "Downgrade ACC-42 to the free plan and email the owner.", NOW.minusSeconds(3600), "sales-bot",
            DeferredAction.Status.SCHEDULED, "", null);

    private FunctionTool tool(String name) {
        return FunctionTool.builder(name, name).handler(inv -> {
            ran.add(name + " " + inv.arguments());
            return ToolResult.ok("ok");
        }).build();
    }

    @Test
    void theRunIsGivenOnlyToolsThatReadRevokeNotifyOrRequest() {
        assertThat(DeferredActions.restrict(tools).entries()).extracting(e -> e.tool().name())
                .containsExactlyInAnyOrder("crm_downgrade_account", "crm_read_account", "crm_export_all", "email_send");
    }

    @Test
    void theGateKeepsEveryCallToItsSubjectOrItsContacts() {
        ToolGate gate = DeferredActions.gateFor(action, acme.get(), DeferredActions.restrict(tools));

        assertThat(evaluate(gate, "crm_downgrade_account", Map.of("account_id", "ACC-42"))).isInstanceOf(GateResult.Allowed.class);
        assertThat(evaluate(gate, "crm_downgrade_account", Map.of("account_id", "acc-42"))).isInstanceOf(GateResult.Allowed.class);
        assertThat(evaluate(gate, "crm_downgrade_account", Map.of("account_id", "ACC-99"))).isInstanceOf(GateResult.Denied.class);
        assertThat(evaluate(gate, "crm_extend_trial", Map.of("account_id", "ACC-42"))).isInstanceOf(GateResult.Denied.class);
        assertThat(evaluate(gate, "crm_export_all", Map.of())).isInstanceOf(GateResult.Denied.class);
        assertThat(evaluate(gate, "email_send", Map.of("to", "owner@vendor.example"))).isInstanceOf(GateResult.Allowed.class);
        assertThat(evaluate(gate, "email_send", Map.of("to", "rival@elsewhere.example"))).isInstanceOf(GateResult.Denied.class);
        // A contact may be told, not acted on.
        assertThat(evaluate(gate, "crm_read_account", Map.of("account_id", "owner@vendor.example")))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(evaluate(gate, "undeclared", Map.of("account_id", "ACC-42"))).isInstanceOf(GateResult.Denied.class);
    }

    @Test
    void theGoalIsAProcedureUnderTheRunsOwnObjectiveWithTheRecordAsItIsNow() {
        String goal = DeferredActions.goalFor(action, acme.get(), NOW).description();

        assertThat(goal).startsWith("Carry out the deferred action below for account ACC-42.")
                .contains("kind=\"procedure\"").contains("source=\"deferred-action:account_ACC-42_202610150000\"")
                .contains("trial_end: 2026-10-15").contains("may be notified: owner@vendor.example");
        assertThat(goal.indexOf("Downgrade ACC-42")).isGreaterThan(goal.indexOf("Deferred action:"));
    }

    @Test
    void theRunnerRunsWhatIsDueThroughARealAgentAndRecordsHowItEnded() {
        DeferredActionStore store = DeferredActionStore.inMemory();
        store.put(action);
        List<String> dispositions = new ArrayList<>();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("1", "crm_extend_trial", Map.of("account_id", "ACC-42")),
                FakeLlmClient.toolUse("2", "crm_downgrade_account", Map.of("account_id", "ACC-99")),
                FakeLlmClient.toolUse("3", "email_send", Map.of("to", "rival@elsewhere.example")),
                FakeLlmClient.toolUse("4", "crm_downgrade_account", Map.of("account_id", "ACC-42")),
                FakeLlmClient.toolUse("5", "email_send", Map.of("to", "owner@vendor.example")),
                FakeLlmClient.text("Downgraded ACC-42 and emailed the owner."));
        AtomicReference<Instant> clock = new AtomicReference<>(NOW.minusSeconds(60));
        List<Instant> sweeps = new ArrayList<>();
        DeferredRunner runner = new DeferredRunner(store, crm, tools, (goal, allowed, gate) ->
                Agent.builder(llm, allowed.registry(), AgentConfig.builder("m").maxSteps(10).build())
                        .toolGate(gate).observer(new AgentObserver() {
                            @Override
                            public void onToolResult(AgentRun run, int step, ToolInvocation proposed, ToolInvocation effective,
                                                     ToolResult result, Disposition disposition) {
                                dispositions.add(proposed.name() + ":" + disposition.name());
                            }
                        }).build().run(goal), clock::get, sweeps::add);

        assertThat(runner.runDue()).isZero();
        clock.set(NOW);
        assertThat(runner.runDue()).isEqualTo(1);

        assertThat(dispositions).containsExactly("crm_extend_trial:UNKNOWN_TOOL", "crm_downgrade_account:REFUSED",
                "email_send:REFUSED", "crm_downgrade_account:RAN", "email_send:RAN");
        assertThat(ran).containsExactly("crm_downgrade_account {account_id=ACC-42}", "email_send {to=owner@vendor.example}");
        assertThat(store.get(action.id())).hasValueSatisfying(a -> {
            assertThat(a.status()).isEqualTo(DeferredAction.Status.DONE);
            assertThat(a.outcome()).isEqualTo("Downgraded ACC-42 and emailed the owner.");
        });
        assertThat(sweeps).hasSize(2);
        assertThat(runner.runDue()).isZero();
    }

    @Test
    void anActionWhoseSubjectIsGoneFailsWithoutRunning() {
        DeferredActionStore store = DeferredActionStore.inMemory();
        store.put(action);
        acme.set(null);
        DeferredRunner runner = new DeferredRunner(store, crm, tools, (goal, allowed, gate) -> {
            throw new AssertionError("should not run");
        }, () -> NOW, null);

        runner.runDue();

        assertThat(store.get(action.id())).hasValueSatisfying(a -> {
            assertThat(a.status()).isEqualTo(DeferredAction.Status.FAILED);
            assertThat(a.outcome()).contains("no longer exists");
        });
    }

    private static GateResult evaluate(ToolGate gate, String name, Map<String, Object> args) {
        Tool tool = FunctionTool.builder(name, name).handler(inv -> ToolResult.ok("ok")).build();
        return gate.evaluate(tool, new ToolInvocation("t", name, new HashMap<>(args)));
    }
}
