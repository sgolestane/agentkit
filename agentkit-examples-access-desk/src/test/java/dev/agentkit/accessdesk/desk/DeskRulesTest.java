package dev.agentkit.accessdesk.desk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.mcp.InProcessMcpConnection;
import dev.agentkit.accessdesk.systems.CompanySystems;
import dev.agentkit.core.deferred.DeferredAction;
import dev.agentkit.core.deferred.DeferredActionScheduler;
import dev.agentkit.core.deferred.DeferredActionStore;
import dev.agentkit.core.deferred.SubjectRecord;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The rules the desk enforces in code, whatever a model concludes from the policy. No model here: each tool
 * is called the way a model would call it, against the company systems in-process.
 */
class DeskRulesTest {

    static final String PRIYA = "priya.natarajan@acme.example";
    static final String DANA = "dana.kim@acme.example";
    static final String SAM = "sam.okafor@acme.example";
    static final Instant NOW = Instant.parse("2026-09-16T15:00:00Z");

    private final CompanySystems systems = CompanySystems.open(null);
    private final CompanyClient company = new CompanyClient(new InProcessMcpConnection(systems.catalog()));
    private final AccessLedger ledger = AccessLedger.open(null);
    private final DeferredActionStore store = DeferredActionStore.inMemory();
    private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);
    private final DeferredActionScheduler scheduler = DeskTools.scheduler(ledger, store, clock::get);

    private DeclaredTools as(String who) {
        return new DeskTools(who, ledger, company, scheduler, clock::get).catalog();
    }

    private static ToolResult call(DeclaredTools tools, String name, Object... args) {
        Map<String, Object> arguments = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            arguments.put((String) args[i], args[i + 1]);
        }
        return tools.entry(name).orElseThrow().tool().execute(new ToolInvocation("t", name, arguments));
    }

    @Test
    void lowSensitivityIsGrantedAtOnceAndNothingElseIs() {
        ToolResult low = call(as(PRIYA), "grant_low_risk_access", "resource_id", "datadog-payments", "level", "read",
                "hours", 3, "justification", "debugging latency");
        ToolResult high = call(as(PRIYA), "grant_low_risk_access", "resource_id", "db-payments-prod", "level", "read",
                "hours", 1, "justification", "INC-4211");

        assertThat(low.isError()).isFalse();
        assertThat(low.content()).contains("GR-1001").contains("2026-09-16T18:00:00Z");
        assertThat(systems.access()).anyMatch(a -> a.resource_id().equals("datadog-payments") && a.email().equals(PRIYA));
        assertThat(high.isError()).isTrue();
        assertThat(high.content()).contains("needs an approver");
        assertThat(systems.access()).noneMatch(a -> a.resource_id().equals("db-payments-prod"));
    }

    @Test
    void requestsAreBoundedByTheCatalog() {
        assertThat(call(as(PRIYA), "grant_low_risk_access", "resource_id", "datadog-payments", "level", "read",
                "hours", 25, "justification", "x").content()).contains("at most 24 hours");
        assertThat(call(as(PRIYA), "grant_low_risk_access", "resource_id", "datadog-payments", "level", "admin",
                "hours", 1, "justification", "x").content()).contains("has no level");
        assertThat(call(as(PRIYA), "grant_low_risk_access", "resource_id", "nope", "level", "read",
                "hours", 1, "justification", "x").content()).contains("no resource");
        assertThat(call(as(PRIYA), "grant_low_risk_access", "resource_id", "datadog-payments", "level", "read",
                "hours", 1, "justification", " ").content()).contains("justification is required");
        call(as(PRIYA), "grant_low_risk_access", "resource_id", "datadog-payments", "level", "read", "hours", 1,
                "justification", "x");
        assertThat(call(as(PRIYA), "grant_low_risk_access", "resource_id", "datadog-payments", "level", "read",
                "hours", 1, "justification", "again").content()).contains("already hold");
    }

    @Test
    void theApproverIsTheOwnerOrTheManagerAndNeverTheRequester() {
        ToolResult self = call(as(PRIYA), "submit_access_request", "resource_id", "db-payments-prod", "level", "read",
                "hours", 2, "justification", "INC-4211", "approver_email", PRIYA);
        ToolResult managerApproves = call(as(PRIYA), "submit_access_request", "resource_id", "aws-prod-admin", "level",
                "admin", "hours", 1, "justification", "INC-4211", "approver_email", DANA);
        ToolResult ownerApproves = call(as(PRIYA), "submit_access_request", "resource_id", "db-payments-prod", "level", "read",
                "hours", 2, "justification", "INC-4211", "approver_email", DANA);
        ToolResult ownerAsksManager = call(as(DANA), "submit_access_request", "resource_id", "db-payments-prod", "level",
                "write", "hours", 1, "justification", "INC-4211", "approver_email", SAM);

        assertThat(self.isError()).isTrue();
        assertThat(self.content()).contains("Nobody may approve their own request");
        // Sam owns aws-prod-admin, but Dana is Priya's manager, which the code also allows; routing to the owner is policy.
        assertThat(managerApproves.isError()).isFalse();
        assertThat(ownerApproves.isError()).isFalse();
        assertThat(ownerAsksManager.isError()).isFalse();
        // Priya neither owns payments-prod nor manages Sam.
        assertThat(call(as(SAM), "submit_access_request", "resource_id", "db-payments-prod", "level", "read", "hours", 1,
                "justification", "INC-1", "approver_email", PRIYA).content()).contains("cannot approve");
        assertThat(systems.messages()).anyMatch(m -> m.to().equals(DANA) && m.text().contains("REQ-1002"));
    }

    @Test
    void onlyTheApproverDecidesAndMayShortenButNotLengthen() {
        call(as(PRIYA), "submit_access_request", "resource_id", "db-payments-prod", "level", "read", "hours", 4,
                "justification", "INC-4211", "approver_email", DANA);

        assertThat(call(as(PRIYA), "decide_request", "request_id", "REQ-1001", "decision", "approve").content())
                .contains("Only dana.kim@acme.example");
        assertThat(call(as(SAM), "decide_request", "request_id", "REQ-1001", "decision", "approve").isError()).isTrue();
        assertThat(call(as(DANA), "decide_request", "request_id", "REQ-1001", "decision", "approve", "hours", 6).content())
                .contains("never more");

        ToolResult approved = call(as(DANA), "decide_request", "request_id", "REQ-1001", "decision", "approve", "hours", 2);
        assertThat(approved.isError()).isFalse();
        assertThat(approved.content()).contains("GR-1001").contains("2026-09-16T17:00:00Z");
        assertThat(ledger.grant("GR-1001")).hasValueSatisfying(g -> assertThat(g.approvedBy()).isEqualTo(DANA));
        assertThat(systems.access()).anyMatch(a -> a.resource_id().equals("db-payments-prod") && a.email().equals(PRIYA));
        assertThat(systems.messages()).anyMatch(m -> m.to().equals(PRIYA) && m.text().contains("approved"));
        assertThat(call(as(DANA), "decide_request", "request_id", "REQ-1001", "decision", "deny").content())
                .contains("already approved");
    }

    @Test
    void aGrantIsRevokedOnlyByItsHolderApproverOwnerOrTheDesk() {
        call(as(PRIYA), "grant_low_risk_access", "resource_id", "slack-incident-war-room", "level", "member", "hours", 2,
                "justification", "INC-4211");

        assertThat(call(as(DANA), "revoke_grant", "grant_id", "GR-1001", "reason", "x").content()).contains("Only the holder");
        ToolResult byDesk = call(as(DeskTools.DESK), "revoke_grant", "grant_id", "gr-1001", "reason", "expired");
        assertThat(byDesk.isError()).isFalse();
        assertThat(systems.access()).noneMatch(a -> a.resource_id().equals("slack-incident-war-room"));
        assertThat(call(as(PRIYA), "revoke_grant", "grant_id", "GR-1001", "reason", "done").content()).contains("already revoked");
    }

    @Test
    void aGrantIsASubjectAndTheSchedulerCountsFromItsExpiry() {
        call(as(PRIYA), "submit_access_request", "resource_id", "db-payments-prod", "level", "read", "hours", 4,
                "justification", "INC-4211", "approver_email", DANA);
        call(as(DANA), "decide_request", "request_id", "REQ-1001", "decision", "approve", "hours", 2);

        SubjectRecord grant = DeskTools.grantSubjects(ledger).resolve(DeskTools.GRANT, "GR-1001").orElseThrow();
        assertThat(grant.refersTo("GR-1001")).isTrue();
        assertThat(grant.refersTo(PRIYA)).isTrue();
        assertThat(grant.isContact(DANA)).isTrue();
        assertThat(grant.facts()).containsEntry("expires_at", "2026-09-16T17:00:00Z").containsEntry("status", "ACTIVE");

        ToolResult scheduled = call(as(DANA), DeferredActionScheduler.TOOL_NAME, "subject_kind", "grant", "subject_id",
                "GR-1001", "goal", "Revoke grant GR-1001 with revoke_grant and tell " + PRIYA + ".", "relative_to", "expires_at",
                "offset_minutes", 0);
        ToolResult reminder = call(as(DANA), DeferredActionScheduler.TOOL_NAME, "subject_kind", "grant", "subject_id",
                "GR-1001", "goal", "Remind the holder it ends soon.", "relative_to", "expires_at", "offset_minutes", -15);
        ToolResult past = call(as(DANA), DeferredActionScheduler.TOOL_NAME, "subject_kind", "grant", "subject_id",
                "GR-1001", "goal", "x", "run_at", "2026-09-16T14:00:00Z");

        assertThat(scheduled.content()).contains("the goal names all of it");
        assertThat(reminder.content()).contains("does not name: [GR-1001]");
        assertThat(past.isError()).isTrue();
        assertThat(store.all()).extracting(DeferredAction::runAt)
                .containsExactly(Instant.parse("2026-09-16T16:45:00Z"), Instant.parse("2026-09-16T17:00:00Z"));
        assertThat(store.all()).allMatch(a -> a.scheduledBy().equals(DANA));
    }

    @Test
    void onlyThoseWhoCouldRevokeAGrantMayScheduleWorkForIt() {
        call(as(SAM), "grant_low_risk_access", "resource_id", "slack-incident-war-room", "level", "member", "hours", 4,
                "justification", "on call");

        // Priya cannot revoke Sam's grant, so she cannot schedule its revocation to run later as the desk either.
        assertThat(call(as(PRIYA), "revoke_grant", "grant_id", "GR-1001", "reason", "x").content()).contains("Only the holder");
        ToolResult byPriya = call(as(PRIYA), DeferredActionScheduler.TOOL_NAME, "subject_kind", "grant", "subject_id",
                "GR-1001", "goal", "Revoke GR-1001 now and tell the holder Security revoked it.", "run_at", "2026-09-16T15:01:00Z");
        assertThat(byPriya.isError()).isTrue();
        assertThat(byPriya.content()).contains("may not schedule deferred actions for grant GR-1001");
        assertThat(store.all()).isEmpty();

        // Sam holds the grant (and owns the channel), so he may.
        assertThat(call(as(SAM), DeferredActionScheduler.TOOL_NAME, "subject_kind", "grant", "subject_id", "GR-1001",
                "goal", "Revoke GR-1001.", "relative_to", "expires_at").isError()).isFalse();
        assertThat(call(as(SAM), DeferredActionScheduler.TOOL_NAME, "subject_kind", "grant", "subject_id", "GR-1001",
                "goal", "Remind about GR-1001.", "relative_to", "expires_at", "offset_minutes", -15).isError()).isFalse();
        assertThat(store.all()).hasSize(2);
    }

    @Test
    void conversationsNeverGetTheCompanySystemsRawGrantsOrRevocations() {
        DeclaredTools tools = DeskAgent.conversationTools(new DeskTools(PRIYA, ledger, company, scheduler, clock::get),
                systems.catalog());

        assertThat(tools.declaration("grant_access")).isEmpty();
        assertThat(tools.declaration("revoke_access")).isEmpty();
        assertThat(tools.declaration("list_resources")).hasValueSatisfying(i -> assertThat(i.effect()).isEqualTo(ToolEffect.READ));
        assertThat(tools.declaration("send_message")).hasValueSatisfying(i -> assertThat(i.effect()).isEqualTo(ToolEffect.NOTIFY));
        assertThat(tools.declaration("decide_request")).isPresent();
    }
}
