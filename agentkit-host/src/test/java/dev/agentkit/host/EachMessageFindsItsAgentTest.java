package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.ChatUnavailable;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Step;
import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A conversation nobody pinned to an agent sends each message to the one that handles it — chosen by the router, or by
 * the person for that message — or the router answers itself. Each turn records who answered; the router chooses only
 * among the agents the person may use, and has none of their tools.
 */
class EachMessageFindsItsAgentTest {

    private static final String SAM = new Tenant("acme", HelpdeskConnector.SAM).id();
    private static final String PRIYA = new Tenant("acme", HelpdeskConnector.PRIYA).id();

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private OrgHost org;
    private HostChat chat;
    private ChatRuntime runtime;
    /** What the router decides, in order; each agent answers with its own name. */
    private final Deque<String> routes = new ArrayDeque<>();
    private final List<LlmRequest> routerAsked = new ArrayList<>();

    private final LlmClient model = request -> {
        String text;
        if (request.outputSchema().isPresent()) {
            synchronized (routerAsked) {
                routerAsked.add(request);
            }
            text = routes.poll();
        } else {
            String system = request.system().orElse("");
            text = system.contains("security team") ? "Security Desk here." : "Helpdesk here.";
        }
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)), LlmStopReason.END_TURN,
                new TokenUsage(10, 10));
    };

    private void start(String orgYamlAdds) throws Exception {
        helpdesk = new HelpdeskConnector();
        RepoFixture repo = RepoFixture.copyInto(dir);
        repo.write("org.yaml", repo.read("org.yaml") + orgYamlAdds);
        repo.commit("acme");
        org = OrgHost.open(repo.root(), AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        chat = new HostChat(Map.of("acme", org), Optional.of(model), Instant::now, self::get);
        runtime = new ChatRuntime(new InMemoryChatStore(), new ChatEvents(), chat);
        self.set(runtime);
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

    private static String route(String action, String agent, String text) {
        return "{\"why\":\"because\",\"action\":\"" + action + "\",\"agent\":\"" + agent + "\",\"text\":\"" + text + "\"}";
    }

    @Test
    void eachMessageGoesToTheAgentThatHandlesItOrTheRouterAnswers() throws Exception {
        start("");
        assertThat(chat.pin(SAM, null)).as("no agent chosen, and a choice to make").isNull();
        Conversation conversation = runtime.store().create(SAM, "", chat.pin(SAM, null));
        String version = org.current().repo().version();

        routes.add(route("agent", "helpdesk", ""));
        Turn laptop = say(conversation, "My laptop will not boot");
        routes.add(route("agent", "security-desk", ""));
        Turn payroll = say(conversation, "Who is Dana's manager?");
        routes.add(route("answer", "", "Helpdesk opens tickets; Security Desk looks people up."));
        Turn what = say(conversation, "What can you do?");

        assertThat(laptop.answer()).isEqualTo("Helpdesk here.");
        assertThat(laptop.agent()).isEqualTo(new Conversation.Pin("helpdesk", version));
        assertThat(payroll.answer()).isEqualTo("Security Desk here.");
        assertThat(payroll.agent()).isEqualTo(new Conversation.Pin("security-desk", version));
        assertThat(what.answer()).isEqualTo("Helpdesk opens tickets; Security Desk looks people up.");
        assertThat(what.agent()).as("the router answered").isNull();
        assertThat(what.steps()).filteredOn(s -> s.kind() == Step.Kind.NOTE && s.name().equals("routed"))
                .singleElement().satisfies(s -> assertThat(s.detail()).containsEntry("to", "router"));

        // The router saw who answered before, and chose only among Sam's agents; it is given no tools.
        LlmRequest third = routerAsked.get(2);
        String asked = third.messages().stream().map(Message::text).reduce("", String::concat);
        assertThat(asked).contains("Agent helpdesk: Helpdesk here.", "Agent security-desk: Security Desk here.");
        assertThat(third.tools()).isEmpty();
        assertThat(third.outputSchema().orElseThrow().schema().toString())
                .contains("helpdesk", "security-desk");
    }

    @Test
    void aPersonMaySendOneMessageToAnAgentOfTheirChoosingButOnlyOneTheyMayUse() throws Exception {
        start("");
        Conversation conversation = runtime.store().create(SAM, "", chat.pin(SAM, null));
        Conversation.Pin chosen = chat.forMessage(SAM, conversation, "security-desk");

        Turn turn = await(conversation, runtime.say(SAM, conversation.id(), "Look up Dana", List.of(), chosen));

        assertThat(turn.answer()).isEqualTo("Security Desk here.");
        assertThat(turn.agent()).isEqualTo(chosen);
        assertThat(routerAsked).as("no router call for a chosen agent").isEmpty();
        assertThatThrownBy(() -> chat.forMessage(PRIYA, conversation, "security-desk"))
                .isInstanceOf(ChatUnavailable.class).hasMessage("There is no agent security-desk for you.");
    }

    @Test
    void withTheRouterOffAPersonChoosesAnAgentAsBefore() throws Exception {
        start("\nrouter: false\n");

        assertThatThrownBy(() -> chat.pin(SAM, null)).isInstanceOf(ChatUnavailable.class)
                .hasMessage("Choose which agent to talk to.");
        assertThat(chat.pin(PRIYA, null)).as("one agent: pinned to it").isNotNull();
    }

    private Turn say(Conversation conversation, String text) {
        return await(conversation, runtime.say(conversation.tenantId(), conversation.id(), text, List.of()));
    }

    private Turn await(Conversation conversation, Turn turn) {
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
