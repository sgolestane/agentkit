package dev.agentkit.host.models;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.reliability.BudgetExceededException;
import dev.agentkit.host.Secrets;
import dev.agentkit.host.repo.OrgRepo;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * However many calls one organization makes, another's are not queued behind them: each has its own places for calls
 * at once, and a call beyond them waits for one of its own organization's to finish. Deferred work is counted against
 * a budget but never refused by it.
 */
class OneOrganizationsLoadWaitsBehindItselfTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

    /** A model whose calls stay open until released, counting who is inside. */
    static final class Held implements LlmClient {
        final Map<String, CountDownLatch> entered = new ConcurrentHashMap<>();
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public LlmResponse generate(LlmRequest request) {
            String who = request.system().orElse("");
            entered.computeIfAbsent(who, k -> new CountDownLatch(1)).countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of("done")), LlmStopReason.END_TURN,
                    new TokenUsage(10, 10));
        }

        boolean hasEntered(String who, long millis) throws InterruptedException {
            return entered.computeIfAbsent(who, k -> new CountDownLatch(1)).await(millis, TimeUnit.MILLISECONDS);
        }
    }

    private static OrgRepo org(String name, Budget budget) {
        return new OrgRepo(name, "v1", "m", Optional.empty(), Map.of(), Map.of(), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), budget);
    }

    private static LlmRequest request(String who) {
        return LlmRequest.builder("m").system(who).addMessage(Message.of(Role.USER, TextBlock.of("hi"))).build();
    }

    @Test
    void aCallBeyondAnOrganizationsPlacesWaitsForItsOwnWhileAnothersGoesStraightIn() throws Exception {
        Held model = new Held();
        HostLimits limits = new HostLimits(Map.of(), new HostLimits.Limits(4, Budget.NONE),
                Map.of("acme", new HostLimits.Limits(1, Budget.NONE)));
        ModelAccounts accounts = new ModelAccounts(Optional.of(model), limits, UsageLedger.inMemory(),
                org -> Secrets.NONE, (p, k) -> model, () -> NOW, Duration.ofSeconds(10));
        LlmClient acme = accounts.client(org("acme", Budget.NONE), "helpdesk", ModelAccounts.Purpose.TURN);
        LlmClient globex = accounts.client(org("globex", Budget.NONE), "helpdesk", ModelAccounts.Purpose.TURN);

        CompletableFuture<LlmResponse> first = CompletableFuture.supplyAsync(() -> acme.generate(request("acme 1")));
        assertThat(model.hasEntered("acme 1", 5_000)).isTrue();
        CompletableFuture<LlmResponse> second = CompletableFuture.supplyAsync(() -> acme.generate(request("acme 2")));
        CompletableFuture<LlmResponse> other = CompletableFuture.supplyAsync(() -> globex.generate(request("globex")));

        assertThat(model.hasEntered("globex", 5_000)).as("another organization's call").isTrue();
        assertThat(model.hasEntered("acme 2", 300)).as("acme's second call, while its first runs").isFalse();
        assertThat(accounts.report(org("acme", Budget.NONE)).get("concurrentCalls"))
                .isEqualTo(Map.of("limit", 1, "running", 1));

        model.release.countDown();
        first.get(5, TimeUnit.SECONDS);
        assertThat(model.hasEntered("acme 2", 5_000)).isTrue();
        second.get(5, TimeUnit.SECONDS);
        other.get(5, TimeUnit.SECONDS);
        assertThat(accounts.ledger().spent("acme", NOW.atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                NOW.atZone(java.time.ZoneOffset.UTC).toLocalDate(), true)).isEqualTo(new Spend(2, 20, 20, 0));
    }

    @Test
    void aCallThatFindsNoPlaceInTimeFailsSayingWhy() throws Exception {
        Held model = new Held();
        HostLimits limits = new HostLimits(Map.of(), new HostLimits.Limits(1, Budget.NONE), Map.of());
        ModelAccounts accounts = new ModelAccounts(Optional.of(model), limits, UsageLedger.inMemory(),
                org -> Secrets.NONE, (p, k) -> model, () -> NOW, Duration.ofMillis(200));
        LlmClient acme = accounts.client(org("acme", Budget.NONE), "helpdesk", ModelAccounts.Purpose.TURN);
        CompletableFuture<LlmResponse> first = CompletableFuture.supplyAsync(() -> acme.generate(request("acme 1")));
        assertThat(model.hasEntered("acme 1", 5_000)).isTrue();

        assertThatThrownBy(() -> acme.generate(request("acme 2"))).isInstanceOf(LlmException.class)
                .hasMessage("acme already has 1 model calls running, the most the host runs for it at once, and none "
                        + "finished within 200ms.");
        model.release.countDown();
        first.get(5, TimeUnit.SECONDS);
    }

    @Test
    void deferredWorkIsCountedAgainstTheBudgetButNeverRefusedByIt() {
        Held model = new Held();
        model.release.countDown();
        ModelAccounts accounts = new ModelAccounts(Optional.of(model), HostLimits.none(), UsageLedger.inMemory(),
                org -> Secrets.NONE, (p, k) -> model, () -> NOW, Duration.ofSeconds(5));
        OrgRepo acme = org("acme", new Budget(20, 0, 0, 0));

        accounts.client(acme, "access-desk", ModelAccounts.Purpose.TURN).generate(request("a turn"));
        assertThatThrownBy(() -> accounts.client(acme, "access-desk", ModelAccounts.Purpose.TURN)
                .generate(request("another"))).isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("for today (20 tokens)");

        LlmResponse revoked = accounts.client(acme, "access-desk", ModelAccounts.Purpose.DEFERRED)
                .generate(request("revoke a grant that expired"));
        assertThat(revoked.message().text()).isEqualTo("done");
        assertThat(accounts.report(acme).get("today")).isEqualTo(Map.of("calls", 2L, "inputTokens", 20L,
                "outputTokens", 20L, "usd", 0.0));
    }
}
