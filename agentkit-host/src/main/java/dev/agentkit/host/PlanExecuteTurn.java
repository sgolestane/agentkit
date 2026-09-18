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
import dev.agentkit.core.prompt.Source;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.host.plans.PlanBook;
import dev.agentkit.host.plans.PlanReuse;
import dev.agentkit.host.plans.PlanTask;
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
import java.util.Optional;
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
 *
 * <p><strong>Reusing a settled plan.</strong> For an agent with {@code plans.reuse}, started from its form, every plan
 * carried out is kept with the task's values taken out ({@link PlanReuse}). Once the plans for a kind of task have
 * settled, the next is not asked of the model: the settled plan is filled with this task's values and carried out the
 * same way, each step by the model, each confirmation asked. The plan says it was reused.
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
    private final Optional<FormTask> form;

    /**
     * A turn started from the agent's form, whose plan may be reused and is kept.
     *
     * @param request what the form made of the input: the turn is this task only if its request is exactly this
     */
    record FormTask(PlanReuse plans, PlanBook.Agent where, PlanTask task, String request) {
    }

    PlanExecuteTurn(HostedAgent agent, LlmClient llm, Principal principal, Instant now, Supplier<Agent> executors,
                    ChatRuntime.Session session) {
        this(agent, llm, principal, now, executors, session, Optional.empty());
    }

    PlanExecuteTurn(HostedAgent agent, LlmClient llm, Principal principal, Instant now, Supplier<Agent> executors,
                    ChatRuntime.Session session, Optional<FormTask> form) {
        this.form = Objects.requireNonNull(form, "form");
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
        // This turn is the form's task only if nothing else was said with it.
        Optional<FormTask> task = form.filter(f -> alsoSent.isEmpty()
                && goal.render().strip().equals(f.request().strip()));
        Optional<PlanReuse.Settled> settled = task.flatMap(f -> f.plans().forRun(f.where(),
                agent.definition().planReuse(), f.task()));
        Optional<Plan> reused = settled.flatMap(s -> instantiate(s, task.get().task()));

        PlanningAgent planAndExecute = new PlanningAgent((request, tools) -> {
            long started = System.nanoTime();
            if (reused.isPresent()) {
                made.set(reused.get());
                show(reused.get(), 0, settled.get().agreed());
                return reused.get();
            }
            Plan plan = planner.plan(task.flatMap(PlanExecuteTurn::lastPlan)
                    .map(last -> Goal.of(request.render() + "\n\n" + last)).orElse(request), tools);
            if (plan.size() > MAX_PLAN_STEPS) {
                throw new IllegalStateException("The plan had " + plan.size() + " steps, more than the "
                        + MAX_PLAN_STEPS + " a turn may carry out.");
            }
            made.set(plan);
            show(plan, (System.nanoTime() - started) / 1_000_000, 0);
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
        task.ifPresent(f -> keep(f, execution, reused.isPresent()));
        String answer = answer(execution);
        TokenUsage usage = overall.usage().plus(plannerUsage.get());
        return overall.stopReason() == StopReason.COMPLETED
                ? AgentResult.completed(answer, overall.steps(), usage)
                : new AgentResult(overall.stopReason(), answer, overall.steps(), usage, overall.error(), overall.awaiting());
    }

    /**
     * The last plan the model made, and carried out cleanly, for a task like this one, with this task's values: shown to
     * the planner, so that where the same actions apply it words them the same way, and plans can settle. The model still
     * decides; it is only spared rewording.
     */
    private static Optional<String> lastPlan(FormTask f) {
        return f.plans().book().recent(f.where(), f.task().shape(), PlanReuse.LOOK_BACK).stream()
                .filter(run -> run.clean() && !run.reused()).findFirst()
                .flatMap(run -> instantiate(new PlanReuse.Settled(run.steps(), 1), f.task()))
                .map(plan -> {
                    StringBuilder steps = new StringBuilder();
                    for (int i = 0; i < plan.size(); i++) {
                        steps.append(i + 1).append(". ").append(plan.steps().get(i)).append('\n');
                    }
                    return "Tasks like this one have been planned before, as below. If the same actions apply to this "
                            + "one, write the plan exactly as below, word for word and in the same order; change, add "
                            + "or leave out a step only where this task needs something different.\n"
                            + Spotlight.wrap(Spotlight.Kind.EVIDENCE, Source.of("earlier-plan"),
                                    steps.toString().stripTrailing());
                });
    }

    /** The settled plan with this task's values put back; empty if one of its placeholders has no value here. */
    private static Optional<Plan> instantiate(PlanReuse.Settled settled, PlanTask task) {
        List<String> steps = new java.util.ArrayList<>();
        for (String step : settled.steps()) {
            Optional<String> filled = task.instantiate(step);
            if (filled.isEmpty()) {
                return Optional.empty();
            }
            steps.add(filled.get());
        }
        return Optional.of(new Plan(steps));
    }

    /** The plan carried out, with the task's values taken out, and whether every step of it completed. */
    private void keep(FormTask f, PlanExecution execution, boolean reused) {
        if (execution.plan().steps().isEmpty()) {
            return;
        }
        boolean clean = execution.overall().stopReason() == StopReason.COMPLETED
                && execution.stepResults().size() == execution.plan().size()
                && execution.stepResults().stream().allMatch(r -> r.stopReason() == StopReason.COMPLETED);
        try {
            f.plans().book().add(f.where(), f.task().shape(), new PlanBook.Run(
                    execution.plan().steps().stream().map(f.task()::parameterize).toList(), f.task().values(), reused,
                    clean, Instant.now()));
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(PlanExecuteTurn.class).warn("Could not keep the plan of {}",
                    agent.definition().id(), e);
        }
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
                // A nested item, so the answer reads as a list of steps each with its outcome under it.
                answer.append(result.stopReason() == StopReason.COMPLETED ? "\n   - Done: " : "\n   - Stopped ("
                        + result.stopReason().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + "): ")
                        .append(output.isEmpty() ? "(nothing said)" : output);
            } else {
                answer.append("\n   - Not started.");
            }
            answer.append("\n");
        }
        return answer.toString().stripTrailing();
    }

    /**
     * The plan onto the turn: a step in the trace, a view for the person, and on the live stream.
     *
     * @param reusedAfter how many plans in a row agreed on it, when it was reused rather than made; 0 when made
     */
    private void show(Plan plan, long millis, int reusedAfter) {
        StringBuilder markdown = new StringBuilder(reusedAfter > 0
                ? "**Plan** (reused: the last " + reusedAfter + " plans for tasks like this one agreed on it, so no "
                        + "model was asked to make it)\n\n"
                : "**Plan**\n\n");
        for (int i = 0; i < plan.size(); i++) {
            markdown.append(i + 1).append(". ").append(OneLine.of(plan.steps().get(i))).append('\n');
        }
        View view = View.markdown(markdown.toString());
        session.store().addStep(session.tenantId(), session.conversationId(), session.turnId(), Step.Kind.NOTE, "plan",
                reusedAfter > 0 ? Map.of("steps", plan.steps(), "reused", true, "agreed", reusedAfter)
                        : Map.of("steps", plan.steps()), millis, false);
        session.store().show(session.tenantId(), session.conversationId(), session.turnId(), view);
        session.events().publish(session.conversationId(), session.turnId(), ChatEvent.Type.VIEW, "plan",
                agent.definition().id(), Map.of("kind", view.kind(), "data", view.data()));
    }

    private void say(String text) {
        session.events().publish(session.conversationId(), session.turnId(), ChatEvent.Type.TEXT_DELTA, "plan",
                agent.definition().id(), Map.of("text", text));
    }
}
