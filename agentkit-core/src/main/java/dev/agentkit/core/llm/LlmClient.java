package dev.agentkit.core.llm;

/**
 * The single seam between AgentKit and a concrete LLM provider.
 *
 * <p>The core depends only on this interface, so the agent loop, tools, skills,
 * memory, and orchestration are all testable with a fake client and portable
 * across providers. Adapters (e.g. the Anthropic module) translate a
 * {@link LlmRequest} into their SDK's request, invoke the model, and normalise
 * the response into an {@link LlmResponse}.
 */
@FunctionalInterface
public interface LlmClient {

    /**
     * Executes a single model turn.
     *
     * <p>If the request carries an {@link LlmRequest#outputSchema()}, an adapter must
     * either constrain decoding to it or fail; returning unconstrained prose is not a
     * valid response to a schema-carrying request.
     *
     * @param request the request; never {@code null}
     * @return the normalised response; never {@code null}
     * @throws LlmException if the call fails
     */
    LlmResponse generate(LlmRequest request);

    /**
     * Executes a single model turn, streaming text output to {@code handler} as it
     * is produced. The returned {@link LlmResponse} is the same as
     * {@link #generate(LlmRequest)} would produce — the stream is an additional live
     * view, not a replacement for the final result.
     *
     * <p>The default implementation does not stream: it runs the blocking call and
     * emits the assembled assistant text as a single delta, so every client is
     * usable through this method. Adapters whose SDK supports server-sent streaming
     * (e.g. the Anthropic client) override it to deliver real incremental deltas.
     *
     * @param request the request; never {@code null}
     * @param handler receives text deltas; never {@code null}
     * @return the normalised response; never {@code null}
     * @throws LlmException if the call fails
     */
    default LlmResponse generate(LlmRequest request, StreamHandler handler) {
        java.util.Objects.requireNonNull(handler, "handler");
        LlmResponse response = generate(request);
        String text = response.message().text();
        if (!text.isEmpty()) {
            handler.onTextDelta(text);
        }
        return response;
    }
}
