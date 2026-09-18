package dev.agentkit.acme;

import dev.agentkit.accessdesk.desk.AccessLedger;
import dev.agentkit.accessdesk.desk.CompanyClient;
import dev.agentkit.accessdesk.ledger.AccessLedgerConnector;
import dev.agentkit.accessdesk.systems.CompanySystems;
import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.deferred.DeferredActionStore;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.host.AgentHost;
import dev.agentkit.host.DeferredWork;
import dev.agentkit.host.HostChat;
import dev.agentkit.host.OrgHost;
import dev.agentkit.host.Secrets;
import dev.agentkit.host.Tenant;
import dev.agentkit.mcp.InProcessMcpConnection;
import dev.agentkit.onboarding.OnboardingConnector;
import dev.agentkit.onboarding.OnboardingSystems;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Acme as it runs: {@code orgs/acme} loaded by the agent host, with each of its connectors — the company systems, the
 * access ledger and the onboarding systems — served over HTTP behind the bearer token the organization's secrets
 * name, for one test's systems and on its clock. Nothing about Acme's agents is code on the host's side of the
 * connectors.
 */
public final class AcmeOnTheHost implements AutoCloseable {

    public static final Path ORG = Path.of("orgs", "acme");

    private final HttpConnector company;
    private final AccessLedgerConnector ledger;
    private final HttpConnector onboarding;
    private final OrgHost org;
    private final DeferredWork deferred;
    private final HostChat chat;
    private final ChatRuntime runtime;

    private AcmeOnTheHost(HttpConnector company, AccessLedgerConnector ledger, HttpConnector onboarding, OrgHost org,
                          DeferredWork deferred, HostChat chat, ChatRuntime runtime) {
        this.company = company;
        this.ledger = ledger;
        this.onboarding = onboarding;
        this.org = org;
        this.deferred = deferred;
        this.chat = chat;
        this.runtime = runtime;
    }

    /**
     * @param stores each agent's deferred actions, by agent id
     */
    public static AcmeOnTheHost start(CompanySystems companySystems, AccessLedger accessLedger,
                                      OnboardingSystems onboardingSystems, Function<String, DeferredActionStore> stores,
                                      Supplier<Instant> clock, LlmClient llm) {
        try {
            HttpConnector company = HttpConnector.serve(0, "company-systems", "", "company-token",
                    companySystems.catalog());
            AccessLedgerConnector ledger = AccessLedgerConnector.serve(0, "ledger-token", accessLedger,
                    new CompanyClient(new InProcessMcpConnection(companySystems.catalog())), clock, 3600);
            HttpConnector onboarding = OnboardingConnector.serve(0, "onboarding-token", onboardingSystems);
            OrgHost org = OrgHost.open(ORG, AgentHost.Options.hosted(Secrets.of(Map.of(
                    "COMPANY_URL", company.url(), "COMPANY_TOKEN", "company-token",
                    "LEDGER_URL", ledger.url(), "LEDGER_TOKEN", "ledger-token",
                    "ONBOARDING_URL", onboarding.url(), "ONBOARDING_TOKEN", "onboarding-token"))));
            DeferredWork deferred = new DeferredWork(org, stores, clock, Optional.of(llm));
            AtomicReference<ChatRuntime> self = new AtomicReference<>();
            HostChat chat = new HostChat(Map.of("acme", org), Map.of("acme", deferred), Optional.of(llm), clock,
                    self::get);
            ChatRuntime runtime = new ChatRuntime(new InMemoryChatStore(), new ChatEvents(), chat);
            self.set(runtime);
            return new AcmeOnTheHost(company, ledger, onboarding, org, deferred, chat, runtime);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Deferred stores that give {@code agentId} {@code store} and every other agent an empty one of its own: each agent
     * runs the actions in its store with its own subjects, so a store shared with another agent would have that
     * agent claim actions about subjects it cannot look up.
     */
    public static Function<String, DeferredActionStore> storeFor(String agentId, DeferredActionStore store) {
        return agent -> agent.equals(agentId) ? store : DeferredActionStore.inMemory();
    }

    public ChatRuntime runtime() {
        return runtime;
    }

    public HostChat chat() {
        return chat;
    }

    /** The runtime's tenant for a person. */
    public String tenant(String who) {
        return new Tenant("acme", who).id();
    }

    /** A new conversation of {@code who}'s with {@code agentId}, at the current version. */
    public Conversation start(String who, String agentId, String title) {
        return runtime.store().create(tenant(who), title, chat.pin(tenant(who), agentId));
    }

    /** The organization as loaded. */
    public OrgHost org() {
        return org;
    }

    /** The organization's deferred work, as the host runs it. */
    public DeferredWork deferred() {
        return deferred;
    }

    @Override
    public void close() {
        runtime.close();
        deferred.close();
        org.close();
        onboarding.close();
        ledger.close();
        company.close();
    }
}
