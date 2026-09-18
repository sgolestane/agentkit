package dev.agentkit.accessdesk.evals;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.accessdesk.evals.AccessDeskEvalTest.Harness;
import dev.agentkit.accessdesk.evals.AccessDeskEvalTest.World;
import dev.agentkit.accessdesk.ledger.AccessLedgerConnector;
import dev.agentkit.accessdesk.ledger.HttpConnector;
import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.eval.CaseReport;
import dev.agentkit.host.AgentHost;
import dev.agentkit.host.DeferredWork;
import dev.agentkit.host.HostChat;
import dev.agentkit.host.OrgHost;
import dev.agentkit.host.Secrets;
import dev.agentkit.host.Tenant;
import dev.agentkit.openrouter.OpenRouterLlmClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The same six conversations as {@link AccessDeskEvalTest}, scored by the same checks — with Access Desk running on
 * the agent host instead of as its own application.
 *
 * <p>Nothing about Access Desk is code here. It is {@code orgs/acme}: an {@code agent.yaml}, the policy and prompts,
 * and two connectors — the company systems, and the access ledger ({@link AccessLedgerConnector}), which holds the
 * desk's rules. Both are reached over MCP on HTTP, with bearer tokens from the organization's secrets, exactly as the
 * host reaches any connector. Deferred actions are scheduled through the host's {@link DeferredWork}.
 *
 * <pre>
 * ACCESS_DESK_EVAL=true OPENROUTER_API_KEY=sk-or-... \
 *   ./mvnw -pl agentkit-examples-access-desk test -Dtest=AccessDeskOnTheHostEvalTest
 * </pre>
 */
class AccessDeskOnTheHostEvalTest {

    static final Path ORG = Path.of("orgs", "acme");

    @TestFactory
    Stream<DynamicTest> accessDeskOnTheHostFollowsThePolicy() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("ACCESS_DESK_EVAL")),
                "Set ACCESS_DESK_EVAL=true to run the Access Desk evals against a real model.");
        String key = System.getenv(OpenRouterLlmClient.API_KEY_ENV);
        Assumptions.assumeTrue(key != null && !key.isBlank(), "Set OPENROUTER_API_KEY to run the Access Desk evals.");
        LlmClient llm = OpenRouterLlmClient.builder(key).title("agentkit access desk on the host evals").build();
        String subset = System.getenv().getOrDefault("ACCESS_DESK_EVAL_CASES", "");
        Set<String> wanted = subset.isBlank() ? Set.of() : Set.of(subset.strip().split("[\\s,]+"));

        return AccessDeskEvalTest.cases().stream().filter(c -> wanted.isEmpty() || wanted.contains(c.name()))
                .map(c -> DynamicTest.dynamicTest(c.name(), () -> {
                    CaseReport report = AccessDeskEvalTest.run(c, world -> onTheHost(world, llm));
                    assertThat(report.failures()).as("failed checks for %s", c.name()).isEmpty();
                }));
    }

    /**
     * The case's world behind two connectors on HTTP, and Access Desk loaded from {@code orgs/acme} by the host, with
     * the case's clock.
     */
    /** The harness, and the organization it loaded, for a test that needs to reach past the conversation. */
    interface HostHarness extends Harness {
        OrgHost org();
    }

    static HostHarness onTheHost(World world, LlmClient llm) {
        try {
            HttpConnector company = HttpConnector.serve(0, "company-systems", "", "company-token",
                    world.systems.catalog());
            AccessLedgerConnector ledger = AccessLedgerConnector.serve(0, "ledger-token", world.ledger, world.company,
                    () -> AccessDeskEvalTest.NOW, 3600);
            OrgHost org = OrgHost.open(ORG, AgentHost.Options.hosted(Secrets.of(Map.of(
                    "COMPANY_URL", company.url(), "COMPANY_TOKEN", "company-token",
                    "LEDGER_URL", ledger.url(), "LEDGER_TOKEN", "ledger-token"))));
            DeferredWork deferred = new DeferredWork(org, agent -> world.store, () -> AccessDeskEvalTest.NOW,
                    Optional.of(llm));
            AtomicReference<ChatRuntime> self = new AtomicReference<>();
            HostChat chat = new HostChat(Map.of("acme", org), Map.of("acme", deferred), Optional.of(llm),
                    () -> AccessDeskEvalTest.NOW, self::get);
            ChatRuntime runtime = new ChatRuntime(new InMemoryChatStore(), new ChatEvents(), chat);
            self.set(runtime);
            return new HostHarness() {
                @Override
                public OrgHost org() {
                    return org;
                }

                @Override
                public ChatRuntime runtime() {
                    return runtime;
                }

                @Override
                public String tenant(String who) {
                    return new Tenant("acme", who).id();
                }

                @Override
                public Conversation start(String who, String title) {
                    return runtime.store().create(tenant(who), title, chat.pin(tenant(who), "access-desk"));
                }

                @Override
                public void close() {
                    runtime.close();
                    deferred.close();
                    org.close();
                    ledger.close();
                    company.close();
                }
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
