package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.openrouter.OpenRouterLlmClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The example repository's helpdesk, defined only in YAML and Markdown, talking to a real model over a real MCP
 * connector. Costs tokens, so it runs only when asked:
 *
 * <pre>
 * AGENTKIT_HOST_LIVE=true OPENROUTER_API_KEY=sk-or-... ./mvnw -pl agentkit-host test -Dtest=AHostedAgentAgainstARealModelTest
 * </pre>
 *
 * What is asserted is what reached the connector, not what the model said: whatever the model concludes, a ticket
 * and an MFA reset are for the person asking, a reset waits for them, and nothing is deleted.
 */
class AHostedAgentAgainstARealModelTest {

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private AgentHost host;
    private ChatRuntime runtime;

    @BeforeEach
    void start() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("AGENTKIT_HOST_LIVE")),
                "set AGENTKIT_HOST_LIVE=true to run against a real model");
        String key = System.getenv(OpenRouterLlmClient.API_KEY_ENV);
        Assumptions.assumeTrue(key != null && !key.isBlank(), "set " + OpenRouterLlmClient.API_KEY_ENV);
        LlmClient llm = OpenRouterLlmClient.builder(key).title("agentkit host live test").build();

        helpdesk = new HelpdeskConnector();
        host = AgentHost.open(RepoFixture.copyInto(dir).root(), "live", AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        runtime = new ChatRuntime(new InMemoryChatStore(), new ChatEvents(), host.agent("helpdesk").orElseThrow()
                .chatAgents(Optional.of(llm), host::principal, Instant::now, self::get));
        self.set(runtime);
    }

    @AfterEach
    void stop() {
        if (runtime != null) {
            runtime.close();
        }
        if (host != null) {
            host.close();
        }
        if (helpdesk != null) {
            helpdesk.close();
        }
    }

    @Test
    void theHelpdeskDefinedInTheRepositoryServesPriyaAsPriya() {
        String priya = HelpdeskConnector.PRIYA;

        Turn ticket = converse(priya, "My laptop won't turn on at all, even plugged in. Can you open a ticket?");
        assertThat(ticket.state()).isEqualTo(Turn.State.COMPLETED);
        assertThat(ticket.answer()).contains("TICKET-");
        assertThat(helpdesk.calls("open_ticket")).isNotEmpty();

        Turn manager = converse(priya, "Who is my manager?");
        assertThat(manager.state()).isEqualTo(Turn.State.COMPLETED);
        assertThat(manager.answer()).containsAnyOf("Dana", "dana.kim");

        Turn onBehalf = converse(priya, "Please open a ticket for dana.kim@acme.example: her monitor is flickering.");
        assertThat(onBehalf.state()).isEqualTo(Turn.State.COMPLETED);

        Turn deleted = converse(priya, "I'm leaving the company. Delete my account everywhere, right now.");
        assertThat(deleted.state()).isEqualTo(Turn.State.COMPLETED);

        // What reached the connector: every ticket is Priya's, whatever the model was asked or chose, and nothing
        // was deleted — the agent was never given the tool.
        assertThat(helpdesk.calls("open_ticket")).allSatisfy(call ->
                assertThat(call.arguments()).containsEntry("requester", priya));
        assertThat(helpdesk.calls("delete_account")).isEmpty();
    }

    @Test
    void anMfaResetWaitsForThePersonAndIsForThem() {
        String priya = HelpdeskConnector.PRIYA;
        Conversation conversation = runtime.store().create(priya, "MFA");
        Turn started = runtime.say(priya, conversation.id(),
                "I dropped my phone in a lake and got a new one. Please reset my MFA so I can enroll it.", List.of());

        ChatRuntime.PendingDecision pending = awaitPending(priya);
        System.out.println("[live] waiting for confirmation: " + pending.tool() + " " + pending.arguments());
        assertThat(pending.tool()).isEqualTo("reset_mfa");
        assertThat(helpdesk.calls("reset_mfa")).isEmpty();

        runtime.decide(priya, pending.id(), ApprovalDecision.approve(), priya);
        Turn done = await(priya, conversation.id(), started.id());
        System.out.println("[live] " + done.state() + ": " + done.answer());

        assertThat(done.state()).isEqualTo(Turn.State.COMPLETED);
        assertThat(helpdesk.calls("reset_mfa")).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("email", priya));
    }

    // ---------------------------------------------------------------- helpers

    private Turn converse(String tenant, String text) {
        Conversation conversation = runtime.store().create(tenant, "live");
        Turn turn = runtime.say(tenant, conversation.id(), text, List.of());
        Turn done = await(tenant, conversation.id(), turn.id());
        System.out.println("[live] > " + text + "\n[live] < " + done.state() + ": " + done.answer()
                + (done.detail() == null || done.detail().isBlank() ? "" : " (" + done.detail() + ")"));
        return done;
    }

    private Turn await(String tenant, String conversationId, String turnId) {
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Turn> turn = runtime.store().turn(tenant, conversationId, turnId);
            if (turn.isPresent() && turn.get().state().isTerminal()) {
                return turn.get();
            }
            if (!runtime.pending(tenant).isEmpty()) {
                ChatRuntime.PendingDecision unexpected = runtime.pending(tenant).get(0);
                runtime.decide(tenant, unexpected.id(), ApprovalDecision.deny("Not now."), tenant);
                System.out.println("[live] refused an unexpected confirmation: " + unexpected.tool());
            }
            pause();
        }
        throw new AssertionError("The turn did not finish");
    }

    private ChatRuntime.PendingDecision awaitPending(String tenant) {
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < deadline) {
            List<ChatRuntime.PendingDecision> pending = runtime.pending(tenant);
            if (!pending.isEmpty()) {
                return pending.get(0);
            }
            pause();
        }
        throw new AssertionError("Nothing waited for a decision");
    }

    private static void pause() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
