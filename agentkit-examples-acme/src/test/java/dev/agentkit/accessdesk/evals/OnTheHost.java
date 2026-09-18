package dev.agentkit.accessdesk.evals;

import dev.agentkit.accessdesk.evals.AccessDeskEvalTest.World;
import dev.agentkit.accessdesk.ledger.AccessLedgerConnector;
import dev.agentkit.accessdesk.ledger.HttpConnector;
import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.host.AgentHost;
import dev.agentkit.host.DeferredWork;
import dev.agentkit.host.HostChat;
import dev.agentkit.host.OrgHost;
import dev.agentkit.host.Secrets;
import dev.agentkit.host.Tenant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Access Desk as it runs: {@code orgs/acme} loaded by the agent host, with the company systems and the access ledger
 * as MCP connectors over HTTP, behind the bearer tokens the organization's secrets name — for one case's world, on
 * the case's clock. Nothing about Access Desk is code on the host's side of the connectors.
 */
final class OnTheHost {

    static final Path ORG = Path.of("orgs", "acme");

    private OnTheHost() {
    }

    /** A conversation's runtime with Access Desk behind it, and whose conversations are whose. */
    interface Harness extends AutoCloseable {
        ChatRuntime runtime();

        /** The runtime's tenant for a person. */
        String tenant(String who);

        /** A new conversation of {@code who}'s with Access Desk. */
        Conversation start(String who, String title);

        /** The organization as loaded. */
        OrgHost org();

        /** The organization's deferred work, as the host runs it. */
        DeferredWork deferred();

        @Override
        void close();
    }

    static Harness start(World world, LlmClient llm) {
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
            return new Harness() {
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
                public OrgHost org() {
                    return org;
                }

                @Override
                public DeferredWork deferred() {
                    return deferred;
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
