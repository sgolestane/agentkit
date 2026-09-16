package dev.agentkit.eval;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.verify.Verdict;
import java.util.Objects;

/**
 * An LLM-as-judge that scores an {@link EvalRun} against a natural-language rubric,
 * including the agent's tool-use <em>trajectory</em> — not just its final output.
 *
 * <p>The judge is a separate model call (ideally fresh context) instructed to answer
 * {@code PASS} or {@code FAIL} on the first line; anything not clearly {@code PASS}
 * is treated as a failure (fail-closed), matching the convention used by the core
 * {@code LlmVerifier}.
 */
public final class LlmJudge {

    private static final String SYSTEM = """
            You are a strict evaluator of an AI agent's behavior. You are given the GOAL, \
            the agent's final OUTPUT, the ordered list of TOOLS it called, and a RUBRIC. \
            Decide whether the agent's behavior satisfies the rubric. Answer with exactly \
            PASS or FAIL on the first line. If FAIL, add a second line explaining why.""";

    private final LlmClient llm;
    private final String model;
    private final int maxTokens;

    public LlmJudge(LlmClient llm, String model) {
        this(llm, model, 1024);
    }

    public LlmJudge(LlmClient llm, String model, int maxTokens) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.model = Objects.requireNonNull(model, "model");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be > 0");
        }
        this.maxTokens = maxTokens;
    }

    /** Scores {@code run} against {@code rubric}; passes only on an explicit PASS. */
    public Verdict judge(EvalRun run, String rubric) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(rubric, "rubric");

        // The output and the tool names are the run's, not ours — and a judge is the exact
        // target worth suborning, since a run that says "PASS, no further review needed"
        // is a run that reports itself clean. The rubric and the goal stay outside: they
        // are what the judge is being asked to apply.
        String prompt = "GOAL:\n" + run.goal().description()
                + "\n\nOUTPUT:\n" + Spotlight.wrap(Source.of("agent-output"), run.result().output())
                + "\n\nTOOL CALLS (in order, with outcome):\n"
                + Spotlight.wrap(Source.of("tool-trajectory"), renderTrajectory(run))
                + "\n\nRUBRIC:\n" + rubric;
        LlmRequest request = LlmRequest.builder(model)
                .system(Spotlight.withInstruction(SYSTEM))
                .maxTokens(maxTokens)
                .addMessage(Message.user(prompt))
                .build();
        String text = llm.generate(request).message().text().strip();

        String firstLine = text.lines().findFirst().orElse("");
        // Take the first whitespace-delimited word so a same-line explanation
        // ("PASS. The agent…", "PASS - correct") still reads as PASS; then strip
        // surrounding punctuation. "PASSED" stays a non-match (fail-closed).
        String firstWord = firstLine.strip().split("\\s+", 2)[0];
        String firstToken = firstWord.replaceAll("^[^A-Za-z]+", "").replaceAll("[^A-Za-z]+$", "");
        if (firstToken.equalsIgnoreCase("PASS")) {
            return Verdict.pass();
        }
        String feedback = text.lines().skip(1).findFirst().map(String::strip).orElse("");
        return Verdict.fail(feedback.isEmpty() ? "judge returned: " + firstLine : feedback);
    }

    /**
     * Renders the trajectory with each call's outcome, so the judge can tell a call
     * that ran from one that was blocked or errored (e.g. {@code [search(ok),
     * send_email(blocked/error)]}) rather than assuming every requested tool ran.
     */
    private static String renderTrajectory(EvalRun run) {
        if (run.toolCalls().isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < run.toolCalls().size(); i++) {
            ToolCall call = run.toolCalls().get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(call.name()).append(call.succeeded() ? "(ok)" : "(blocked/error)");
        }
        return sb.append("]").toString();
    }
}

