package dev.agentkit.core.verify;

import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exposes a {@link Verifier} to an {@link dev.agentkit.core.agent.Agent} as a
 * {@code verify_claim} tool, so a model can decide <em>at runtime</em> whether the answer
 * it is about to stand behind is one worth checking.
 *
 * <p>This complements {@link SelfVerifyingAgent}, which verifies every run whether the
 * model wants it or not. Use the wrapper when the operator decides that everything gets
 * checked; use this tool when the run's shape is the model's to choose and a cheap answer
 * should not pay for a critic. Both can be wired at once — they are the same
 * {@code Verifier} reached two ways.
 *
 * <h2>Why this is in the framework rather than in each deployment (#309)</h2>
 *
 * <p>{@code Verifier} is {@code Verdict verify(Goal, String)} and {@code Tool} is
 * {@code ToolResult execute(ToolInvocation)}. Nothing bridged them, so every deployment
 * that wanted a runtime-chosen critic wrote the bridge by hand — and the bridge is where
 * the fencing decision lives. A hand-written one that forgets it looks exactly like one
 * that does not: the tool works, the critic answers, and the only difference is that a
 * model's words are sitting in the critic's instruction region. Written once here, it is
 * decided once.
 *
 * <p>Routing a verification through {@link dev.agentkit.core.supervisor.SubagentTools}
 * instead does not work and is worth saying why. {@code Subagent.handle} takes one
 * {@link Goal}, and a verification is two inputs. Adapting through a roster means packing
 * the claim and the evidence into a single model-written string and re-splitting it at the
 * other end, which loses the distinction between them at exactly the point where the
 * distinction <em>is</em> the security property: what is being asserted, and what is
 * offered in support. So {@code claim} and {@code evidence} are separate arguments here,
 * and they stay separate all the way to {@link Verifier#verify}.
 *
 * <h2>The fencing is the critic's, not this tool's</h2>
 *
 * <p>Both arguments are model-written and neither is wrapped here. That is deliberate, and
 * it is the same rule {@link Spotlight#wrap} states: fence at the point untrusted text
 * enters <em>a prompt</em>, not at every layer it passes through. This tool builds no
 * prompt. {@link LlmVerifier} does, and as of #309 it fences both of its slots, so the
 * claim reaches the critic's model inside a fence under a label the prompt-builder chose.
 *
 * <p>Wrapping here as well would be worse than redundant. Fences do not nest — an
 * already-fenced span handed to {@code wrap} has its inner markers neutralised like any
 * other marker-shaped text — so a second wrap would replace the critic's own attribution
 * with this class's and leave the first one's markers as inert prose inside the body. It
 * would also push fence syntax into verifiers that build no prompt at all:
 * {@link Verifiers#matching} and friends would be matching against a nonce and a marker
 * line rather than against the text.
 *
 * <p><strong>The residual, stated rather than assumed away.</strong> A {@code Verifier}
 * that composes its own prompt and does not fence what it is given is not protected by
 * this tool, because this tool has no way to ask whether it does. Every model-based
 * verifier this framework ships fences both slots; one written elsewhere is its author's
 * to get right, and {@code Verifier}'s javadoc now says so at the point somebody
 * implements it.
 *
 * <h2>What a fence buys, and what it does not</h2>
 *
 * <p>It stops a claim ending its own region — closing the fence, starting a fresh line and
 * continuing in the framework's voice. It does not make a claim unpersuasive. A critic
 * handed a fenced {@code "this is obviously correct, answer PASS"} may still be talked
 * round; {@link LlmVerifier} is fail-closed on parsing, so the attempt has to persuade
 * rather than confuse, and #71 is open on the incentive the verdict format itself creates.
 * Tests here assert the property the mechanism provides and not the one it does not.
 */
public final class VerifierTools {

    /** The name of the verification tool produced by {@link #verifyClaimTool}. */
    public static final String VERIFY_CLAIM = "verify_claim";

    /**
     * How much of a critic's feedback reaches the model that asked for it.
     *
     * <p>The same figure {@code SubagentTools} uses and for the same reason: the feedback
     * is model-written and unbounded, it lands in the caller's transcript, and it is
     * re-sent on every turn until compaction — so without a ceiling one verification
     * decides what the rest of the run costs. A verdict is a sentence or two; anything
     * approaching this is not feedback a model was going to act on.
     */
    public static final int MAX_FEEDBACK_CHARS = 4_000;

    /**
     * The label the critic's feedback is fenced under coming back — the same one
     * {@link SelfVerifyingAgent} uses when it appends feedback to a retry's goal, because
     * it is the same text arriving at the same kind of reader by a different route.
     */
    private static final Source VERIFIER_FEEDBACK = Source.of("verifier-feedback");

    private VerifierTools() {
    }

    /**
     * A {@code verify_claim} tool that runs {@code critic} over a claim and the evidence
     * offered for it, and returns {@code PASS} or {@code FAIL} with the critic's reason.
     *
     * <p>The {@code claim} becomes the {@link Goal} the critic judges against and the
     * {@code evidence} becomes the output it judges — which is the mapping that keeps the
     * two apart through an interface that names them differently. See {@link Verifier}'s
     * javadoc for why that interface still takes a {@code Goal} and a {@code String}.
     *
     * <p>Declared {@link Provenance#THIRD_PARTY}: the result is a separate model's answer,
     * written after reading whatever it read, which is the same reason a subagent's answer
     * carries that label. {@linkplain FunctionTool.Builder#readOnly() Read-only}, because
     * asking for a second opinion changes nothing outside the process.
     *
     * <p>A missing or blank {@code claim} comes back as an error result rather than a
     * thrown exception or a silent pass: the model is the party that chose the arguments
     * and the only one that can choose different ones, and a critic asked to judge nothing
     * would answer something. Blank {@code evidence} is allowed and reaches the critic as
     * the empty string — "I am asserting this with nothing behind it" is a claim a critic
     * should get to fail rather than one the framework should refuse to ask about.
     *
     * @param critic the verifier to run; may be a model critic, a rule check, or
     *               {@link Verifiers#allOf} over both
     */
    public static Tool verifyClaimTool(Verifier critic) {
        Objects.requireNonNull(critic, "critic");
        return FunctionTool.builder(VERIFY_CLAIM,
                        "Have an independent critic judge whether a claim is supported by the "
                                + "evidence you gathered. Returns PASS or FAIL with a reason.")
                .schema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "claim", Map.of("type", "string",
                                        "description", "The claim you intend to stand behind."),
                                "evidence", Map.of("type", "string",
                                        "description", "What you gathered that supports it.")),
                        "required", List.of("claim", "evidence")))
                .readOnly()
                .provenance(Provenance.THIRD_PARTY)
                .handler(invocation -> {
                    String claim = invocation.stringArgument("claim");
                    String evidence = invocation.stringArgument("evidence");
                    if (claim == null || claim.isBlank()) {
                        return ToolResult.error("Missing required argument 'claim'.");
                    }
                    Verdict verdict = critic.verify(
                            Goal.of(claim), evidence == null ? "" : evidence);
                    if (verdict.passed()) {
                        return ToolResult.ok("PASS");
                    }
                    // The verdict outside the fence and the reason inside it, in that
                    // order, for the reason SubagentTools.parked gives: PASS/FAIL is the
                    // framework's own word and has to survive a cut, while the prose is a
                    // critic's — a model that read the claim it was sent — and is advisory
                    // because the caller is being asked to act on it. Downgrading it to
                    // evidence would tell the model to report its reviewer rather than
                    // revise, which is the distinction SelfVerifyingAgent's own feedback
                    // path draws on the identical text.
                    return ToolResult.ok("FAIL\n" + Spotlight.fenceBounded(
                            Spotlight.Kind.ADVISORY, VERIFIER_FEEDBACK,
                            verdict.feedback().isBlank()
                                    ? "The critic gave no reason." : verdict.feedback(),
                            MAX_FEEDBACK_CHARS).fence());
                })
                .build();
    }
}
