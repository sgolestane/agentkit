package dev.agentkit.temporal;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.DelegatingLlmClient;
import dev.agentkit.core.reliability.BudgetLlmClient;
import java.util.Objects;

/**
 * Worker-side {@link LlmActivities} implementation that delegates to a core
 * {@link LlmClient}. It rebuilds an {@link LlmRequest} from the serialized
 * {@link LlmCallSpec} and maps the response into a converter-friendly
 * {@link LlmTurn}.
 *
 * <p>This runs outside the workflow sandbox, so a real network call to a model
 * provider is legitimate here. A thrown {@link RuntimeException} (e.g. a rate
 * limit) propagates to Temporal, which retries the activity per its options.
 */
public final class LlmActivitiesImpl implements LlmActivities {

    private final LlmClient llm;

    /**
     * @throws IllegalArgumentException if {@code llm} is a {@code BudgetLlmClient}. Its
     *     tally is instance state, and one activity instance serves every workflow the
     *     worker runs, so one run's spend would exhaust later runs — and Temporal would
     *     retry the resulting budget stop and report it as {@code ERROR}. Cap a durable
     *     run with {@code DurableAgentRun.withBudget(...)} instead. The check lives here
     *     rather than only in {@code TemporalAgent.register} so it also covers registering
     *     this activity directly. It is best-effort: a budget client nested inside a
     *     decorator that does not declare itself a {@link DelegatingLlmClient} cannot be
     *     detected, and the same caution applies to any client of your own that
     *     accumulates per-run state.
     */
    public LlmActivitiesImpl(LlmClient llm) {
        Objects.requireNonNull(llm, "llm");
        // Searched through the whole decorator chain rather than unwrapped to the
        // bottom: a metered, traced or retrying client is still an ordinary LlmClient,
        // and a budget client sitting between two of them is in the chain but is not
        // what unwrapping returns.
        if (DelegatingLlmClient.findInChain(llm, BudgetLlmClient.class).isPresent()) {
            throw new IllegalArgumentException(
                    "A BudgetLlmClient must not back a durable worker: its tally is instance state "
                            + "shared by every workflow this worker serves, so one run's spend would "
                            + "exhaust later runs (and Temporal would retry the budget stop, reporting "
                            + "ERROR instead of BUDGET_EXHAUSTED). Register the undecorated client and "
                            + "cap the run with DurableAgentRun.withBudget(TokenBudget.ofTotalTokens(...)).");
        }
        this.llm = llm;
    }

    /**
     * One model call, with the activity thread handed back clean (#136).
     *
     * <p>An {@code LlmClient} is user-supplied code on a pooled thread it does not own, the
     * same as a {@code Tool}, and this worker runs both on the same task executor. It is not
     * a hypothetical either: {@code RetryingLlmClient} and {@code JdkHttpTransport} — both
     * first-party — restore the interrupt flag when their sleep or send is interrupted, which
     * is correct of them and leaves the flag set here. See {@link ActivityThread}.
     */
    @Override
    public LlmTurn generate(LlmCallSpec spec) {
        boolean inherited = ActivityThread.arrivesInterrupted();
        try {
            // The same rule as the tool activity, in the same class, for the reason #136
            // established: two spellings is how one of them comes to be missing. A model
            // call on an interrupted thread is the clearest case of the harm — every
            // first-party transport in this repository sends over blocking I/O, which
            // fails at once on a dirty thread.
            ActivityThread.refuseToStartIfArrivedInterrupted("Model client for", spec.model(),
                    inherited);
            return called(spec);
        } finally {
            ActivityThread.clearBeforeReturning("Model client for", spec.model(), inherited);
        }
    }

    private LlmTurn called(LlmCallSpec spec) {
        LlmRequest.Builder builder = LlmRequest.builder(spec.model())
                .messages(spec.conversation())
                .tools(spec.tools())
                .maxTokens(spec.maxTokens());
        if (spec.system() != null) {
            builder.system(spec.system());
        }
        spec.options().forEach(builder::option);

        LlmResponse response = llm.generate(builder.build());
        return new LlmTurn(response.message(), response.stopReason(), response.usage());
    }
}
