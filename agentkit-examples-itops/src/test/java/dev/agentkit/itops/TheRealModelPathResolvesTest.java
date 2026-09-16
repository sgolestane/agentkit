package dev.agentkit.itops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.LlmClient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * The one path in this module a compiler cannot check, checked.
 *
 * <p>{@code ItOpsApp.realModel} reaches the Anthropic client reflectively, deliberately: the
 * demo's claim is that it runs with no API key and no optional jar, and a direct reference
 * would make the offline path depend on something it never calls. The cost is that every name
 * in that lookup is a string, and a string that does not resolve is a warning on stderr where
 * a direct call would be a compile error.
 *
 * <p>It did not resolve. The factory was spelt {@code fromEnvironment} and the client's is
 * {@code fromEnv}, so {@code ITOPS_LLM=anthropic} threw {@code NoSuchMethodException}, printed
 * "the client could not be built", and ran the scripted stand-in — for every run, since the
 * method was introduced. The README and two class javadocs said that setting one environment
 * variable puts a real model behind the same runtime and "nothing else changes"; nothing else
 * changed because nothing changed at all.
 *
 * <p>This is not a test of the Anthropic client. It is a test that the <em>strings this
 * module holds</em> name something that exists, which is the only defect the reflection can
 * hide. It reads the constants rather than repeating them, so editing them to something wrong
 * fails here rather than passing a copy of the same mistake.
 */
class TheRealModelPathResolvesTest {

    @Test
    void theClassTheLookupNamesIsOnTheClasspath() throws Exception {
        assertThat(Class.forName(ItOpsApp.ANTHROPIC_CLIENT))
                .as("the optional client is a compile dependency of this module, so a name"
                        + " that does not resolve is a typo rather than a missing jar")
                .isNotNull();
    }

    @Test
    void theFactoryTheLookupNamesExistsAndReturnsAnLlmClient() throws Exception {
        Method factory = Class.forName(ItOpsApp.ANTHROPIC_CLIENT)
                .getMethod(ItOpsApp.ANTHROPIC_FACTORY);

        assertThat(Modifier.isStatic(factory.getModifiers()))
                .as("realModel invokes it with a null receiver")
                .isTrue();
        assertThat(LlmClient.class.isAssignableFrom(factory.getReturnType()))
                .as("realModel casts the result to LlmClient, and a ClassCastException there"
                        + " is caught and reported as 'the client could not be built'")
                .isTrue();
    }

    @Test
    void theDefaultModelFieldExistsAndIsAStringToSend() throws Exception {
        Field field = Class.forName(ItOpsApp.ANTHROPIC_CLIENT)
                .getField(ItOpsApp.ANTHROPIC_DEFAULT_MODEL);

        assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
        assertThat(field.getType()).isEqualTo(String.class);
        assertThat(String.valueOf(field.get(null)))
                .as("the identifier requests carry when nobody names one; ITOPS_MODEL's own"
                        + " default is \"scripted\", which is not something to send an API")
                .isNotBlank()
                .isNotEqualTo("scripted");
    }

    @Test
    void withoutTheEnvironmentVariableThereIsNoRealModelAndNothingIsBuilt() {
        // The offline default, which is what every run in CI takes. Empty rather than a
        // stand-in, so a caller that must not score the fixture -- an eval -- can tell.
        assertThat(System.getenv("ITOPS_LLM"))
                .as("this test describes the offline default and the environment overrides it")
                .isNotEqualTo("anthropic");
        assertThat(ItOpsApp.realModel()).isEmpty();
    }

    /**
     * The same three questions for OpenRouter, added when that backend was.
     *
     * <p>Two reflective lookups is two chances at the defect this class exists for, and the
     * second one arrived after the first had already shipped a name that did not resolve.
     * OpenRouter has no default-model field, so there are two names here rather than three
     * and {@code ItOpsApp.openRouter} refuses without {@code ITOPS_MODEL} instead of reading
     * one — which is why no third assertion is missing.
     */
    @Test
    void theOpenRouterClassTheLookupNamesIsOnTheClasspath() throws Exception {
        assertThat(Class.forName(ItOpsApp.OPENROUTER_CLIENT))
                .as("a compile dependency of this module, so a name that does not resolve is"
                        + " a typo rather than a missing jar")
                .isNotNull();
    }

    @Test
    void theOpenRouterFactoryExistsAndReturnsAnLlmClient() throws Exception {
        Method factory = Class.forName(ItOpsApp.OPENROUTER_CLIENT)
                .getMethod(ItOpsApp.OPENROUTER_FACTORY);

        assertThat(Modifier.isStatic(factory.getModifiers()))
                .as("realModel invokes it with a null receiver")
                .isTrue();
        assertThat(LlmClient.class.isAssignableFrom(factory.getReturnType()))
                .as("realModel casts the result to LlmClient")
                .isTrue();
    }

    /**
     * The backend names are matched case-insensitively and anything else means "no real
     * model" — including a misspelling, which must not quietly select a different backend.
     */
    @Test
    void onlyTheTwoKnownBackendNamesSelectARealModel() {
        assertThat(ItOpsApp.OPENROUTER_CLIENT).endsWith("OpenRouterLlmClient");
        assertThat(ItOpsApp.ANTHROPIC_CLIENT).endsWith("AnthropicLlmClient");
        assertThat(ItOpsApp.OPENROUTER_CLIENT).isNotEqualTo(ItOpsApp.ANTHROPIC_CLIENT);
    }
}
