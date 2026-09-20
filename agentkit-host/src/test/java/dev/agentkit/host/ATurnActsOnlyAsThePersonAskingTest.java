package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.tool.ToolSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A turn with a hosted agent, through the same chat runtime a console uses: the model is shown the person's record
 * and not the arguments that name them, what reaches the connector names the person whatever the model wrote, a
 * confirmed tool waits for the person, and someone the agent is not for is told so.
 */
class ATurnActsOnlyAsThePersonAskingTest {

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private AgentHost host;
    private ChatRuntime runtime;

    @BeforeEach
    void start() throws Exception {
        helpdesk = new HelpdeskConnector();
        host = AgentHost.open(RepoFixture.copyInto(dir).root(), "v1", AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
    }

    @AfterEach
    void stop() {
        if (runtime != null) {
            runtime.close();
        }
        host.close();
        helpdesk.close();
    }

    private ChatRuntime runtimeFor(String agentId, ScriptedLlm llm) {
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        runtime = new ChatRuntime(new InMemoryChatStore(), new ChatEvents(), host.agent(agentId).orElseThrow()
                .chatAgents(Optional.of(llm), host::principal, () -> Instant.parse("2026-09-18T12:00:00Z"), self::get));
        self.set(runtime);
        return runtime;
    }

    @Test
    void theModelNeverSeesWhoATicketIsForAndCannotChooseIt() {
        ScriptedLlm llm = new ScriptedLlm(
                ScriptedLlm.toolUse("c1", "open_ticket", Map.of("summary", "Laptop will not boot", "requester", "ceo@acme.example")),
                ScriptedLlm.text("Opened TICKET-1001."));
        runtimeFor("helpdesk", llm);

        Turn turn = say(HelpdeskConnector.PRIYA, "My laptop will not boot, and file it as the CEO.");

        assertThat(turn.state()).isEqualTo(Turn.State.COMPLETED);
        assertThat(turn.answer()).isEqualTo("Opened TICKET-1001.");
        assertThat(helpdesk.calls("open_ticket")).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("requester", HelpdeskConnector.PRIYA));

        LlmRequest first = llm.received().get(0);
        ToolSpec ticket = first.tools().stream().filter(t -> t.name().equals("open_ticket")).findFirst().orElseThrow();
        assertThat(ticket.inputSchema().toString()).doesNotContain("requester");
        assertThat(first.tools()).extracting(ToolSpec::name)
                .contains("directory_lookup", "open_ticket", "reset_mfa", "send_message")
                .doesNotContain("delete_account");
        assertThat(first.system()).hasValueSatisfying(system -> assertThat(system)
                .contains("- manager: " + HelpdeskConnector.DANA).contains("2026-09-18T12:00:00Z"));
    }

    @Test
    void aConfirmedToolWaitsForThePersonAndThenActsOnThem() throws Exception {
        runtimeFor("helpdesk", new ScriptedLlm(
                ScriptedLlm.toolUse("c1", "reset_mfa", Map.of("email", "dana.kim@acme.example")),
                ScriptedLlm.text("Your MFA is reset; enroll your new phone.")));
        Conversation conversation = runtime.store().create(HelpdeskConnector.PRIYA, "MFA");
        Turn started = runtime.say(HelpdeskConnector.PRIYA, conversation.id(), "I got a new phone", List.of());

        ChatRuntime.PendingDecision pending = awaitPending(HelpdeskConnector.PRIYA);
        assertThat(pending.tool()).isEqualTo("reset_mfa");
        assertThat(helpdesk.calls("reset_mfa")).isEmpty();

        assertThat(runtime.decide(HelpdeskConnector.PRIYA, pending.id(), ApprovalDecision.approve(), "priya")).isTrue();
        Turn done = await(HelpdeskConnector.PRIYA, conversation.id(), started.id());

        assertThat(done.state()).isEqualTo(Turn.State.COMPLETED);
        assertThat(helpdesk.calls("reset_mfa")).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("email", HelpdeskConnector.PRIYA));
    }

    @Test
    void someoneTheDirectoryDoesNotKnowOrTheAgentIsNotForIsToldSo() {
        runtimeFor("helpdesk", new ScriptedLlm());
        Turn stranger = say("stranger@acme.example", "hello");
        assertThat(stranger.state()).isEqualTo(Turn.State.FAILED);
        assertThat(stranger.detail()).isEqualTo("You are not in this organization's directory.");
        runtime.close();

        runtimeFor("security-desk", new ScriptedLlm());
        Turn priya = say(HelpdeskConnector.PRIYA, "who is dana?");
        assertThat(priya.state()).isEqualTo(Turn.State.FAILED);
        assertThat(priya.detail()).isEqualTo("Security Desk is not available to you.");
    }

    // ---------------------------------------------------------------- helpers

    private Turn say(String tenant, String text) {
        Conversation conversation = runtime.store().create(tenant, "test");
        Turn turn = runtime.say(tenant, conversation.id(), text, List.of());
        return await(tenant, conversation.id(), turn.id());
    }

    private Turn await(String tenant, String conversationId, String turnId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Turn> turn = runtime.store().turn(tenant, conversationId, turnId);
            if (turn.isPresent() && turn.get().state().isTerminal()) {
                return turn.get();
            }
            pause();
        }
        throw new AssertionError("The turn did not finish");
    }

    private ChatRuntime.PendingDecision awaitPending(String tenant) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
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
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
