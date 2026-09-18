package dev.agentkit.host;

import dev.agentkit.chat.ChatEvent;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Step;
import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.planning.LlmPlanner;
import dev.agentkit.core.planning.Plan;
import dev.agentkit.core.planning.PlanExecution;
import dev.agentkit.core.planning.PlanningAgent;
import dev.agentkit.core.tool.View;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.OneLine;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A turn with a plan-and-execute agent: the plan made once, from the request, the policy and who is asking; then each
 * step carried out by a fresh agent with the agent's tools — bound to the person, confirmations stopping for them, as in
 * any turn ({@link PlanningAgent} does the sequencing).
 *
 * <p>What the person sees, as it happens: the plan, as soon as it is made, and each step as it starts, with its tool
 * calls in the trace. The answer is every step and what came of it, and says where the plan stopped if a step did
 * not finish. The plan is a step in the trace, and the planner's tokens are counted in the turn's.
 */
final class PlanExecuteTurn implements ChatRuntime.Runner {

    /** The most steps a plan may have before it is refused rather than run. */
    static final int MAX_PLAN_STEPS = 20;

    /** How much of a step's output the answer quotes. */
    private static final int MAX_STEP_OUTPUT_CHARS = 600;

    private final HostedAgent agent;
    private final LlmClient llm;
    private final Principal principal;
    private final Instant now;
    private final Supplier<Agent> executors;
    private final ChatRuntime.Session session;

    PlanExecuteTurn(HostedAgent agent, LlmClient llm, Principal principal, Instant now, Supplier<Agent> executors,
                    ChatRuntime.Session session) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.llm = Objects.requireNonNull(llm, "llm");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.now = Objects.requireNonNull(now, "now");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public AgentResult run(Goal goal, List<ContentBlock> alsoSent) {
        AtomicReference<TokenUsage> plannerUsage = new AtomicReference<>(TokenUsage.ZERO);
        LlmClient counted = request -> {
            LlmResponse response = llm.generate(request);
            plannerUsage.set(plannerUsage.get().plus(response.usage()));
            return response;
        };
        LlmPlanner planner = new LlmPlanner(counted, agent.model(), agent.plannerPrompt(principal, now),
                agent.definition().maxTokens());
        AtomicReference<Plan> made = new AtomicReference<>();
        AtomicInteger built = new AtomicInteger();

        PlanningAgent planAndExecute = new PlanningAgent((request, tools) -> {
            long started = System.nanoTime();
            Plan plan = planner.plan(request, tools);
            if (plan.size() > MAX_PLAN_STEPS) {
                throw new IllegalStateException("The plan had " + plan.size() + " steps, more than the "
                        + MAX_PLAN_STEPS + " a turn may carry out.");
            }
            made.set(plan);
            show(plan, (System.nanoTime() - started) / 1_000_000);
            return plan;
        }, () -> {
            // The first executor built only lends its tool catalog to the planner; each after it runs a step.
            int step = built.getAndIncrement();
            Plan plan = made.get();
            if (step >= 1 && plan != null && step <= plan.size()) {
                say("**Step " + step + " of " + plan.size() + ":** " + OneLine.of(plan.steps().get(step - 1)) + "\n\n");
            }
            return executors.get();
        });

        PlanExecution execution;
        try {
            execution = planAndExecute.run(withWhatElseWasSent(goal, alsoSent));
        } catch (IllegalStateException refused) {
            return AgentResult.failed(refused, refused.getMessage(), 0, plannerUsage.get());
        }
        AgentResult overall = execution.overall();
        String answer = answer(execution);
        TokenUsage usage = overall.usage().plus(plannerUsage.get());
        return overall.stopReason() == StopReason.COMPLETED
                ? AgentResult.completed(answer, overall.steps(), usage)
                : new AgentResult(overall.stopReason(), answer, overall.steps(), usage, overall.error(), overall.awaiting());
    }

    /** The request, with the conversation so far and anything attached, for the planner to read. */
    private static Goal withWhatElseWasSent(Goal goal, List<ContentBlock> alsoSent) {
        StringBuilder text = new StringBuilder(goal.render());
        for (ContentBlock block : alsoSent) {
            if (block instanceof TextBlock t && !t.text().isBlank()) {
                text.append("\n\n").append(t.text());
            }
        }
        return Goal.of(text.toString());
    }

    /** Every step and what came of it; where the plan stopped, if it did. */
    private static String answer(PlanExecution execution) {
        List<String> steps = execution.plan().steps();
        List<AgentResult> results = execution.stepResults();
        if (steps.isEmpty()) {
            return execution.overall().output();
        }
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            answer.append(i + 1).append(". ").append(OneLine.of(steps.get(i)));
            if (i < results.size()) {
                AgentResult result = results.get(i);
                String output = Cut.to(OneLine.of(result.output() == null ? "" : result.output()), MAX_STEP_OUTPUT_CHARS);
                answer.append(result.stopReason() == StopReason.COMPLETED ? "\n   Done: " : "\n   Stopped ("
                        + result.stopReason().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + "): ")
                        .append(output.isEmpty() ? "(nothing said)" : output);
            } else {
                answer.append("\n   Not started.");
            }
            answer.append("\n");
        }
        return answer.toString().stripTrailing();
    }

    /** The plan onto the turn: a step in the trace, a view for the person, and on the live stream. */
    private void show(Plan plan, long millis) {
        StringBuilder markdown = new StringBuilder("**Plan**\n\n");
        for (int i = 0; i < plan.size(); i++) {
            markdown.append(i + 1).append(". ").append(OneLine.of(plan.steps().get(i))).append('\n');
        }
        View view = View.markdown(markdown.toString());
        session.store().addStep(session.tenantId(), session.conversationId(), session.turnId(), Step.Kind.NOTE, "plan",
                Map.of("steps", plan.steps()), millis, false);
        session.store().show(session.tenantId(), session.conversationId(), session.turnId(), view);
        session.events().publish(session.conversationId(), session.turnId(), ChatEvent.Type.VIEW, "plan",
                agent.definition().id(), Map.of("kind", view.kind(), "data", view.data()));
    }

    private void say(String text) {
        session.events().publish(session.conversationId(), session.turnId(), ChatEvent.Type.TEXT_DELTA, "plan",
                agent.definition().id(), Map.of("text", text));
    }
}
