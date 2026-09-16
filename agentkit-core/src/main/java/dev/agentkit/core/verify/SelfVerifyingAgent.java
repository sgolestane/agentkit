package dev.agentkit.core.verify;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.reliability.PendingApproval;
import dev.agentkit.core.util.OneLine;
import dev.agentkit.core.util.Quoted;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps an {@link Agent} with a verify-and-retry loop: after the agent produces a
 * result, a {@link Verifier} checks it; on failure a <em>fresh</em> agent is run
 * with the verifier's feedback appended to the goal, up to {@code maxAttempts}.
 *
 * <p>This is the reliability backbone for unsupervised runs: the agent does not
 * get to declare success unilaterally. If the final attempt still fails
 * verification, the result is returned with {@link StopReason#VERIFICATION_FAILED}
 * rather than {@code COMPLETED}, so callers can tell a verified success from an
 * unverified one. Steps and token usage are aggregated across attempts (the
 * verifier's own model calls are not counted).
 *
 * <p><strong>Use a fresh agent per attempt.</strong> The constructor takes a
 * {@link Supplier} so each retry gets a clean {@link Agent} — important because
 * some collaborators are per-run stateful (e.g. a {@code DisclosingToolRegistry}
 * accumulates revealed tools). The {@code (Agent, …)} convenience constructor
 * reuses one instance and is only appropriate when every collaborator is
 * stateless across runs.
 *
 * <p>Each retry re-runs the whole loop from a clean conversation rather than
 * continuing the prior one — a deliberate tradeoff favouring a clean context over
 * reusing intermediate work.
 */
public final class SelfVerifyingAgent {

    private static final Logger log = LoggerFactory.getLogger(SelfVerifyingAgent.class);

    private final Supplier<Agent> agentFactory;
    private final Verifier verifier;
    private final int maxAttempts;

    /** Preferred: a fresh {@link Agent} is built for each attempt. */
    public SelfVerifyingAgent(Supplier<Agent> agentFactory, Verifier verifier, int maxAttempts) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
    }

    /**
     * Convenience for a stateless {@link Agent} that is safe to reuse across
     * attempts. Prefer {@link #SelfVerifyingAgent(Supplier, Verifier, int)} when
     * any collaborator (tool registry, context strategy) carries per-run state.
     */
    public SelfVerifyingAgent(Agent agent, Verifier verifier, int maxAttempts) {
        this(() -> Objects.requireNonNull(agent, "agent"), verifier, maxAttempts);
    }

    /** Runs the agent toward {@code goal}, verifying (and retrying on failure). */
    public AgentResult run(Goal goal) {
        Objects.requireNonNull(goal, "goal");
        Goal current = goal;
        AgentResult last = null;
        int totalSteps = 0;
        TokenUsage totalUsage = TokenUsage.ZERO;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            last = agentFactory.get().run(current);
            totalSteps += last.steps();
            totalUsage = totalUsage.plus(last.usage());

            if (!last.isSuccess()) {
                // The agent itself stopped, failed or parked — there is no output to
                // verify. Rebuilt with the run's totals rather than returned as it stands:
                // see withTotals below for what the bare `return last` cost.
                return withTotals(last, totalSteps, totalUsage);
            }

            Verdict verdict = verifier.verify(goal, last.output());
            if (verdict.passed()) {
                log.debug("Verification passed on attempt {}", attempt);
                return AgentResult.completed(last.output(), totalSteps, totalUsage);
            }
            // OneLine, not Quoted. Feedback is model-written prose, often several
            // paragraphs; escaping every newline to six characters makes it one
            // unreadable ribbon. Only its *shape* is dangerous here, so collapsing
            // is the tool — escaping is for a name, where the exact characters are
            // what identify the thing. Deferred, because an argument is evaluated whether
            // or not the level is on and this text is model-written and unbounded.
            log.info("Verification failed on attempt {}/{}: {}",
                    attempt, maxAttempts, Quoted.lazily(() -> OneLine.of(verdict.feedback())));

            current = new Goal(goal.description()
                    + "\n\nA previous attempt was rejected during verification with this feedback:\n"
                    + Spotlight.wrap(Spotlight.Kind.ADVISORY, Source.of("verifier-feedback"), verdict.feedback())
                    + "\n\nProduce a corrected result that addresses it.",
                    goal.parameters());
        }

        // Exhausted attempts without passing verification.
        return AgentResult.stopped(StopReason.VERIFICATION_FAILED, last.output(), totalSteps, totalUsage);
    }

    /**
     * Rebuilds {@code result} with the whole run's step and token totals (#222).
     *
     * <p><strong>The measured defect.</strong> The early return above handed back the last
     * attempt's {@code AgentResult} unchanged, so a run that failed verification on attempt
     * 1 and then parked on attempt 2 reported attempt 2's numbers as if they were the run's.
     * Measured through a real {@link Agent} with a two-turn first attempt and a parking gate
     * on the second:
     *
     * <pre>
     * attempts run : 2
     * reported     : stopReason=AWAITING_APPROVAL steps=1 usage=input 1 / output 1
     * actually ran :                              steps=3 usage=input 81 / output 21
     * </pre>
     *
     * <p>Every other exit from {@link #run} was already aggregating — the verified-success
     * path and the exhausted-attempts path both build from {@code totalSteps} and
     * {@code totalUsage}, and the class javadoc has always promised "steps and token usage
     * are aggregated across attempts". One path out of three disagreed with the other two
     * and with the documentation.
     *
     * <p><strong>Why it is not tidiness.</strong> A caller capping a run by steps or tokens
     * decides against the returned number, and the under-report grows with the number of
     * attempts — the direction that makes a runaway look cheap. {@code TokenUsage} is also
     * what a deployment bills and rate-limits against.
     *
     * <p><strong>Rebuilt by parts, not by case</strong>, which is
     * {@link AgentResult#withTotals}'s own reason for existing and the reason this does not
     * re-derive the outcome. Enumerating outcomes here — {@code failed} for one,
     * {@code stopped} for the rest — is what crashed {@code ReflectiveAgent} and
     * {@code PlanningAgent} when {@link StopReason#AWAITING_APPROVAL} was added (#101), and
     * a park is exactly the case this path now carries: {@code AgentResult.stopped} refuses
     * to build one. Keeping {@code stopReason}, {@code output}, {@code error} and
     * {@code awaiting} untouched also means the {@link PendingApproval} a gate raised
     * travels on unaltered, which is what #159 made a first-class outcome at this level.
     *
     * <p>The totals are the run's up to and including this attempt, because the loop adds
     * each attempt's figures before testing it. A resumed run's own totals will have to
     * compose with these if in-process resume lands (#157); nothing here can do that
     * composition, and nothing here silently discards the half it holds any more.
     *
     * <p>Named rather than inlined for the same reason {@code ReflectiveAgent} names it: the
     * two agents wrap a loop and aggregate, and a reader comparing them should see one shape
     * twice rather than two spellings of it.
     */
    private static AgentResult withTotals(AgentResult result, int totalSteps,
                                          TokenUsage totalUsage) {
        return result.withTotals(totalSteps, totalUsage);
    }
}
