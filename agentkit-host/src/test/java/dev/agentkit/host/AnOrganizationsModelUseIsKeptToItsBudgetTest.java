package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
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
import dev.agentkit.core.reliability.ModelPricing;
import dev.agentkit.host.models.Budget;
import dev.agentkit.host.models.HostLimits;
import dev.agentkit.host.models.ModelAccounts;
import dev.agentkit.host.models.UsageLedger;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each organization's model calls are its own: on the host's account unless its {@code org.yaml} names its own
 * provider, recorded by agent, and refused — in a sentence saying which budget and when it starts again — once the
 * organization's own budget, or the host's budget for what the host pays, is spent.
 */
class AnOrganizationsModelUseIsKeptToItsBudgetTest {

    private static final String PRIYA = new Tenant("acme", HelpdeskConnector.PRIYA).id();
    private static final String MODEL = "anthropic/claude-sonnet-5";

    @TempDir
    Path dir;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-18T12:00:00Z"));
    private final Answering hostAccount = new Answering("from the host's account");
    private final Answering ownAccount = new Answering("from acme's own account");
    private final List<String> opened = new ArrayList<>();
    private HelpdeskConnector helpdesk;
    private RepoFixture repo;
    private OrgHost org;
    private ChatRuntime runtime;
    private ModelAccounts models;

    /** A model that answers, each call costing 400 tokens in and 400 out. */
    static final class Answering implements LlmClient {
        private final String answer;
        final List<LlmRequest> received = new ArrayList<>();

        Answering(String answer) {
            this.answer = answer;
        }

        @Override
        public synchronized LlmResponse generate(LlmRequest request) {
            received.add(request);
            return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(answer)), LlmStopReason.END_TURN,
                    new TokenUsage(400, 400));
        }
    }

    private void start(String orgYamlAdds, HostLimits limits, Map<String, String> secrets) throws Exception {
        helpdesk = new HelpdeskConnector();
        repo = RepoFixture.copyInto(dir);
        repo.write("org.yaml", repo.read("org.yaml") + orgYamlAdds);
        repo.commit("acme");
        Map<String, String> all = new java.util.HashMap<>(secrets);
        all.put("HELPDESK_URL", helpdesk.url());
        all.put("HELPDESK_TOKEN", HelpdeskConnector.TOKEN);
        org = OrgHost.open(repo.root(), AgentHost.Options.hosted(Secrets.of(all)));
        models = new ModelAccounts(Optional.of(hostAccount), limits, UsageLedger.inMemory(),
                name -> Secrets.of(secrets), (provider, key) -> {
                    opened.add(provider + " with " + key);
                    return ownAccount;
                }, now::get, Duration.ofSeconds(5));
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        HostChat chat = new HostChat(Map.of("acme", org), Map.of(), models, now::get, self::get);
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

    @Test
    void aTurnIsRefusedOnceTheOrganizationsOwnBudgetIsSpentAndGoesOnTheNextDay() throws Exception {
        start("""

                budget:
                  tokensPerDay: 1500
                """, HostLimits.none(), Map.of());
        Conversation conversation = runtime.store().create(PRIYA, "laptop", new Conversation.Pin("helpdesk",
                org.current().repo().version()));

        assertThat(say(conversation).answer()).isEqualTo("from the host's account");
        assertThat(say(conversation).answer()).as("800 of 1,500 spent: the call that crosses the cap is made")
                .isEqualTo("from the host's account");
        Turn refused = say(conversation);

        assertThat(refused.state()).isEqualTo(Turn.State.FAILED);
        assertThat(refused.detail()).isEqualTo("Your organization has used its model budget for today (1,500 "
                + "tokens), set in its org.yaml. It starts again at midnight UTC.");
        assertThat(hostAccount.received).hasSize(2);

        Map<String, Object> report = models.report(org.current().repo());
        assertThat(report).containsEntry("account", "host");
        assertThat(report.get("today")).isEqualTo(Map.of("calls", 2L, "inputTokens", 800L, "outputTokens", 800L,
                "usd", 0.0));
        assertThat(report.get("byAgent").toString()).contains("agent=helpdesk", "model=" + MODEL, "account=host");
        assertThat(report.get("budgets").toString()).contains("setBy=in its org.yaml", "1,500 tokens a day",
                "reached=for today (1,500 tokens)");
        assertThat(report.get("unpriced")).isEqualTo(List.of(MODEL));

        now.set(Instant.parse("2026-09-19T00:00:01Z"));
        assertThat(say(conversation).answer()).isEqualTo("from the host's account");
    }

    @Test
    void theHostsBudgetCapsWhatTheHostPaysForAndNotWhatTheOrganizationPaysForItself() throws Exception {
        HostLimits limits = new HostLimits(Map.of(MODEL, ModelPricing.of(3, 15)),
                new HostLimits.Limits(0, Budget.NONE),
                Map.of("acme", new HostLimits.Limits(0, new Budget(0, 0, 0, 0.01))));
        start("", limits, Map.of("MODEL_API_KEY", "acme-key"));
        Conversation conversation = runtime.store().create(PRIYA, "laptop", new Conversation.Pin("helpdesk",
                org.current().repo().version()));

        // 400 in at $3 and 400 out at $15 a million: $0.0072 a call.
        assertThat(say(conversation).answer()).isEqualTo("from the host's account");
        assertThat(say(conversation).answer()).isEqualTo("from the host's account");
        assertThat(say(conversation).detail()).isEqualTo("Your organization has used its model budget for this month "
                + "($0.01), set by the host. It starts again on 1 October.");

        repo.write("org.yaml", repo.read("org.yaml") + "\nprovider: openrouter\n");
        repo.commit("our own account");
        org.reload();

        assertThat(say(conversation).answer()).as("a conversation begun on the host's account goes on on the org's own")
                .isEqualTo("from acme's own account");
        assertThat(opened).containsExactly("openrouter with acme-key");
        Map<String, Object> report = models.report(org.current().repo());
        assertThat(report).containsEntry("account", "openrouter");
        assertThat(report.get("budgets")).isEqualTo(List.of());
        assertThat(report.get("byAgent").toString()).contains("account=host", "account=own");
    }

    @Test
    void anOwnProviderWithoutItsKeyOrADollarBudgetWithoutAPriceIsSaidAndNotGuessed() throws Exception {
        start("\nprovider: anthropic\n", HostLimits.none(), Map.of());
        Conversation conversation = runtime.store().create(PRIYA, "laptop", new Conversation.Pin("helpdesk",
                org.current().repo().version()));

        assertThat(say(conversation).detail()).isEqualTo("acme's org.yaml names anthropic as its model provider, and "
                + "its MODEL_API_KEY secret is not set.");

        repo.write("org.yaml", repo.read("org.yaml").replace("provider: anthropic", "budget: {usdPerMonth: 50}"));
        repo.commit("the host's account, with a budget in dollars");
        org.reload();

        assertThat(say(conversation).detail()).isEqualTo("The host has no price for the model " + MODEL + ", so it "
                + "cannot keep acme to a budget in dollars. The host's operator adds it to the host's limits.");
        assertThat(hostAccount.received).isEmpty();
        assertThat(ownAccount.received).isEmpty();
    }

    private Turn say(Conversation conversation) {
        Turn turn = runtime.say(conversation.tenantId(), conversation.id(), "My laptop will not boot", List.of());
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Turn> done = runtime.store().turn(conversation.tenantId(), conversation.id(), turn.id());
            if (done.isPresent() && done.get().state().isTerminal()) {
                return done.get();
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
