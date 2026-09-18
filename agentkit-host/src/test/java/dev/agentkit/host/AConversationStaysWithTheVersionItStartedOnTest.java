package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.ChatUnavailable;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.host.repo.DefinitionException;
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
 * One runtime serves all of an organization's agents, and a merge to its repository changes what new conversations
 * start with — not what a conversation already under way is talking to. A version that will not load is refused
 * while the one before it keeps serving, and a conversation whose version has been let go is told to start again.
 */
class AConversationStaysWithTheVersionItStartedOnTest {

    private static final String PRIYA = new Tenant("acme", HelpdeskConnector.PRIYA).id();

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private RepoFixture repo;
    private OrgHost org;
    private ChatRuntime runtime;
    private HostChat chat;
    private final ScriptedLlm llm = new ScriptedLlm(
            ScriptedLlm.text("one"), ScriptedLlm.text("two"), ScriptedLlm.text("three"), ScriptedLlm.text("four"));

    @BeforeEach
    void start() throws Exception {
        helpdesk = new HelpdeskConnector();
        repo = RepoFixture.copyInto(dir);
        repo.commit("the helpdesk");
        org = OrgHost.open(repo.root(), AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        chat = new HostChat(Map.of("acme", org), Optional.of(llm), () -> Instant.parse("2026-09-18T12:00:00Z"),
                self::get);
        runtime = new ChatRuntime(new InMemoryChatStore(), new ChatEvents(), chat);
        self.set(runtime);
    }

    @AfterEach
    void stop() {
        runtime.close();
        org.close();
        helpdesk.close();
    }

    @Test
    void thePersonIsOfferedTheAgentsTheirGroupsAdmitAndOneIsChosenForThemWhenThereIsOnlyOne() {
        assertThat(chat.available(PRIYA)).extracting(a -> a.get("id")).containsExactly("helpdesk");
        assertThat(chat.available(PRIYA).get(0)).containsEntry("name", "IT Helpdesk");

        Conversation.Pin pin = chat.pin(PRIYA, null);
        assertThat(pin).isEqualTo(new Conversation.Pin("helpdesk", org.current().repo().version()));

        assertThatThrownBy(() -> chat.pin(PRIYA, "security-desk")).isInstanceOf(ChatUnavailable.class)
                .hasMessage("There is no agent security-desk for you.");
        assertThat(chat.available(new Tenant("globex", HelpdeskConnector.PRIYA).id())).isEmpty();
        assertThat(chat.available(new Tenant("acme", "stranger@acme.example").id())).isEmpty();
        assertThat(chat.available("not a tenant")).isEmpty();
    }

    @Test
    void aMergeChangesNewConversationsAndLeavesTheOneUnderWayAlone() {
        String first = org.current().repo().version();
        Conversation before = runtime.store().create(PRIYA, "before", chat.pin(PRIYA, "helpdesk"));
        assertThat(say(before).state()).isEqualTo(Turn.State.COMPLETED);

        repo.edit("agents/helpdesk/policy.md", "Helpdesk policy:", "Helpdesk policy, revised:");
        String second = repo.commit("revise the policy");
        assertThat(org.reload()).isEqualTo(second);
        assertThat(org.versions()).containsExactly(first, second);

        Conversation after = runtime.store().create(PRIYA, "after", chat.pin(PRIYA, "helpdesk"));
        assertThat(after.agent().version()).isEqualTo(second);
        say(before);
        say(after);

        List<String> prompts = llm.received().stream().map(r -> r.system().orElse("")).toList();
        assertThat(prompts.get(0)).contains("Helpdesk policy:");
        assertThat(prompts.get(1)).as("the conversation started before the merge").contains("Helpdesk policy:")
                .doesNotContain("revised");
        assertThat(prompts.get(2)).as("the conversation started after it").contains("Helpdesk policy, revised:");
    }

    @Test
    void aVersionThatWillNotLoadIsRefusedAndTheLastGoodOneKeepsServing() {
        String good = org.current().repo().version();
        repo.edit("agents/helpdesk/agent.yaml", "confirm:", "confrim:");
        repo.commit("a typo");

        assertThatThrownBy(org::reload).isInstanceOf(DefinitionException.class).hasMessageContaining("confrim");
        assertThat(org.current().repo().version()).isEqualTo(good);
        assertThat(chat.pin(PRIYA, "helpdesk").version()).isEqualTo(good);
    }

    @Test
    void aConversationWhoseVersionWasLetGoIsToldToStartAgain() {
        Conversation old = runtime.store().create(PRIYA, "old", chat.pin(PRIYA, "helpdesk"));
        for (int i = 1; i <= OrgHost.RETAINED; i++) {
            repo.edit("agents/helpdesk/prompts/system.md", "Keep answers short", "Keep answers short (" + i + ")");
            repo.commit("revision " + i);
            org.reload();
        }
        assertThat(org.versions()).hasSize(OrgHost.RETAINED).doesNotContain(old.agent().version());

        Turn refused = say(old);
        assertThat(refused.state()).isEqualTo(Turn.State.FAILED);
        assertThat(refused.detail()).isEqualTo("This conversation was with a version of helpdesk this host no longer "
                + "runs. Start a new conversation to talk to the current one.");
    }

    @Test
    void aConversationWithNoAgentOrSomeoneElsesOrganizationIsRefusedInASentence() {
        Turn unpinned = say(runtime.store().create(PRIYA, "no agent"));
        assertThat(unpinned.detail()).isEqualTo("This conversation is not with any agent. Start a new one.");

        String globex = new Tenant("globex", "someone@globex.example").id();
        Turn elsewhere = say(runtime.store().create(globex, "x", new Conversation.Pin("helpdesk",
                org.current().repo().version())));
        assertThat(elsewhere.detail()).isEqualTo("Your organization has no agents here.");
    }

    private Turn say(Conversation conversation) {
        Turn turn = runtime.say(conversation.tenantId(), conversation.id(), "hello", List.of());
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Turn> now = runtime.store().turn(conversation.tenantId(), conversation.id(), turn.id());
            if (now.isPresent() && now.get().state().isTerminal()) {
                return now.get();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("The turn did not finish");
    }
}
