package dev.agentkit.bedrock;

import com.anthropic.bedrock.backends.BedrockBackend;
import com.anthropic.bedrock.backends.BedrockMantleBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import dev.agentkit.anthropic.AnthropicLlmClient;
import dev.agentkit.anthropic.ModelResolver;
import java.util.Objects;
import software.amazon.awssdk.regions.Region;

/**
 * Factory for running the AgentKit Anthropic adapter against Claude on Amazon
 * Bedrock. It wires one of the Anthropic SDK's Bedrock backends into an
 * {@link AnthropicLlmClient}; the rest of AgentKit is unchanged.
 *
 * <p><strong>Two backends, two invocation models.</strong>
 * <ul>
 *   <li>{@link #llmClient()} — the <b>Mantle</b> backend (Anthropic's
 *       Messages-API-native Bedrock surface, {@code bedrock-mantle}). Invocation
 *       is authorized as {@code bedrock-mantle:CreateInference} on a
 *       <em>project</em>; the model is the bare foundation-model id (e.g.
 *       {@code anthropic.claude-opus-4-8}). Application inference profiles do
 *       <em>not</em> apply here — cost attribution is per project. Recommended
 *       for new integrations.</li>
 *   <li>{@link #invokeModel()} — the classic <b>InvokeModel</b> backend
 *       ({@code bedrock-runtime:InvokeModel}). This is the path where
 *       <em>application inference profiles</em> apply: the model id can be a
 *       cross-region profile id or an application-inference-profile ARN. Pair it
 *       with an {@link InferenceProfiles} resolver to map logical ids →
 *       account-specific ARNs.</li>
 * </ul>
 *
 * <p>Model ids carry an {@code anthropic.} prefix (see {@link BedrockModels}). A
 * {@link ModelResolver} maps the logical ids your agents use onto the concrete
 * wire ids.
 *
 * <pre>{@code
 * // InvokeModel + application inference profiles (discovered at runtime):
 * try (BedrockClient control = BedrockClient.create()) {
 *     ModelResolver resolver = InferenceProfiles.resolver(control);
 *     LlmClient llm = Bedrock.invokeModel(resolver);   // AgentConfig model = "anthropic.claude-opus-4-8"
 * }                                                    // → resolved to your profile ARN per request
 *
 * // Mantle (no profiles): the bare foundation-model id, routed via the project.
 * LlmClient mantle = Bedrock.llmClient();              // AgentConfig model = "anthropic.claude-opus-4-8"
 * }</pre>
 *
 * <p><strong>Structured output.</strong> {@code LlmRequest.outputSchema()} rides along
 * on both backends, since both go through {@link AnthropicLlmClient}. Constrained
 * decoding is generally available on the Mantle backend; on classic InvokeModel it
 * depends on the model, so verify before relying on it there.
 *
 * <p>AWS credentials and region resolve through the standard AWS chain
 * ({@code AWS_REGION} is required); the {@code (Region, …)} overloads or an
 * explicitly-built backend set them directly.
 */
public final class Bedrock {

    private Bedrock() {
    }

    // --- Mantle backend (bedrock-mantle; projects, no inference profiles) -------

    /** An {@link AnthropicClient} over the Mantle {@code backend}. */
    public static AnthropicClient client(BedrockMantleBackend backend) {
        Objects.requireNonNull(backend, "backend");
        return AnthropicOkHttpClient.builder().backend(backend).build();
    }

    /** A Mantle-backed {@code LlmClient} using the default region/credentials, no model mapping. */
    public static AnthropicLlmClient llmClient() {
        return llmClient(ModelResolver.IDENTITY);
    }

    /** A Mantle-backed {@code LlmClient} using the default region/credentials and {@code modelResolver}. */
    public static AnthropicLlmClient llmClient(ModelResolver modelResolver) {
        Objects.requireNonNull(modelResolver, "modelResolver");
        return llmClient(BedrockMantleBackend.fromEnv(), modelResolver);
    }

    /** A Mantle-backed {@code LlmClient} pinned to {@code region}, with {@code modelResolver}. */
    public static AnthropicLlmClient llmClient(Region region, ModelResolver modelResolver) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(modelResolver, "modelResolver");
        return llmClient(BedrockMantleBackend.builder().region(region).build(), modelResolver);
    }

    /** A Mantle-backed {@code LlmClient} over an explicitly-built {@code backend}. */
    public static AnthropicLlmClient llmClient(BedrockMantleBackend backend, ModelResolver modelResolver) {
        return new AnthropicLlmClient(client(backend), modelResolver);
    }

    // --- Legacy InvokeModel backend (bedrock-runtime; inference profiles) -------

    /** An {@link AnthropicClient} over the InvokeModel {@code backend}. */
    public static AnthropicClient client(BedrockBackend backend) {
        Objects.requireNonNull(backend, "backend");
        return AnthropicOkHttpClient.builder().backend(backend).build();
    }

    /** An InvokeModel-backed {@code LlmClient} using the default region/credentials, no model mapping. */
    public static AnthropicLlmClient invokeModel() {
        return invokeModel(ModelResolver.IDENTITY);
    }

    /** An InvokeModel-backed {@code LlmClient} using the default region/credentials and {@code modelResolver}. */
    public static AnthropicLlmClient invokeModel(ModelResolver modelResolver) {
        Objects.requireNonNull(modelResolver, "modelResolver");
        return invokeModel(BedrockBackend.fromEnv(), modelResolver);
    }

    /** An InvokeModel-backed {@code LlmClient} pinned to {@code region}, with {@code modelResolver}. */
    public static AnthropicLlmClient invokeModel(Region region, ModelResolver modelResolver) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(modelResolver, "modelResolver");
        return invokeModel(BedrockBackend.builder().region(region).build(), modelResolver);
    }

    /** An InvokeModel-backed {@code LlmClient} over an explicitly-built {@code backend}. */
    public static AnthropicLlmClient invokeModel(BedrockBackend backend, ModelResolver modelResolver) {
        return new AnthropicLlmClient(client(backend), modelResolver);
    }
}
