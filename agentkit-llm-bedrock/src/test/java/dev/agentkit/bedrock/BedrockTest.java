package dev.agentkit.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.bedrock.backends.BedrockBackend;
import com.anthropic.bedrock.backends.BedrockMantleBackend;
import dev.agentkit.anthropic.AnthropicLlmClient;
import dev.agentkit.anthropic.ModelResolver;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;

/**
 * Boundary null-checks on the {@link Bedrock} factory — the paths that don't need
 * AWS credentials. The credential/region-dependent factories are exercised by the
 * runnable examples, not unit tests.
 */
class BedrockTest {

    @Test
    void clientRejectsNullBackend() {
        assertThatThrownBy(() -> Bedrock.client((BedrockMantleBackend) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("backend");
    }

    @Test
    void invokeModelClientRejectsNullBackend() {
        assertThatThrownBy(() -> Bedrock.client((BedrockBackend) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("backend");
    }

    @Test
    void mantleLlmClientRejectsNullRegion() {
        assertThatThrownBy(() -> Bedrock.llmClient((Region) null, ModelResolver.IDENTITY))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("region");
    }

    @Test
    void invokeModelRejectsNullRegion() {
        assertThatThrownBy(() -> Bedrock.invokeModel((Region) null, ModelResolver.IDENTITY))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("region");
    }

    @Test
    void invokeModelRejectsNullResolver() {
        assertThatThrownBy(() -> Bedrock.invokeModel((ModelResolver) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelResolver");
    }

    /**
     * The third adapter, and the whole of what "check Bedrock" comes to (#271).
     *
     * <p>#271 names {@code AnthropicLlmClient} and {@code OpenRouterLlmClient} and asks
     * about the third adapter that ships here. There is no third response mapping: this
     * module builds an SDK backend and hands it to {@link AnthropicLlmClient}, so a
     * {@code tool_use} arriving over Bedrock is converted by the code #271 changed, and a
     * fix applied there covers both backends by construction rather than by copy.
     *
     * <p>Checked as a property of every public factory rather than asserted in prose,
     * because the way that stops being true is a factory that grows its own client — and
     * that is a change nothing else here would notice. A live round trip is not available
     * without AWS credentials, which is why this module's other tests stop at the null
     * checks; what a live round trip would prove about the conversion,
     * {@code AnthropicUnreadableArgumentsTest} proves where the conversion lives.
     */
    @Test
    void everyFactoryHandsBackTheAnthropicClientSoThereIsNoThirdConversion() {
        List<Method> factories = Arrays.stream(Bedrock.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getName().equals("llmClient")
                        || method.getName().equals("invokeModel"))
                .toList();

        assertThat(factories)
                .as("the factories this asserts over have been renamed away from under it")
                .hasSizeGreaterThanOrEqualTo(8);
        assertThat(factories)
                .as("a Bedrock factory grew a client of its own, so a tool-argument"
                        + " conversion fixed in AnthropicLlmClient no longer covers it")
                .allSatisfy(method ->
                        assertThat(method.getReturnType()).isEqualTo(AnthropicLlmClient.class));
    }
}
