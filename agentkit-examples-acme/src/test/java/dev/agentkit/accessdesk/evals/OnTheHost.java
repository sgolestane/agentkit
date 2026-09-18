package dev.agentkit.accessdesk.evals;

import dev.agentkit.accessdesk.evals.AccessDeskEvalTest.World;
import dev.agentkit.acme.AcmeOnTheHost;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.host.DeferredWork;
import dev.agentkit.host.OrgHost;
import dev.agentkit.onboarding.OnboardingSystems;


/**
 * Access Desk as it runs: Acme's repository on the agent host with its connectors over HTTP ({@link AcmeOnTheHost}), for
 * one case's world, on the case's clock.
 */
final class OnTheHost {

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
        AcmeOnTheHost acme = AcmeOnTheHost.start(world.systems, world.ledger, OnboardingSystems.open("onboarding", 0),
                AcmeOnTheHost.storeFor("access-desk", world.store), () -> AccessDeskEvalTest.NOW, llm);
        return new Harness() {
            @Override
            public ChatRuntime runtime() {
                return acme.runtime();
            }

            @Override
            public String tenant(String who) {
                return acme.tenant(who);
            }

            @Override
            public Conversation start(String who, String title) {
                return acme.start(who, "access-desk", title);
            }

            @Override
            public OrgHost org() {
                return acme.org();
            }

            @Override
            public DeferredWork deferred() {
                return acme.deferred();
            }

            @Override
            public void close() {
                acme.close();
            }
        };
    }
}
