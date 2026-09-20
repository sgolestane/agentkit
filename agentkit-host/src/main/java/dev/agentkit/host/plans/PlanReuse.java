package dev.agentkit.host.plans;

import dev.agentkit.host.repo.AgentDefinition;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a plan-execute agent may reuse a plan rather than ask the model for one, and which.
 *
 * <p>A kind of task has a settled plan when the last {@link AgentDefinition.PlanReuse#after()} plans the model made
 * for it were carried out cleanly and are the same, word for word, once each task's values are taken out — and when
 * every value the plan puts back was seen to differ across those runs, so the plan is known to hold for other values
 * and not merely to have met the same one each time. A plan reused cleanly keeps it settled; one reused and not
 * carried out cleanly, or a person turning down a step, unsettles it, and so does the model planning differently when
 * it is asked again, which it is every {@link AgentDefinition.PlanReuse#recheckEvery()} runs.
 */
public final class PlanReuse {

    /** How many runs back are read to find a settled plan. */
    public static final int LOOK_BACK = 50;

    private final PlanBook book;

    public PlanReuse(PlanBook book) {
        this.book = Objects.requireNonNull(book, "book");
    }

    public PlanBook book() {
        return book;
    }

    /** A settled plan: its parameterized steps, and how many plans in a row agreed on it. */
    public record Settled(List<String> steps, int agreed) {
        public Settled {
            steps = List.copyOf(steps);
        }
    }

    /** The plan to reuse for this run of {@code task}, if there is one and this run is not a recheck. */
    public Optional<Settled> forRun(PlanBook.Agent agent, AgentDefinition.PlanReuse rules, PlanTask task) {
        long run = book.count(agent, task.shape()) + 1;
        if (rules.recheckEvery() <= 1 || run % rules.recheckEvery() == 0) {
            return Optional.empty();
        }
        return settled(agent, rules, task.shape());
    }

    /** The settled plan for {@code shape}, if it has one. */
    public Optional<Settled> settled(PlanBook.Agent agent, AgentDefinition.PlanReuse rules, String shape) {
        List<PlanBook.Run> agreeing = new java.util.ArrayList<>();
        for (PlanBook.Run run : book.recent(agent, shape, LOOK_BACK)) {
            if (!run.clean()) {
                break;
            }
            if (run.reused()) {
                continue;
            }
            agreeing.add(run);
            if (agreeing.size() == rules.after()) {
                break;
            }
        }
        if (agreeing.size() < rules.after()
                || agreeing.stream().anyMatch(run -> !run.steps().equals(agreeing.get(0).steps()))) {
            return Optional.empty();
        }
        for (String step : agreeing.get(0).steps()) {
            for (String name : PlanTask.placeholders(step)) {
                if (name.startsWith("input.")) {
                    Set<String> seen = new HashSet<>();
                    agreeing.forEach(run -> seen.add(run.values().get(name)));
                    if (seen.size() < 2) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.of(new Settled(agreeing.get(0).steps(), agreeing.size()));
    }
}
