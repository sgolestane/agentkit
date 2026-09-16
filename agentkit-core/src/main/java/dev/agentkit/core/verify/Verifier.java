package dev.agentkit.core.verify;

import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.prompt.Spotlight;

/**
 * Checks whether an agent's output actually satisfies its goal — the core of
 * "don't trust the first answer" for unsupervised agents. A verifier can be a
 * rule check, a schema validation, or an independent model acting as a critic.
 *
 * <p><strong>Scope.</strong> A verifier judges the agent's final output
 * <em>string</em> against the goal. It does not observe the tools the agent
 * called or the side effects they had; verifying that an action actually took
 * effect in the world (a row was written, an email was sent) is the caller's
 * responsibility and typically belongs in a tool that reads back the state. See
 * {@link Verifiers} for non-model verifiers and {@link LlmVerifier} for a
 * model-based critic. {@link VerifierTools#verifyClaimTool} makes any of them
 * reachable by the model itself.
 *
 * <p><strong>An implementation that builds a prompt fences both of its inputs.</strong>
 * Neither argument is fenced on the way in — {@code VerifierTools} hands this interface a
 * {@code Goal} it built from a model's own {@code claim} argument, and
 * {@code SelfVerifyingAgent} hands it whatever the caller's goal says. A verifier that
 * composes them into a prompt is the point at which untrusted text enters one, which is
 * where {@link Spotlight} says the fence belongs. {@link LlmVerifier} does this for both
 * slots; a critic written elsewhere is its author's to get right.
 *
 * <h2>Why this still takes a {@code Goal} and a {@code String} (#309)</h2>
 *
 * <p>#309 asked whether these should be two labelled inputs instead, on the grounds that
 * {@code VerifierTools} uses {@code Goal} as a carrier for arbitrary model-written text.
 * The observation is right and the change is not, for three reasons.
 *
 * <p><strong>It would not carry the security property.</strong> A
 * {@code verify(Claim, Evidence)} pair is still two strings, and a model-written claim in
 * the first slot is exactly as dangerous as it was — the danger is not which type the text
 * arrives in but whether the party that composes it into a prompt fences it. That party is
 * the verifier, the fence is now on both of {@code LlmVerifier}'s slots, and renaming the
 * parameters would have closed nothing. Reaching for the type system here would have felt
 * like the repair while leaving the defect where it was.
 *
 * <p><strong>The {@code Goal} is not only a description.</strong> It carries
 * {@link Goal#parameters()}, and {@link SelfVerifyingAgent} passes the caller's own
 * instance through unchanged, so the criterion the critic is given is the same object the
 * agent was run on rather than a copy of its text. A bare label would force that call site
 * to flatten a goal into a string at the one place that currently does not have to, and a
 * verifier wanting the parameters could no longer see them.
 *
 * <p><strong>It is a functional interface with implementations everywhere.</strong>
 * {@link #ALWAYS_PASS}, every factory in {@link Verifiers}, and lambdas across the
 * examples and tests would all change shape. That is a price worth paying for a repair and
 * not for a rename.
 *
 * <p><strong>What it costs, honestly.</strong> The {@code Goal} that
 * {@link VerifierTools#verifyClaimTool} constructs was never anybody's objective: it has
 * no parameters, no agent ran toward it, and calling it a goal narrows what the word means
 * elsewhere in this framework. A reader following {@code Goal} from
 * {@link dev.agentkit.core.agent.Agent} to here meets a second sense of it. That is the
 * residual, and the reason it is written down rather than fixed is that fixing it by
 * renaming the parameter buys the reader a clearer word and buys the run nothing.
 */
@FunctionalInterface
public interface Verifier {

    /** A verifier that accepts everything (no verification). */
    Verifier ALWAYS_PASS = (goal, output) -> Verdict.pass();

    /**
     * Verifies {@code output} against {@code goal}.
     *
     * <p>Treat both as untrusted. See the class javadoc: neither is fenced on the way in,
     * and an implementation that puts either into a prompt is the one that has to.
     */
    Verdict verify(Goal goal, String output);
}
