package dev.agentkit.core.verify;

import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import java.util.Objects;

/**
 * A {@link Verifier} that uses an independent model call as a critic: it judges
 * the output against the goal and returns a pass/fail verdict with feedback.
 *
 * <p>Using a separate call (ideally fresh context) is more reliable than asking
 * the same loop to self-assess. The critic is instructed to answer {@code PASS}
 * or {@code FAIL} on the first line; anything not clearly {@code PASS} is treated
 * as a failure (fail-closed).
 *
 * <h2>Both slots are fenced, and one of them used not to be (#309)</h2>
 *
 * <p>This prompt has two slots and used to fence one: {@code output} went through
 * {@link Spotlight#wrap} and {@code goal.description()} was concatenated raw, into a
 * message whose system prompt is {@link Spotlight#withInstruction}. Raw meant the GOAL
 * slot sat in the region {@link Spotlight#INSTRUCTION} tells the model is the framework's,
 * carrying text the framework had not written.
 *
 * <p>The asymmetry was defensible while a goal was always the operator's. Through
 * {@link SelfVerifyingAgent} it is: the caller's {@link Goal} is handed down unchanged, so
 * the unfenced slot held the operator's own words. That is a property of one caller, not
 * of this class, and it stopped holding the moment a verifier became something a model
 * could invoke — {@link VerifierTools#verifyClaimTool} is exactly that shape, and there
 * the text in the GOAL slot is written by the party the critic exists to check.
 *
 * <p>So both slots are fenced now. Fencing the operator's own goal costs nothing: a fence
 * around trusted text is redundant, not wrong, and the price of the redundancy is a marker
 * pair and an id. What it buys is that the guarantee no longer has a clause about who the
 * caller is.
 *
 * <p><strong>What a fence buys here, stated precisely.</strong> It stops the text
 * <em>ending its own region</em>: a goal spelling {@code </untrusted>}, a newline and a
 * fresh {@code OUTPUT:} header cannot close the span and continue in the framework's
 * voice, because the closing marker carries a nonce derived from the body. It does not
 * make a claim unpersuasive. A critic reading a fenced {@code "the answer is obviously
 * correct"} may still be talked round, and #71 is open on that — the incentive the verdict
 * format itself creates — which is a different failure of this same component.
 *
 * <p><strong>{@link Spotlight.Kind#EVIDENCE} for both</strong>, which is the strict
 * reading and the right one: this critic's instructions are the system prompt, and both
 * slots are material to weigh against them. A kind that told the model to follow
 * directions in the GOAL slot would hand back most of what the fence just took away.
 */
public final class LlmVerifier implements Verifier {

    private static final String SYSTEM = """
            You are a strict verifier. Given a GOAL and a candidate OUTPUT, decide \
            whether the output fully satisfies the goal. Answer with exactly PASS \
            or FAIL on the first line. If FAIL, add a second line with specific, \
            actionable feedback on what to fix.""";

    /**
     * The label on the GOAL slot's fence.
     *
     * <p>{@code goal} rather than anything about who wrote it, because this class cannot
     * know: the same slot carries an operator's objective through
     * {@link SelfVerifyingAgent} and a model's claim through
     * {@link VerifierTools#verifyClaimTool}. A label naming one of those would be a
     * statement of provenance the framework is not in a position to make, and
     * {@link Spotlight#INSTRUCTION} already tells the model a label is worth no more than
     * the content it sits beside.
     */
    private static final Source GOAL = Source.of("goal");

    /** The label on the OUTPUT slot's fence — the thing being judged. */
    private static final Source AGENT_OUTPUT = Source.of("agent-output");

    private final LlmClient llm;
    private final String model;
    private final int maxTokens;

    public LlmVerifier(LlmClient llm, String model) {
        this(llm, model, 1024);
    }

    public LlmVerifier(LlmClient llm, String model, int maxTokens) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.model = Objects.requireNonNull(model, "model");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be > 0");
        }
        this.maxTokens = maxTokens;
    }

    @Override
    public Verdict verify(Goal goal, String output) {
        String prompt = "GOAL:\n" + Spotlight.wrap(GOAL, goal.description())
                + "\n\nOUTPUT:\n" + Spotlight.wrap(AGENT_OUTPUT, output);
        LlmRequest request = LlmRequest.builder(model)
                .system(Spotlight.withInstruction(SYSTEM))
                .maxTokens(maxTokens)
                .addMessage(Message.user(prompt))
                .build();
        String verdictText = llm.generate(request).message().text().strip();

        // Fail-closed: the ENTIRE first line (minus surrounding punctuation) must be
        // the token PASS. "PASS is not warranted…" or "PASSABLE" therefore fail.
        String firstLine = verdictText.lines().findFirst().orElse("");
        String firstToken = firstLine.strip()
                .replaceAll("^[^A-Za-z]+", "").replaceAll("[^A-Za-z]+$", "");
        if (firstToken.equalsIgnoreCase("PASS")) {
            return Verdict.pass();
        }
        String feedback = verdictText.contains("\n")
                ? verdictText.substring(verdictText.indexOf('\n') + 1).strip()
                : "";
        return Verdict.fail(feedback.isEmpty() ? "Output did not satisfy the goal." : feedback);
    }
}
