package dev.agentkit.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * The reflective backend names, pinned. A misspelt reflective name is a runtime warning
 * where a direct call is a compile error — the itops example shipped exactly that defect,
 * so every name this module reaches for is a constant and this test resolves each one.
 */
class TheRealModelPathResolvesTest {

    @Test
    void everyReflectiveNameResolves() throws Exception {
        Class<?> anthropic = Class.forName(WorkbenchApp.ANTHROPIC_CLIENT);
        assertThatCode(() -> anthropic.getMethod(WorkbenchApp.ANTHROPIC_FACTORY))
                .doesNotThrowAnyException();
        assertThatCode(() -> anthropic.getField(WorkbenchApp.ANTHROPIC_DEFAULT_MODEL))
                .doesNotThrowAnyException();

        Class<?> openRouter = Class.forName(WorkbenchApp.OPENROUTER_CLIENT);
        assertThatCode(() -> openRouter.getMethod(WorkbenchApp.OPENROUTER_FACTORY))
                .doesNotThrowAnyException();
    }

    @Test
    void aWrappedFailureIsReportedByItsCause() {
        Exception cause = new IllegalStateException("Environment variable X is not set");
        Exception wrapped = new java.lang.reflect.InvocationTargetException(cause);
        assertThat(WorkbenchApp.becauseForTest(wrapped))
                .contains("Environment variable X is not set");
    }
}
