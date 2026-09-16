package dev.agentkit.core.reliability;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.TokenUsage;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tallies the model calls made through the clients it wraps, so a caller can see what
 * a run actually cost.
 *
 * <p><strong>Why this is needed.</strong> {@code AgentResult.usage()} reports only the
 * agent loop's own turns. The framework issues model calls from several other places —
 * context compaction, verification, reflection, planning, critics, synthesis, and
 * LLM-judged evals — and each is handed its own {@link LlmClient}, so the loop never
 * sees their spend. In a compacting agent those calls easily dominate: summarising a
 * long transcript costs far more than the turn that triggered it.
 *
 * <p><strong>It counts only what you wrap.</strong> This is a measuring instrument, not
 * an interceptor: a client you forget to wrap is invisible, and metering <em>only</em>
 * the client you hand to the {@code Agent} yields exactly the figure
 * {@code AgentResult.usage()} already gives you. Wrap every client that will make a
 * call — the compactor's, the verifier's, the reflector's, the planner's, a critic's, a
 * synthesizer's, an eval judge's — or the total will read as authoritative while being
 * just as short as the number it replaces.
 *
 * <pre>{@code
 * UsageMeter meter = new UsageMeter();
 * LlmClient agentLlm   = meter.wrap("agent", llm);
 * LlmClient compactLlm = meter.wrap("compaction", cheapLlm);   // must be metered too
 *
 * Agent agent = Agent.builder(agentLlm, tools, config)
 *         .contextStrategy(ContextStrategies.compacting(
 *                 SummarizingCompactor.builder(compactLlm, "m").build()))
 *         .build();
 * agent.run(goal);
 *
 * meter.total();                // every call through a metered client
 * meter.forRole("compaction");  // what compaction alone cost
 * System.out.println(meter.summary());
 * }</pre>
 *
 * <p>Roles are free-form: use one per component whose cost you want separated, or take
 * {@link #wrap(LlmClient)} when the split does not matter. Several clients may share a
 * role and their tallies add up — so for a per-role figure to convert cleanly to
 * dollars with {@link ModelPricing}, keep one model per role; a role spanning two
 * models yields a token count that no single price applies to.
 *
 * <p><strong>Placement.</strong> Wrap the outermost client you intend to measure. Put
 * the meter outside a {@link RetryingLlmClient} to count every attempt a retry makes,
 * or inside it to count only the attempt that succeeded. A metered client still looks
 * like any other {@code LlmClient} to code that inspects it, so wrapping a
 * {@link BudgetLlmClient} hides it from callers that check for one — see
 * {@link MeteringLlmClient#delegate()}.
 *
 * <p>The tally is instance state spanning every call, so one meter measures <em>one
 * run</em>: build a fresh meter per run, or {@link #reset()} between runs. For the same
 * reason a metered client should not back a Temporal worker, where one instance serves
 * every workflow and the totals would mix unrelated runs — a durable run's own usage is
 * reported by {@code AgentRunResult.usage()}.
 *
 * <p>Recording is thread-safe, so one meter can span concurrent subagents. Each read
 * ({@link #total()}, {@link #summary()}, …) takes a single snapshot, so its parts agree
 * with each other even while other threads record; successive reads may of course
 * differ.
 */
public final class UsageMeter {

    /** The role used by {@link #wrap(LlmClient)} when none is given. */
    public static final String DEFAULT_ROLE = "unattributed";

    private final ConcurrentMap<String, Tally> tallies = new ConcurrentHashMap<>();

    /**
     * What one role spent.
     *
     * @param calls how many model calls returned a response
     * @param usage their cumulative token usage
     */
    public record Tally(long calls, TokenUsage usage) {

        /** The empty tally. */
        public static final Tally ZERO = new Tally(0, TokenUsage.ZERO);

        public Tally {
            Objects.requireNonNull(usage, "usage");
            if (calls < 0) {
                throw new IllegalArgumentException("calls must be >= 0");
            }
        }

        Tally plus(Tally other) {
            return new Tally(calls + other.calls, usage.plus(other.usage));
        }
    }

    /** Wraps {@code delegate} so its calls are tallied under {@link #DEFAULT_ROLE}. */
    public MeteringLlmClient wrap(LlmClient delegate) {
        return wrap(DEFAULT_ROLE, delegate);
    }

    /**
     * Wraps {@code delegate} so its calls are tallied under {@code role}. The same role
     * may be used for several clients; their tallies add up.
     *
     * @throws IllegalArgumentException if {@code role} is blank
     */
    public MeteringLlmClient wrap(String role, LlmClient delegate) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(delegate, "delegate");
        if (role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        return new MeteringLlmClient(this, role, delegate);
    }

    /** Records one call that returned a response. */
    void record(String role, TokenUsage usage) {
        // merge applies the remap atomically under the bin lock, and Tally is immutable,
        // so concurrent recorders cannot lose an update.
        tallies.merge(role, new Tally(1, usage), Tally::plus);
    }

    /** What {@code role} spent, or {@link Tally#ZERO} if it made no metered calls. */
    public Tally forRole(String role) {
        return tallies.getOrDefault(Objects.requireNonNull(role, "role"), Tally.ZERO);
    }

    /** An immutable snapshot of every role's tally, ordered by role name. */
    public SortedMap<String, Tally> byRole() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(tallies));
    }

    /** Calls and tokens across every role, from a single consistent snapshot. */
    public Tally totalTally() {
        return fold(byRole());
    }

    /** The cumulative usage of every metered call, across all roles. */
    public TokenUsage total() {
        return totalTally().usage();
    }

    /** How many metered calls returned a response, across all roles. */
    public long calls() {
        return totalTally().calls();
    }

    /**
     * Clears every tally so this meter can measure a fresh run. Intended for use
     * between runs: clearing is not atomic against calls still in flight, so a
     * concurrent recording may land on either side of it.
     */
    public void reset() {
        tallies.clear();
    }

    /** A one-line-per-role breakdown, plus a total — for logging at the end of a run. */
    public String summary() {
        // One snapshot for both the per-role lines and the total, so a report rendered
        // while other threads record cannot contradict itself.
        SortedMap<String, Tally> snapshot = byRole();
        StringBuilder sb = new StringBuilder("Usage by role:");
        snapshot.forEach((role, tally) -> sb.append("\n  ").append(role)
                .append(": ").append(tally.calls()).append(" call(s), ")
                .append(tally.usage().totalTokens()).append(" tokens (")
                .append(tally.usage().inputTokens()).append(" in, ")
                .append(tally.usage().outputTokens()).append(" out)"));
        Tally total = fold(snapshot);
        return sb.append("\n  TOTAL: ").append(total.calls()).append(" call(s), ")
                .append(total.usage().totalTokens()).append(" tokens").toString();
    }

    private static Tally fold(Map<String, Tally> snapshot) {
        Tally sum = Tally.ZERO;
        for (Tally tally : snapshot.values()) {
            sum = sum.plus(tally);
        }
        return sum;
    }
}
