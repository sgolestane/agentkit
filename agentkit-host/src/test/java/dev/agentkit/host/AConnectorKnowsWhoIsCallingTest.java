package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.host.auth.CallerSigner;
import dev.agentkit.mcp.server.CallerAssertion;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every call the host makes to a connector carries a caller assertion it signs — the organization, the person (or the
 * host itself, for its own lookups), the agent at its version, the conversation and the turn — and a connector that
 * checks it knows who is calling from the assertion, not from an argument. A host whose assertions it cannot check is
 * refused: without one, or signed with a key it does not publish.
 */
class AConnectorKnowsWhoIsCallingTest {

    private static final String ISSUER = "https://agents.acme.example";

    @TempDir
    Path dir;

    private final CallerSigner signer = CallerSigner.generate(ISSUER);
    private HelpdeskConnector helpdesk;
    private final List<AgentHost> hosts = new java.util.ArrayList<>();
    private ChatRuntime runtime;

    @BeforeEach
    void start() throws Exception {
        helpdesk = HelpdeskConnector.guarded(CallerAssertion.fromKeys(signer.jwks(), ISSUER, "helpdesk", "acme"));
    }

    @AfterEach
    void stop() {
        if (runtime != null) {
            runtime.close();
        }
        hosts.forEach(AgentHost::close);
        helpdesk.close();
    }

    private AgentHost open(Optional<CallerSigner> signedBy) {
        AgentHost.Options options = AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN)));
        AgentHost host = AgentHost.open(RepoFixture.copyInto(dir.resolve("repo" + hosts.size())).root(), "v1",
                signedBy.map(options::signedBy).orElse(options));
        hosts.add(host);
        return host;
    }

    @Test
    void aCallInATurnSaysWhoWithWhichAgentInWhichConversation() throws Exception {
        AgentHost host = open(Optional.of(signer));
        ScriptedLlm llm = new ScriptedLlm(
                ScriptedLlm.toolUse("c1", "open_ticket", Map.of("summary", "Laptop will not boot")),
                ScriptedLlm.text("Opened TICKET-1001."));
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        runtime = new ChatRuntime(new InMemoryChatStore(), new ChatEvents(), host.agent("helpdesk").orElseThrow()
                .chatAgents(Optional.of(llm), host::principal, Instant::now, self::get));
        self.set(runtime);
        Conversation conversation = runtime.store().create(HelpdeskConnector.PRIYA, "laptop");
        Turn turn = runtime.say(HelpdeskConnector.PRIYA, conversation.id(), "My laptop will not boot", List.of());
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!runtime.store().turn(HelpdeskConnector.PRIYA, conversation.id(), turn.id()).orElseThrow().state()
                .isTerminal() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertThat(helpdesk.calls("open_ticket")).singleElement().satisfies(call -> {
            assertThat(call.caller().email()).isEqualTo(HelpdeskConnector.PRIYA);
            assertThat(call.caller().subject()).isEqualTo(HelpdeskConnector.PRIYA);
            assertThat(call.caller().agent()).isEqualTo("helpdesk");
            assertThat(call.caller().agentVersion()).isEqualTo("v1");
            assertThat(call.caller().conversation()).isEqualTo(conversation.id());
            assertThat(call.caller().turn()).isEqualTo(turn.id());
            assertThat(call.arguments()).containsEntry("requester", HelpdeskConnector.PRIYA);
        });
        // Looking the person up was the host's own call, and says so.
        assertThat(helpdesk.calls("directory_lookup")).isNotEmpty()
                .allSatisfy(call -> assertThat(call.caller().isHost()).isTrue());
    }

    @Test
    void aToolOfferedDirectlyOverMcpSaysWhoOutsideAnyConversation() {
        AgentHost host = open(Optional.of(signer));
        Principal priya = host.principal(HelpdeskConnector.PRIYA).orElseThrow();

        host.agent("helpdesk").orElseThrow().directTools(priya).entries().get(0).tool()
                .execute(new ToolInvocation("d1", "directory_lookup", new HashMap<>(Map.of("email", HelpdeskConnector.DANA))));

        assertThat(helpdesk.calls("directory_lookup")).last().satisfies(call -> {
            assertThat(call.caller().email()).isEqualTo(HelpdeskConnector.PRIYA);
            assertThat(call.caller().conversation()).isNull();
        });
    }

    @Test
    void aHostWhoseAssertionsTheConnectorCannotCheckIsRefused() {
        AgentHost unsigned = open(Optional.empty());
        AgentHost forged = open(Optional.of(CallerSigner.generate(ISSUER)));

        // The directory is the first thing the host asks, and without a caller it can check the connector answers no one.
        assertThat(unsigned.principal(HelpdeskConnector.PRIYA)).isEmpty();
        assertThat(forged.principal(HelpdeskConnector.PRIYA)).isEmpty();
        assertThat(helpdesk.calls).isEmpty();
    }
}
