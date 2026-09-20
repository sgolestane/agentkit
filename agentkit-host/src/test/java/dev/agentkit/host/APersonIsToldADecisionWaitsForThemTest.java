package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Step;
import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.host.repo.DefinitionException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A person is told, once, when a decision has waited for them longer than their organization's {@code notify.after}:
 * through the connector tool {@code org.yaml} names, with a link to the conversation, and noted on the turn.
 */
class APersonIsToldADecisionWaitsForThemTest {

    private static final String PRIYA = new Tenant("acme", HelpdeskConnector.PRIYA).id();

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private OrgHost org;
    private ChatRuntime runtime;

    private RepoFixture repo(String notify) {
        RepoFixture repo = RepoFixture.copyInto(dir);
        repo.write("org.yaml", repo.read("org.yaml") + "\nrouter: false\n" + notify);
        repo.commit("notify");
        return repo;
    }

    @AfterEach
    void stop() {
        if (runtime != null) {
            runtime.close();
        }
        if (org != null) {
            org.close();
        }
        if (helpdesk != null) {
            helpdesk.close();
        }
    }

    @Test
    void aConfirmationThatWaitedIsToldOfOnceWithALinkAndNotedOnTheTurn() throws Exception {
        helpdesk = new HelpdeskConnector();
        RepoFixture repo = repo("notify: {tool: helpdesk/send_message, to: to_email, text: text, after: 60}\n");
        org = OrgHost.open(repo.root(), AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
        ScriptedLlm llm = new ScriptedLlm(
                ScriptedLlm.toolUse("c1", "reset_mfa", Map.of()),
                ScriptedLlm.text("Reset."));
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        HostChat chat = new HostChat(Map.of("acme", org), Optional.of(llm), Instant::now, self::get);
        runtime = new ChatRuntime(new InMemoryChatStore(), new ChatEvents(), chat);
        self.set(runtime);
        Conversation conversation = runtime.store().create(PRIYA, "mfa", chat.pin(PRIYA, "helpdesk"));
        Turn turn = runtime.say(PRIYA, conversation.id(), "Reset my MFA", List.of());
        ChatRuntime.PendingDecision pending = awaitPending();

        AtomicReference<Instant> now = new AtomicReference<>(pending.askedAt().plusSeconds(30));
        Nudges nudges = new Nudges(Map.of("acme", org), self::get, id -> "https://agents.acme.example/c/" + id, now::get);

        assertThat(nudges.tellWhoIsWaited()).as("not waited long enough").isZero();
        now.set(pending.askedAt().plusSeconds(61));
        assertThat(nudges.tellWhoIsWaited()).isEqualTo(1);
        assertThat(nudges.tellWhoIsWaited()).as("once").isZero();

        assertThat(helpdesk.calls("send_message")).singleElement().satisfies(call -> {
            assertThat(call.arguments()).containsEntry("to_email", HelpdeskConnector.PRIYA);
            assertThat(String.valueOf(call.arguments().get("text"))).contains("waiting for you to confirm reset_mfa",
                    "https://agents.acme.example/c/" + conversation.id());
        });
        assertThat(runtime.store().turn(PRIYA, conversation.id(), turn.id()).orElseThrow().steps())
                .filteredOn(s -> s.kind() == Step.Kind.NOTE && s.name().equals("notified")).singleElement()
                .satisfies(s -> assertThat(s.detail()).containsEntry("to", HelpdeskConnector.PRIYA));

        runtime.decide(PRIYA, pending.id(), ApprovalDecision.approve(), "priya");
    }

    @Test
    void theToolMustNotifyAndTakeTheArgumentsNamed() throws Exception {
        helpdesk = new HelpdeskConnector();
        RepoFixture repo = repo("notify: {tool: helpdesk/open_ticket, to: to_email, text: text}\n");
        assertThatThrownBy(() -> AgentHost.open(repo.root(), AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN)))))
                .isInstanceOf(DefinitionException.class)
                .satisfies(e -> assertThat(((DefinitionException) e).problems()).extracting(Object::toString)
                        .containsExactly("org.yaml notify.tool: helpdesk/open_ticket is declared request; people are "
                                + "told through a tool that notifies"));
    }

    private ChatRuntime.PendingDecision awaitPending() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            List<ChatRuntime.PendingDecision> pending = runtime.pendingAll();
            if (!pending.isEmpty()) {
                return pending.get(0);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No decision waited");
    }
}
