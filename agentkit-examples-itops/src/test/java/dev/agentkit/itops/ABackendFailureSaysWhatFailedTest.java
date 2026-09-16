package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The line an operator reads when a backend will not start has to name the fault.
 *
 * <p>Measured, from a real run where {@code OPENROUTER_API_KEY} was set in a shell but not
 * exported, so it never reached the forked surefire JVM:
 *
 * <pre>
 * ITOPS_LLM=openrouter was requested but the client could not be built
 * (java.lang.reflect.InvocationTargetException).
 * </pre>
 *
 * <p>The cause said {@code Environment variable OPENROUTER_API_KEY is not set} — the entire
 * answer — and the wrapper hid it, because {@code Method.invoke} wraps whatever a factory
 * throws and {@code InvocationTargetException.toString()} is a class name with no message.
 *
 * <p>This is the same shape as the spelling bug {@code TheRealModelPathResolvesTest} exists
 * for, one level along: there the reflection hid a name that did not resolve, here it hid the
 * reason a resolved name failed. A diagnostic that reports the mechanism rather than the
 * fault is worse than no diagnostic, because it is read only when something is already wrong.
 */
class ABackendFailureSaysWhatFailedTest {

    /** Whatever {@code realModel} wrote to stderr while returning empty. */
    private static String stderrFrom(Runnable call) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            call.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    /**
     * The exact failure from that run, reproduced without touching the environment.
     *
     * <p>{@code realModel} reads {@code ITOPS_LLM} from the process environment, which a test
     * cannot set portably, so this drives the reporting seam directly with the wrapper
     * {@code Method.invoke} produces. That is the part that was wrong; the branch that
     * chooses a backend is covered by {@code TheRealModelPathResolvesTest}.
     */
    @Test
    @DisplayName("a factory's own message survives the reflective wrapper")
    void theCauseIsReportedRatherThanTheWrapper() {
        String rendered = ItOpsApp.becauseForTest(new java.lang.reflect.InvocationTargetException(
                new dev.agentkit.core.llm.LlmException(
                        "Environment variable OPENROUTER_API_KEY is not set")));

        assertThat(rendered)
                .as("the answer the operator needs")
                .contains("Environment variable OPENROUTER_API_KEY is not set");
        assertThat(rendered)
                .as("and not the wrapper, which says nothing")
                .doesNotContain("InvocationTargetException");
    }

    /** A failure with no message still says something rather than an empty string. */
    @Test
    @DisplayName("a cause carrying no message falls back to naming itself")
    void aMessagelessCauseStillNamesItself() {
        assertThat(ItOpsApp.becauseForTest(
                new java.lang.reflect.InvocationTargetException(new IllegalStateException())))
                .contains("IllegalStateException");
    }

    /** An unwrapped failure is reported as itself, not skipped over. */
    @Test
    @DisplayName("a failure that was never wrapped is reported directly")
    void anUnwrappedFailureIsReportedDirectly() {
        assertThat(ItOpsApp.becauseForTest(new ClassNotFoundException("no such client")))
                .contains("no such client");
    }

    /** The absence of a backend is still silent, so nothing here changed that. */
    @Test
    @DisplayName("asking for no real model writes nothing and returns empty")
    void noBackendAsksForNothingAndSaysNothing() {
        StringBuilder result = new StringBuilder();
        String noise = stderrFrom(() -> {
            Optional<ItOpsApp.Backend> backend = ItOpsApp.realModel();
            result.append(backend.isPresent());
        });

        // The environment this suite runs in configures no backend, so realModel takes the
        // "asked for none" branch. If a machine running this DID configure one, the value
        // below is true and the assertion on quiet still holds -- which is why it asserts on
        // the noise rather than on the presence.
        assertThat(noise).as("the quiet path must stay quiet: %s", result).isEmpty();
    }
}
