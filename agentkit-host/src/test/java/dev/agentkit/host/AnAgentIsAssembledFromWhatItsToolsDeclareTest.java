package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.host.repo.DefinitionException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An agent is its definition assembled against what its connectors' tools declare: which tools it is given, which
 * stop for the person, which arguments are the person's and not the model's — and a definition that would hand a
 * model a grant nobody confirms is refused before anyone can talk to it.
 */
class AnAgentIsAssembledFromWhatItsToolsDeclareTest {

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private RepoFixture repo;

    @BeforeEach
    void start() throws Exception {
        helpdesk = new HelpdeskConnector();
        repo = RepoFixture.copyInto(dir);
    }

    @AfterEach
    void stop() {
        helpdesk.close();
    }

    private AgentHost open() {
        return AgentHost.open(repo.root(), "v1", AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
    }

    @Test
    void theAgentGetsTheToolsItsEffectsSelectAndNothingThatTakesAway() {
        try (AgentHost host = open()) {
            HostedAgent agent = host.agent("helpdesk").orElseThrow();
            Principal priya = host.principal(HelpdeskConnector.PRIYA).orElseThrow();

            assertThat(agent.qualifiedName()).isEqualTo("helpdesk@v1");
            assertThat(agent.model()).isEqualTo("anthropic/claude-sonnet-5");
            assertThat(agent.unavailable()).isEmpty();
            assertThat(agent.tools(priya).entries()).extracting(e -> e.tool().name())
                    .containsExactly("directory_lookup", "open_ticket", "reset_mfa", "send_message");
            assertThat(agent.confirmed()).containsExactly("reset_mfa");

            HostedAgent security = host.agent("security-desk").orElseThrow();
            assertThat(security.tools(priya).entries()).extracting(e -> e.tool().name()).containsExactly("directory_lookup");
        }
    }

    @Test
    void aBoundArgumentIsHiddenFromTheModelAndFilledFromThePersonWhateverTheModelSends() {
        try (AgentHost host = open()) {
            Principal priya = host.principal(HelpdeskConnector.PRIYA).orElseThrow();
            Tool ticket = tool(host.agent("helpdesk").orElseThrow().tools(priya), "open_ticket");

            assertThat(properties(ticket.inputSchema())).containsOnlyKeys("summary");
            assertThat(ticket.inputSchema().get("required")).isEqualTo(List.of("summary"));
            assertThat(properties(ticket.spec().inputSchema())).containsOnlyKeys("summary");

            ToolResult opened = ticket.execute(new ToolInvocation("c1", "open_ticket", new HashMap<>(Map.of(
                    "summary", "Laptop will not boot", "requester", "ceo@acme.example"))));

            assertThat(opened.isError()).isFalse();
            assertThat(helpdesk.calls("open_ticket")).singleElement().satisfies(call ->
                    assertThat(call.arguments()).containsEntry("requester", HelpdeskConnector.PRIYA)
                            .containsEntry("summary", "Laptop will not boot"));
        }
    }

    @Test
    void aBindingThePersonsRecordCannotFillRefusesTheCall() {
        repo.edit("agents/helpdesk/agent.yaml", "{requester: principal.email}", "{requester: principal.employee_id}");
        try (AgentHost host = open()) {
            Principal dana = host.principal(HelpdeskConnector.DANA).orElseThrow();
            ToolResult refused = tool(host.agent("helpdesk").orElseThrow().tools(dana), "open_ticket")
                    .execute(new ToolInvocation("c1", "open_ticket", new HashMap<>(Map.of("summary", "x"))));

            assertThat(refused.isError()).isTrue();
            assertThat(refused.content()).contains("principal.employee_id");
            assertThat(helpdesk.calls("open_ticket")).isEmpty();
        }
    }

    @Test
    void thePersonIsWhoTheDirectorySaysAndTheAudienceIsChecked() {
        try (AgentHost host = open()) {
            Principal priya = host.principal("  Priya.Natarajan@ACME.example ").orElseThrow();

            assertThat(priya.email()).isEqualTo(HelpdeskConnector.PRIYA);
            assertThat(priya.org()).isEqualTo("acme");
            assertThat(priya.groups()).containsExactlyInAnyOrder("engineering", "payments");
            assertThat(priya.facts()).containsEntry("manager", HelpdeskConnector.DANA).doesNotContainKey("groups");
            assertThat(host.principal("stranger@acme.example")).isEmpty();

            assertThat(host.agentsFor(priya)).extracting(a -> a.definition().id()).containsExactly("helpdesk");
            Principal security = new Principal("acme", "sam", "sam@acme.example", java.util.Set.of("security"), Map.of());
            assertThat(host.agentsFor(security)).extracting(a -> a.definition().id())
                    .containsExactlyInAnyOrder("helpdesk", "security-desk");
            Principal outsider = new Principal("globex", "x", "x@globex.example", java.util.Set.of("security"), Map.of());
            assertThat(host.agentsFor(outsider)).isEmpty();
        }
    }

    @Test
    void theSystemPromptCarriesThePolicyThePersonFencedAndTheTime() {
        try (AgentHost host = open()) {
            Principal priya = host.principal(HelpdeskConnector.PRIYA).orElseThrow();
            String prompt = host.agent("helpdesk").orElseThrow().systemPrompt(priya, Instant.parse("2026-09-18T12:00:00Z"));

            assertThat(prompt).startsWith("You are Acme's IT helpdesk.")
                    .contains("Helpdesk policy:")
                    .contains("- email: " + HelpdeskConnector.PRIYA)
                    .contains("- manager: " + HelpdeskConnector.DANA)
                    .contains("<untrusted")
                    .endsWith("The time now is 2026-09-18T12:00:00Z (UTC).");
        }
    }

    @Test
    void aGrantNobodyConfirmsIsRefusedUnlessTheConnectorHoldsTheRules() {
        repo.edit("agents/helpdesk/agent.yaml", "confirm:\n  - helpdesk/reset_mfa\n", "");

        assertThatThrownBy(this::open).isInstanceOfSatisfying(DefinitionException.class, e ->
                assertThat(e.problems()).extracting(Object::toString).containsExactly(
                        "agents/helpdesk/agent.yaml confirm: helpdesk/reset_mfa grants something, so it must be confirmed, "
                                + "or helpdesk marked authoritative because it enforces its own rules"));

        repo.edit("connectors/helpdesk.yaml", "trustAnnotations: true", "trustAnnotations: true\nauthoritative: true");
        try (AgentHost host = open()) {
            assertThat(host.agent("helpdesk").orElseThrow().confirmed()).isEmpty();
        }
    }

    @Test
    void whatOnlyTheConnectorCanAnswerIsCheckedAgainstItAllAtOnce() {
        repo.edit("agents/helpdesk/agent.yaml", "  - helpdesk/reset_mfa", "  - helpdesk/delete_account")
                .edit("agents/helpdesk/agent.yaml", "{email: principal.email}", "{account: principal.email}")
                .edit("agents/security-desk/agent.yaml", "tools: [directory_lookup]", "tools: [directory_lookup, whois]")
                .write("agents/empty/agent.yaml", """
                        name: Empty
                        prompt: {system: s.md}
                        tools: [{connector: helpdesk, effects: [schedule]}]
                        """)
                .write("agents/empty/s.md", "Nothing to do.");

        assertThatThrownBy(this::open).isInstanceOfSatisfying(DefinitionException.class, e ->
                assertThat(e.problems()).extracting(Object::toString).containsExactlyInAnyOrder(
                        "agents/empty/agent.yaml tools[0]: selects no tool of helpdesk",
                        "agents/helpdesk/agent.yaml confirm[0]: helpdesk/delete_account is not one of this agent's tools",
                        "agents/helpdesk/agent.yaml bind.helpdesk/reset_mfa.account: helpdesk/reset_mfa has no argument account",
                        "agents/helpdesk/agent.yaml confirm: helpdesk/reset_mfa grants something, so it must be confirmed, "
                                + "or helpdesk marked authoritative because it enforces its own rules",
                        "agents/security-desk/agent.yaml tools[0].tools: helpdesk has no tool whois that declares what it does"));
    }

    @Test
    void aSubjectIsLookedUpByAToolTheConnectorHasWithTheArgumentNamed() {
        repo.write("agents/helpdesk/deferred.md", "Carry out one deferred action.")
                .edit("agents/helpdesk/agent.yaml", "limits:", """
                        deferred:
                          prompt: deferred.md
                          actor: helpdesk
                          subjects:
                            person: {tool: helpdesk/directory_lookup, argument: email}
                            ticket: {tool: helpdesk/get_ticket, argument: ticket_id}
                            account: {tool: helpdesk/delete_account, argument: account}
                        limits:""");

        assertThatThrownBy(this::open).isInstanceOfSatisfying(DefinitionException.class, e ->
                assertThat(e.problems()).extracting(Object::toString).containsExactlyInAnyOrder(
                        "agents/helpdesk/agent.yaml deferred.subjects.ticket.tool: helpdesk has no tool get_ticket that "
                                + "declares what it does",
                        "agents/helpdesk/agent.yaml deferred.subjects.account.argument: helpdesk/delete_account has no "
                                + "argument account"));

        repo.edit("agents/helpdesk/agent.yaml", "    ticket: {tool: helpdesk/get_ticket, argument: ticket_id}\n", "")
                .edit("agents/helpdesk/agent.yaml", "    account: {tool: helpdesk/delete_account, argument: account}\n", "");
        try (AgentHost host = open()) {
            HostedAgent agent = host.agent("helpdesk").orElseThrow();
            assertThat(agent.actor()).hasValueSatisfying(actor -> assertThat(actor.email()).isEqualTo("helpdesk"));
            assertThat(agent.deferredConfig()).hasValueSatisfying(c ->
                    assertThat(c.systemPrompt()).isEqualTo("Carry out one deferred action."));
            // The connector is asked for the record itself: an object in no particular shape still names its subject,
            // and a lookup that fails is a subject that does not exist.
            assertThat(agent.subjects().orElseThrow().resolve("person", HelpdeskConnector.PRIYA)).hasValueSatisfying(r ->
                    assertThat(r.identifiers()).containsExactly(HelpdeskConnector.PRIYA));
            assertThat(agent.subjects().orElseThrow().resolve("person", "nobody@acme.example")).isEmpty();
        }
    }

    @Test
    void twoConnectorsOfferingTheSameToolNameCannotBothBeSelected() throws Exception {
        try (HelpdeskConnector chat = new HelpdeskConnector(name -> name.equals("send_message"))) {
            repo.write("connectors/chat.yaml", "url: " + chat.url() + "\nheaders: {Authorization: Bearer "
                            + HelpdeskConnector.TOKEN + "}\ntrustAnnotations: true\n")
                    .edit("agents/helpdesk/agent.yaml", "effects: [read, request, notify, grant]",
                            "effects: [read, request, notify, grant]\n  - connector: chat");

            assertThatThrownBy(this::open).isInstanceOfSatisfying(DefinitionException.class, e ->
                    assertThat(e.problems()).extracting(Object::toString).containsExactly(
                            "agents/helpdesk/agent.yaml tools[1]: both helpdesk and chat have a tool named send_message; "
                                    + "select only one of them"));
        }
    }

    @Test
    void aConnectorThatIsDownMakesItsAgentsUnavailableAndNothingElse() {
        repo.write("connectors/crm.yaml", "url: http://127.0.0.1:1/mcp\n")
                .write("agents/sales/agent.yaml", "name: Sales\nprompt: {system: s.md}\ntools: [{connector: crm}]\n")
                .write("agents/sales/s.md", "You help with sales.");
        try (AgentHost host = open()) {
            assertThat(host.agent("sales").orElseThrow().unavailable()).contains("The crm connector could not be reached.");
            assertThat(host.agent("helpdesk").orElseThrow().unavailable()).isEmpty();
        }
    }

    @Test
    void aSecretThatIsNotSetOrALocalCommandIsRefusedBeforeAnythingConnects() {
        repo.write("connectors/local.yaml", "command: [\"./server\", \"${dataDir}\"]\n");

        assertThatThrownBy(() -> AgentHost.open(repo.root(), "v1", AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url())))))
                .isInstanceOfSatisfying(DefinitionException.class, e ->
                        assertThat(e.problems()).extracting(Object::toString).containsExactlyInAnyOrder(
                                "connectors/helpdesk.yaml: the secret HELPDESK_TOKEN is not set",
                                "connectors/local.yaml command: this host only reaches connectors by url"));
        assertThat(helpdesk.calls).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private static Tool tool(DeclaredTools tools, String name) {
        return tools.entry(name).orElseThrow().tool();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }
}
