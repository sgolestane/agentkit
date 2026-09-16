package dev.agentkit.core.verify;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Factories and combinators for non-model {@link Verifier}s.
 *
 * <p>Not every check needs an LLM. When "correct" is a property you can express
 * as code — the output matches a regex, parses as JSON, contains a required
 * marker — a deterministic verifier is cheaper, faster, and not itself
 * fallible. These compose with {@link LlmVerifier} via {@link #allOf} so a run
 * can be gated on both a cheap structural check and a semantic one.
 */
public final class Verifiers {

    private Verifiers() {
    }

    /**
     * Passes when the output, in full, matches {@code pattern}. Use {@code .*}
     * around a fragment (or {@link #containing}) to match a substring.
     */
    public static Verifier matching(Pattern pattern) {
        Objects.requireNonNull(pattern, "pattern");
        return (goal, output) -> pattern.matcher(output).matches()
                ? Verdict.pass()
                : Verdict.fail("Output did not match the required pattern: " + pattern.pattern());
    }

    /** Passes when the output contains {@code required} as a substring. */
    public static Verifier containing(String required) {
        Objects.requireNonNull(required, "required");
        return (goal, output) -> output.contains(required)
                ? Verdict.pass()
                : Verdict.fail("Output did not contain the required text: " + required);
    }

    /**
     * Passes when {@code predicate} accepts the output. The {@code reason} is used
     * as feedback on failure, so make it actionable ("output must be valid JSON").
     */
    public static Verifier satisfies(Predicate<String> predicate, String reason) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(reason, "reason");
        return (goal, output) -> predicate.test(output) ? Verdict.pass() : Verdict.fail(reason);
    }

    /**
     * Combines verifiers: passes only if <em>every</em> verifier passes, returning
     * the first failure's verdict. With no verifiers the result passes (fail-open):
     * an empty policy imposes no check. Cheaper checks run first, so put structural
     * verifiers ahead of a model critic to short-circuit before spending a call.
     */
    public static Verifier allOf(Verifier... verifiers) {
        Verifier[] all = verifiers.clone();
        return (goal, output) -> {
            for (Verifier verifier : all) {
                Verdict verdict = verifier.verify(goal, output);
                if (!verdict.passed()) {
                    return verdict;
                }
            }
            return Verdict.pass();
        };
    }
}
