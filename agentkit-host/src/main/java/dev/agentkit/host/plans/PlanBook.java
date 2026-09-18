package dev.agentkit.host.plans;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every plan a plan-execute agent carried out for a task started from its form: kept by organization, agent, version
 * and kind of task ({@link PlanTask#shape}), with the task's values taken out. What {@link PlanReuse} decides from.
 * With several instances of the host it must be shared (Postgres), so they settle on a plan together.
 */
public interface PlanBook {

    /**
     * One plan carried out.
     *
     * @param steps  the plan's steps, parameterized
     * @param values the task's values the steps were parameterized with
     * @param reused whether it was a settled plan reused, rather than one the model made
     * @param clean  whether every step of it completed
     */
    record Run(List<String> steps, Map<String, String> values, boolean reused, boolean clean, Instant at) {
        public Run {
            steps = List.copyOf(steps);
            values = Map.copyOf(values);
            Objects.requireNonNull(at, "at");
        }
    }

    /** Where plans for one agent at one version are kept. */
    record Agent(String org, String agent, String version) {
        public Agent {
            Objects.requireNonNull(org, "org");
            Objects.requireNonNull(agent, "agent");
            Objects.requireNonNull(version, "version");
        }
    }

    void add(Agent agent, String shape, Run run);

    /** The most recent runs of {@code shape}, newest first, at most {@code limit}. */
    List<Run> recent(Agent agent, String shape, int limit);

    /** How many runs of {@code shape} there have been. */
    long count(Agent agent, String shape);

    /** Every kind of task the agent has carried out a plan for, most recently run first. */
    List<String> shapes(Agent agent);

    /** A book in this process: for one instance, and forgotten on restart. */
    static PlanBook inMemory() {
        record Kept(Agent agent, String shape, Run run) {
        }
        List<Kept> kept = new java.util.concurrent.CopyOnWriteArrayList<>();
        return new PlanBook() {
            @Override
            public void add(Agent agent, String shape, Run run) {
                kept.add(new Kept(agent, shape, run));
            }

            @Override
            public List<Run> recent(Agent agent, String shape, int limit) {
                List<Run> runs = new ArrayList<>();
                for (int i = kept.size() - 1; i >= 0 && runs.size() < limit; i--) {
                    Kept k = kept.get(i);
                    if (k.agent().equals(agent) && k.shape().equals(shape)) {
                        runs.add(k.run());
                    }
                }
                return runs;
            }

            @Override
            public long count(Agent agent, String shape) {
                return kept.stream().filter(k -> k.agent().equals(agent) && k.shape().equals(shape)).count();
            }

            @Override
            public List<String> shapes(Agent agent) {
                Map<String, Instant> last = new ConcurrentHashMap<>();
                kept.stream().filter(k -> k.agent().equals(agent)).forEach(k -> last.merge(k.shape(), k.run().at(),
                        (a, b) -> a.isAfter(b) ? a : b));
                return last.entrySet().stream().sorted(Map.Entry.<String, Instant>comparingByValue(Comparator.reverseOrder()))
                        .map(Map.Entry::getKey).toList();
            }
        };
    }
}
