package dev.agentkit.core.plan;

import java.util.List;
import java.util.Objects;

/**
 * What a run said it was going to do, in its own words, before doing it.
 *
 * <p>A <em>claim</em>, not policy. Nothing here can widen what a run may touch — that is a
 * {@code ToolGate}'s job, and the gate built from this one
 * ({@link Conformance#holdingTo(RunLedger)}) can only ever take away, because it is composed
 * with the coding-time policy rather than replacing it. Its use is as the yardstick
 * {@link Conformance#check} holds a trace against, and as the criterion that gate enforces.
 *
 * <p>This is the shape #310 named: a criterion supplied by the model at runtime and enforced
 * statically. What the framework fixes is that a declaration exists, that the first one is
 * the one that counts, and that it is compared; what the model fixes is its content.
 *
 * <h2>Two promises and a licence</h2>
 *
 * <p>{@link #subagents} and {@link #verifies} are <strong>promises</strong>: declaring them
 * and then not doing them is a broken promise, and {@link Conformance} reports it as one.
 *
 * <p>{@link #spawns} is a <strong>licence</strong> — "I may need to build a specialist" —
 * and the asymmetry is deliberate rather than an oversight. A promise can be broken by
 * omission; a licence cannot. Reporting "declared it might build a specialist and then did
 * not need to" would be a report that fires on correct behaviour, which is the whole defect
 * #316 was filed for, and adding a second one while closing the first is not a trade worth
 * making. What {@code spawns == false} buys is the other direction: a run that builds a
 * specialist after saying it would not has broken its word, and until this component existed
 * there was no way for it to say it would not.
 *
 * @param subagents the names it will delegate to; empty if it will answer directly
 * @param verifies  whether it will have its answer checked before returning it
 * @param spawns    whether it may build a specialist that does not exist yet (#316) — a
 *     licence, not a commitment
 * @param rationale one sentence on why this shape fits the question, for the reviewer
 */
public record DeclaredPlan(List<String> subagents, boolean verifies, boolean spawns,
                           String rationale) {

    public DeclaredPlan {
        subagents = List.copyOf(subagents);
        rationale = Objects.requireNonNull(rationale, "rationale");
    }

    /** Whether this plan named {@code subagent} as one it would use. */
    public boolean named(String subagent) {
        return subagents.contains(subagent);
    }
}
