package dev.agentkit.eval;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Runs a dataset of {@link EvalCase}s against an agent and scores each with its
 * checks, producing an {@link EvalReport}.
 *
 * <p>The agent is supplied as a factory that receives a trajectory-capturing
 * {@link AgentObserver}. The harness needs to observe the tools each run invokes
 * (the final {@link AgentResult} does not record them), so <strong>the factory MUST
 * attach the given observer</strong> via {@code .observer(obs)} — otherwise the
 * trajectory is empty and every tool-use check silently mis-scores. A fresh agent is
 * built per case, so state never leaks between cases:
 *
 * <pre>{@code
 * EvalHarness harness = new EvalHarness(
 *         obs -> Agent.builder(llm, freshTools(), config).observer(obs).build());
 * EvalReport report = harness.run(List.of(
 *         EvalCase.of("weather", Goal.of("Weather in Seattle?"),
 *                 Checks.completed(), Checks.usedTool("get_weather"),
 *                 Checks.outputContains("Seattle"))));
 * }</pre>
 */
public final class EvalHarness {

    private final Function<AgentObserver, Agent> agentFactory;

    /**
     * @param agentFactory builds an agent wired with the harness's trajectory
     *                     observer (attach it via {@code .observer(...)}); never {@code null}
     */
    public EvalHarness(Function<AgentObserver, Agent> agentFactory) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
    }

    /** Runs every case and returns the aggregate report. */
    public EvalReport run(List<EvalCase> cases) {
        Objects.requireNonNull(cases, "cases");
        List<CaseReport> reports = new ArrayList<>(cases.size());
        for (EvalCase evalCase : cases) {
            reports.add(runCase(evalCase));
        }
        return new EvalReport(reports);
    }

    /** Runs a single case, capturing its trajectory and applying its checks. */
    public CaseReport runCase(EvalCase evalCase) {
        Objects.requireNonNull(evalCase, "evalCase");
        TrajectoryCapture capture = new TrajectoryCapture();
        EvalRun run;
        try {
            Agent agent = Objects.requireNonNull(agentFactory.apply(capture), "agentFactory returned null");
            AgentResult result = agent.run(evalCase.goal());
            run = new EvalRun(evalCase.goal(), result, capture.toolCalls());
        } catch (RuntimeException e) {
            // Isolate a per-case build/run failure (a throwing factory, observer, or
            // context strategy) so it does not abort the rest of the dataset.
            run = new EvalRun(evalCase.goal(), AgentResult.failed(e, 0, TokenUsage.ZERO),
                    capture.toolCalls());
        }

        List<CheckOutcome> outcomes = new ArrayList<>(evalCase.checks().size());
        for (Check check : evalCase.checks()) {
            try {
                outcomes.add(check.check(run));
            } catch (RuntimeException e) {
                // A flaky check (e.g. a judge whose model call fails) fails that check,
                // never the whole run.
                outcomes.add(CheckOutcome.fail("check", "check threw: " + e.getMessage()));
            }
        }
        return new CaseReport(evalCase.id(), run, outcomes);
    }

    /**
     * Records each tool call and its outcome. Captures from {@code onToolResult}
     * (which fires for every requested tool, including unknown/gated/thrown ones,
     * with an error result) so success can be distinguished from a mere attempt.
     *
     * <p>The <strong>proposed</strong> call, not the effective one, and this is the one
     * place in the repository where that is the right answer. An eval scores the
     * <em>model</em>: "did it ask for the dangerous path" is the question, and a gate
     * narrowing the arguments afterwards is the harness protecting itself, not the model
     * choosing better. {@link ToolCall}'s own javadoc says {@code invocation} is "what the
     * model requested", and taking the effective call here would have reversed that
     * silently — which is why #131 removed the three-argument callback that made the choice
     * implicit rather than leaving a default to pick for this class.
     *
     * <p>{@code disposition} <strong>is</strong> captured, having deliberately not been.
     * The argument for dropping it was that a gate stopping a call is a fact about the
     * runtime while an eval scores the model, so "the harness's gate refused twice" would be
     * scoring the harness. That is right where the gate is scaffolding the harness put there
     * and wrong where the gate ships in the product — {@code agentkit-examples-itops} is the
     * second case, and "the platform refused the second route to the same capability" is
     * exactly what an eval on that agent is for. It also assumed {@link ToolCall#succeeded()}
     * could stand in, which {@code Disposition} measures as seven outcomes in one bit: a
     * refusal and a tool that ran and answered "no such user" are the same bit and call for
     * opposite judgements of what the model does next. {@link ToolCall} carries the
     * measurement.
     *
     * <p>{@code run} is not captured either, and for a reason particular to this class
     * rather than the one above (#311). A case builds one agent and runs it once, so every
     * row already belongs to the same run and an identity would distinguish nothing; a
     * harness whose agent factory wires subagents onto this same observer would need it, and
     * that is the point at which to start reading it rather than now.
     */
    private static final class TrajectoryCapture implements AgentObserver {
        private final List<ToolCall> calls = new ArrayList<>();

        @Override
        public void onToolResult(AgentRun run, int step, ToolInvocation proposed,
                                 ToolInvocation effective, ToolResult result,
                                 Disposition disposition) {
            calls.add(new ToolCall(proposed, result.isError(), disposition));
        }

        List<ToolCall> toolCalls() {
            return calls;
        }
    }
}
