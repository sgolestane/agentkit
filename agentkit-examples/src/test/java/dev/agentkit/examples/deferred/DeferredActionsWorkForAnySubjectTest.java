package dev.agentkit.examples.deferred;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The deferred-action package with nothing from onboarding or HR: a CRM account whose trial ends. If
 * this compiles and passes without importing anything from {@code examples.onboarding}, the scheduling,
 * the goal and the bounds are generic.
 */
class DeferredActionsWorkForAnySubjectTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

    private final SubjectRecord acme = new SubjectRecord("account", "ACC-42", Set.of("ACC-42"),
            Set.of("owner@vendor.example"), Map.of("name", "Acme Corp", "plan", "trial", "trial_end", "2026-10-15"));

    private final SubjectResolver crm = new SubjectResolver() {
        @Override
        public Set<String> kinds() {
            return Set.of("account");
        }

        @Override
        public Optional<SubjectRecord> resolve(String kind, String id) {
            return "account".equals(kind) && acme.id().equals(id) ? Optional.of(acme) : Optional.empty();
        }
    };

    private final Map<String, ToolInfo> declared = Map.of(
            "crm_downgrade_account", new ToolInfo("crm", Effect.REVOKE, "account_id"),
            "crm_extend_trial", new ToolInfo("crm", Effect.GRANT, "account_id"),
            "email_send", new ToolInfo("email", Effect.NOTIFY, "to"),
            "crm_export_all", new ToolInfo("crm", Effect.READ, null));

    private final List<Tool> tools = declared.keySet().stream()
            .<Tool>map(name -> FunctionTool.builder(name, name).handler(inv -> ToolResult.ok("ok")).build()).toList();

    @Test
    void schedulesRelativeToAnyDateFieldOfAnySubject() {
        DeferredActionScheduler scheduler = new DeferredActionScheduler(crm, () -> TODAY, subject -> List.of("crm"));

        ToolResult result = scheduler.schedule(invocation(Map.of("subject_kind", "account", "subject_id", "ACC-42",
                "goal", "Downgrade account ACC-42 in the CRM to the free plan and email owner@vendor.example.",
                "relative_to", "trial_end", "offset_days", 0)));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("the goal names all of it");
        assertThat(scheduler.scheduled()).singleElement().satisfies(a -> {
            assertThat(a.id()).isEqualTo("account_ACC-42_20261015");
            assertThat(a.runOn()).isEqualTo(LocalDate.of(2026, 10, 15));
        });
        assertThat(scheduler.schema().toString()).contains("[account]");
    }

    @Test
    void theRunIsBoundedByDeclarationsAndTheSubjectAlone() {
        DeferredAction action = new DeferredAction("account_ACC-42_20261015", "account", "ACC-42",
                LocalDate.of(2026, 10, 15), "on trial_end", "Downgrade and tell the owner.", TODAY);
        ToolGate gate = DeferredActions.gateFor(action, acme, name -> Optional.ofNullable(declared.get(name)));

        assertThat(DeferredActions.restrict(tools, name -> Optional.ofNullable(declared.get(name))).tools())
                .extracting(Tool::name).containsExactlyInAnyOrder("crm_downgrade_account", "email_send");
        assertThat(evaluate(gate, "crm_downgrade_account", Map.of("account_id", "ACC-42"))).isInstanceOf(GateResult.Allowed.class);
        assertThat(evaluate(gate, "crm_downgrade_account", Map.of("account_id", "ACC-99"))).isInstanceOf(GateResult.Denied.class);
        assertThat(evaluate(gate, "crm_extend_trial", Map.of("account_id", "ACC-42"))).isInstanceOf(GateResult.Denied.class);
        assertThat(evaluate(gate, "crm_export_all", Map.of())).isInstanceOf(GateResult.Denied.class);
        assertThat(evaluate(gate, "email_send", Map.of("to", "owner@vendor.example"))).isInstanceOf(GateResult.Allowed.class);
        assertThat(evaluate(gate, "email_send", Map.of("to", "rival@elsewhere.example"))).isInstanceOf(GateResult.Denied.class);
        assertThat(evaluate(gate, "undeclared_tool", Map.of("account_id", "ACC-42"))).isInstanceOf(GateResult.Denied.class);

        String goal = DeferredActions.goalFor(action, acme, LocalDate.of(2026, 10, 15)).description();
        assertThat(goal).startsWith("Carry out the deferred action below for account ACC-42.")
                .contains("trial_end: 2026-10-15").contains("may be notified: owner@vendor.example")
                .contains("kind=\"procedure\"");
    }

    private static GateResult evaluate(ToolGate gate, String name, Map<String, Object> args) {
        Tool tool = FunctionTool.builder(name, name).handler(inv -> ToolResult.ok("ok")).build();
        return gate.evaluate(tool, invocation(name, args));
    }

    private static ToolInvocation invocation(Map<String, Object> args) {
        return invocation(DeferredActionScheduler.TOOL_NAME, args);
    }

    private static ToolInvocation invocation(String name, Map<String, Object> args) {
        return new ToolInvocation("t", name, new HashMap<>(args));
    }
}
