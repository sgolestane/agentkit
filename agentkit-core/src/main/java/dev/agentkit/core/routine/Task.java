package dev.agentkit.core.routine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a goal is, for the purpose of recognising that it has been done before: a kind of work, and the values that
 * make this instance of it different from the last one.
 *
 * <p>"Onboard a worker" is the kind; {@code employee_id=W-1002} is the parameter. Two goals with the same kind are
 * the same job with different values, which is what lets a run recorded for one be replayed for the other.
 *
 * <p>The framework cannot work this out from a goal's text — only the deployment knows which words are the job and
 * which are the values, so {@link TaskShape} is supplied rather than inferred.
 *
 * @param kind       the job, stable across instances; never blank
 * @param parameters the values that vary, by name; never null, and no null keys or values
 */
public record Task(String kind, Map<String, String> parameters) {

    public Task {
        Objects.requireNonNull(kind, "kind");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("a task kind names the job, and is never blank");
        }
        Objects.requireNonNull(parameters, "parameters");
        Map<String, String> copy = new LinkedHashMap<>();
        parameters.forEach((name, value) -> {
            Objects.requireNonNull(name, "parameter name");
            Objects.requireNonNull(value, "value of parameter " + name);
            copy.put(name, value);
        });
        parameters = Map.copyOf(copy);
    }

    /** A task with no parameters: every instance of this kind is the same job. */
    public static Task of(String kind) {
        return new Task(kind, Map.of());
    }

    /** A task with one parameter. */
    public static Task of(String kind, String parameter, String value) {
        return new Task(kind, Map.of(parameter, value));
    }
}
