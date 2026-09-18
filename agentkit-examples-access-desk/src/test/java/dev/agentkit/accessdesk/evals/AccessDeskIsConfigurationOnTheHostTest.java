package dev.agentkit.accessdesk.evals;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.accessdesk.desk.AccessLedger.Grant;
import dev.agentkit.accessdesk.evals.AccessDeskEvalTest.Harness;
import dev.agentkit.accessdesk.evals.AccessDeskEvalTest.World;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Turn;
import dev.agentkit.core.deferred.DeferredAction;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.tool.ToolSpec;
import dev.agentkit.host.DeferredWork;
import dev.agentkit.host.OrgHost;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Access Desk with no Access Desk code in the product path: {@code orgs/acme} on the agent host, the ledger's rules
 * in its connector, over HTTP — without a model, so what is pinned is the wiring the evals depend on.
 */
class AccessDeskIsConfigurationOnTheHostTest {

    private static final String PRIYA = AccessDeskEvalTest.PRIYA;

    @Test
    void theModelIsGivenTheDesksToolsAndNeverTheRawGrantsOrWhoItActsAs() throws Exception {
        Scripted llm = new Scripted(text("Hello."));
        World world = new World();
        try (Harness harness = AccessDeskOnTheHostEvalTest.onTheHost(world, llm)) {
            converse(harness, PRIYA, "hi");

            List<ToolSpec> tools = llm.requests.get(0).tools();
            assertThat(tools).extracting(ToolSpec::name).contains("grant_low_risk_access", "submit_access_request",
                    "decide_request", "my_access", "revoke_grant", "list_resources", "directory_lookup", "send_message",
                    "schedule_deferred_action", "ask_person")
                    .doesNotContain("grant_access", "revoke_access", "get_grant");
            assertThat(tools).allSatisfy(t -> assertThat(t.inputSchema().toString()).doesNotContain("acting_as"));
            assertThat(llm.requests.get(0).system()).hasValueSatisfying(system -> assertThat(system)
                    .contains("You are Access Desk").contains("Access policy:")
                    .contains("- manager: " + AccessDeskEvalTest.DANA).contains("2026-09-16T15:00:00Z"));
        }
    }

    @Test
    void aGrantIsThePersonsWhateverTheModelSaysAndItsExpiryIsCarriedOutLater() throws Exception {
        Scripted llm = new Scripted(
                call("grant_low_risk_access", Map.of("resource_id", "datadog-payments", "level", "read", "hours", 2,
                        "justification", "latency", "acting_as", "sam.okafor@acme.example")),
                call("schedule_deferred_action", Map.of("subject_kind", "grant", "subject_id", "GR-1001",
                        "relative_to", "expires_at", "offset_minutes", 0,
                        "goal", "Revoke grant GR-1001 with revoke_grant and tell " + PRIYA + ".")),
                text("Granted GR-1001 until 17:00."),
                // The deferred run: revoke, then say so.
                call("revoke_grant", Map.of("grant_id", "GR-1001", "reason", "expired")),
                text("Revoked GR-1001."));
        World world = new World();
        try (Harness harness = AccessDeskOnTheHostEvalTest.onTheHost(world, llm)) {
            Turn turn = converse(harness, PRIYA, "read on the payments dashboards for 2h please");

            assertThat(turn.state()).isEqualTo(Turn.State.COMPLETED);
            assertThat(world.grants()).singleElement().satisfies(g -> {
                assertThat(g.email()).isEqualTo(PRIYA);
                assertThat(g.expiresAt()).isEqualTo(Instant.parse("2026-09-16T17:00:00Z"));
            });
            assertThat(world.deferred()).singleElement().satisfies(a -> {
                assertThat(a.subjectKind()).isEqualTo("grant");
                assertThat(a.runAt()).isEqualTo(Instant.parse("2026-09-16T17:00:00Z"));
                assertThat(a.scheduledBy()).isEqualTo(PRIYA);
            });

            // Two hours on, the host runs it — as the desk, which may revoke.
            DeferredWork later = laterWork(harness, world, llm);
            assertThat(later.runDue()).isEqualTo(1);
            assertThat(world.grants()).singleElement().satisfies(g -> {
                assertThat(g.status()).isEqualTo(Grant.Status.REVOKED);
                assertThat(g.revokedBy()).isEqualTo("access-desk");
            });
            assertThat(world.deferred()).singleElement()
                    .satisfies(a -> assertThat(a.status()).isEqualTo(DeferredAction.Status.DONE));
        }
    }

    /** The harness's own deferred work, with the clock moved past the grant's expiry. */
    private static DeferredWork laterWork(Harness harness, World world, LlmClient llm) {
        OrgHost org = ((AccessDeskOnTheHostEvalTest.HostHarness) harness).org();
        return new DeferredWork(org, agent -> world.store, () -> Instant.parse("2026-09-16T17:01:00Z"), Optional.of(llm));
    }

    // ---------------------------------------------------------------- helpers

    private static Turn converse(Harness harness, String who, String text) throws InterruptedException {
        ChatRuntime runtime = harness.runtime();
        Conversation conversation = harness.start(who, "test");
        Turn turn = runtime.say(harness.tenant(who), conversation.id(), text, List.of());
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Turn> now = runtime.store().turn(harness.tenant(who), conversation.id(), turn.id());
            if (now.isPresent() && now.get().state().isTerminal()) {
                return now.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("The turn did not finish");
    }

    private static LlmResponse text(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)), LlmStopReason.END_TURN, TokenUsage.ZERO);
    }

    private static LlmResponse call(String tool, Map<String, Object> input) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, ProposedCall.of("c-" + tool, tool, input)),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO);
    }

    /** A model that says what it is told, in order, and keeps what it was sent. */
    static final class Scripted implements LlmClient {
        final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
        private final Deque<LlmResponse> script;

        Scripted(LlmResponse... responses) {
            this.script = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public synchronized LlmResponse generate(LlmRequest request) {
            requests.add(request);
            if (script.isEmpty()) {
                throw new IllegalStateException("The script ran out after " + requests.size() + " calls");
            }
            return script.poll();
        }
    }
}
