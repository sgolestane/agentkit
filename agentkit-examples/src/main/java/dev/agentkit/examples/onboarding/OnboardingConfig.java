package dev.agentkit.examples.onboarding;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * What a customer customizes, and nothing else: the onboarding policy and the two prompts.
 *
 * <p>Each is a text file. The defaults ship on the classpath under {@code onboarding/}; an
 * environment variable naming a file replaces one of them without a code change or a rebuild:
 * {@code ONBOARDING_POLICY_FILE}, {@code ONBOARDING_PLANNER_PROMPT_FILE},
 * {@code ONBOARDING_EXECUTOR_PROMPT_FILE}. Everything else — the connectors, what each tool
 * declares about itself, and the bounds on deferred actions — is product code that behaves the
 * same for every customer.
 *
 * @param policy         the onboarding policy, conditions and all
 * @param plannerPrompt  the system prompt that turns the policy and a hire's facts into a flat plan
 * @param executorPrompt the system prompt each plan step runs with
 */
public record OnboardingConfig(String policy, String plannerPrompt, String executorPrompt) {

    public OnboardingConfig {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(plannerPrompt, "plannerPrompt");
        Objects.requireNonNull(executorPrompt, "executorPrompt");
    }

    /** The shipped defaults, each replaced by the file its environment variable names, if set. */
    public static OnboardingConfig fromEnv() {
        return from(System.getenv());
    }

    static OnboardingConfig from(Map<String, String> env) {
        return new OnboardingConfig(
                load(env, "ONBOARDING_POLICY_FILE", "onboarding/policy.md"),
                load(env, "ONBOARDING_PLANNER_PROMPT_FILE", "onboarding/planner-prompt.md"),
                load(env, "ONBOARDING_EXECUTOR_PROMPT_FILE", "onboarding/executor-prompt.md"));
    }

    private static String load(Map<String, String> env, String variable, String defaultResource) {
        String path = env.get(variable);
        try {
            if (path != null && !path.isBlank()) {
                return Files.readString(Path.of(path.strip()), StandardCharsets.UTF_8).strip();
            }
            try (InputStream in = OnboardingConfig.class.getClassLoader().getResourceAsStream(defaultResource)) {
                if (in == null) {
                    throw new IllegalStateException("Missing default resource " + defaultResource);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + (path != null ? path : defaultResource)
                    + " for " + variable, e);
        }
    }
}
