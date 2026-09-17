package dev.agentkit.examples.onboarding;

import dev.agentkit.core.agent.Goal;
import dev.agentkit.examples.onboarding.OnboardingSystems.Worker;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The one part of onboarding that is about onboarding: what to ask for. The goal is the customer's
 * policy, conditions and all, followed by the worker's HRIS record; a
 * {@link dev.agentkit.examples.planexecute.PlanExecuteAgent} resolves the conditions against the record
 * while planning.
 */
public final class OnboardingGoal {

    private OnboardingGoal() {
    }

    /** The goal for onboarding {@code worker} under {@code policy}. */
    public static Goal forWorker(String policy, Worker worker) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(worker, "worker");
        StringBuilder record = new StringBuilder();
        new TreeMap<>(worker.fields()).forEach((field, value) ->
                record.append("- ").append(field).append(": ").append(value).append('\n'));
        return Goal.of("Onboard the new hire below by following the onboarding policy.\n\n"
                + policy + "\n\nNew hire (HRIS record):\n" + record.toString().stripTrailing());
    }
}
