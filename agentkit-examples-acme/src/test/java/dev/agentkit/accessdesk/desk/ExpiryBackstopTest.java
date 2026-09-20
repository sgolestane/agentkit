package dev.agentkit.accessdesk.desk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.accessdesk.systems.CompanySystems;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.mcp.InProcessMcpConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Revocation at expiry is normally a deferred action the agent scheduled, which a model wrote and which can therefore
 * be missing or fail. Access that outlives its expiry is the one failure the desk exists to prevent, so the ledger's
 * connector revokes whatever is still active a grace period after it expired, with no model involved.
 */
class ExpiryBackstopTest {

    static final String PRIYA = "priya.natarajan@acme.example";
    static final String DANA = "dana.kim@acme.example";

    private final CompanySystems systems = CompanySystems.open(null);
    private final CompanyClient company = new CompanyClient(new InProcessMcpConnection(systems.catalog()));
    private final AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-09-16T15:00:00Z"));

    @Test
    void aGrantNobodyRevokedIsRevokedOnceItsGracePeriodIsOver() {
        AccessLedger ledger = AccessLedger.open(null);
        call(new DeskTools(PRIYA, ledger, company, clock::get).catalog(), "submit_access_request", "resource_id",
                "db-payments-prod", "level", "read", "hours", 4, "justification", "INC-4211", "approver_email", DANA);
        call(new DeskTools(DANA, ledger, company, clock::get).catalog(), "decide_request", "request_id", "REQ-1001",
                "decision", "approve", "hours", 2);
        ExpiryBackstop backstop = new ExpiryBackstop(ledger, new DeskTools(DeskTools.DESK, ledger, company, clock::get));

        // No deferred action was ever scheduled: only the backstop stands between the grant and forever.
        clock.set(Instant.parse("2026-09-16T17:04:00Z"));
        assertThat(backstop.revokeOverdue(clock.get())).isEmpty();
        clock.set(Instant.parse("2026-09-16T17:05:00Z").plus(Duration.ofSeconds(1)));
        assertThat(backstop.revokeOverdue(clock.get())).containsExactly("GR-1001");
        assertThat(ledger.grant("GR-1001")).hasValueSatisfying(g -> {
            assertThat(g.revokedBy()).isEqualTo(DeskTools.DESK);
            assertThat(g.revokeReason()).contains("backstop");
        });
        assertThat(systems.access()).noneMatch(a -> a.resource_id().equals("db-payments-prod"));
        assertThat(backstop.revokeOverdue(clock.get())).isEmpty();
    }

    private static void call(DeclaredTools tools, String name, Object... args) {
        Map<String, Object> arguments = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            arguments.put((String) args[i], args[i + 1]);
        }
        assertThat(tools.entry(name).orElseThrow().tool().execute(new ToolInvocation("t", name, arguments)).isError())
                .isFalse();
    }
}
