package dev.agentkit.core.reliability;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.StreamHandler;
import dev.agentkit.core.llm.TokenUsage;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * An {@link LlmClient} decorator that enforces a {@link TokenBudget} across a run,
 * so an unsupervised agent cannot burn unbounded tokens (or dollars).
 *
 * <p>It accumulates the {@link TokenUsage} of every response and, before each call,
 * refuses to proceed once the budget is exhausted by throwing a
 * {@link BudgetExceededException}. The check is a pre-flight guard on the spend so
 * far — a turn's cost is unknown until it returns — so the turn that first reaches
 * the cap still completes and its answer is delivered; the <em>next</em> turn is the
 * one that is refused. The agent loop turns that exception into a
 * {@code BUDGET_EXHAUSTED} stop.
 *
 * <p>Place this decorator <em>outside</em> a {@link RetryingLlmClient} (budget wraps
 * retry) so retries count against the budget and a budget stop is never itself
 * retried. The running total is thread-safe, but the check-then-call is not one
 * atomic step; under concurrent callers the cap is approximate — fine for a single
 * agent loop, which calls serially.
 *
 * <p>The tally is instance state that spans every call, so one instance tracks
 * <em>one run</em>: build a fresh {@code BudgetLlmClient} per run, or call
 * {@link #reset()} between runs, otherwise a reused instance carries its spend
 * forward and a later run can start already exhausted. For the same reason this
 * belongs in the in-process agent loop and <em>not</em> inside a Temporal activity,
 * where the instance would be shared across every workflow the worker serves and a
 * thrown {@link BudgetExceededException} would be retried by Temporal. A durable run
 * is capped instead by declaring the budget on its workflow input
 * ({@code DurableAgentRun.withBudget(...)}), which the workflow enforces per run;
 * {@code TemporalAgent.register} rejects this client outright.
 */
public final class BudgetLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final TokenBudget budget;
    private final AtomicReference<TokenUsage> spent = new AtomicReference<>(TokenUsage.ZERO);

    public BudgetLlmClient(LlmClient delegate, TokenBudget budget) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        return guarded(() -> delegate.generate(request));
    }

    @Override
    public LlmResponse generate(LlmRequest request, StreamHandler handler) {
        // Enforce the budget on the streaming path too, so streaming does not bypass
        // the cap, and forward to the delegate's streaming call so real deltas survive.
        return guarded(() -> delegate.generate(request, handler));
    }

    private LlmResponse guarded(Supplier<LlmResponse> call) {
        TokenUsage current = spent.get();
        if (budget.isExhausted(current)) {
            throw new BudgetExceededException(budget, current);
        }
        LlmResponse response = call.get();
        spent.updateAndGet(u -> u.plus(response.usage()));
        return response;
    }

    /** The cumulative usage recorded so far. */
    public TokenUsage spent() {
        return spent.get();
    }

    /** Clears the running total so this instance can track a fresh run. */
    public void reset() {
        spent.set(TokenUsage.ZERO);
    }
}
