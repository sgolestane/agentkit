package dev.agentkit.openrouter;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The HTTP seam under {@link OpenRouterLlmClient}: a POST of a JSON body, returning
 * either the whole response ({@link #post}) or a stream of server-sent-event lines
 * ({@link #postStreaming}). Keeping it an interface lets the client be unit-tested
 * end-to-end against a scripted transport with no network, and lets a caller swap in
 * a different HTTP stack, proxy, or retry wrapper.
 */
@FunctionalInterface
public interface OpenRouterTransport {

    /**
     * POSTs {@code jsonBody} to {@code url} with {@code headers} and returns the raw
     * HTTP status and response body. Implementations must not throw on a non-2xx
     * status — the status is returned so the client maps provider errors uniformly;
     * they should throw {@link dev.agentkit.core.llm.LlmException} only for a genuine
     * transport failure (connection refused, timeout, interruption).
     */
    HttpResult post(String url, Map<String, String> headers, String jsonBody);

    /**
     * POSTs {@code jsonBody} and returns the status plus a lazy {@link Stream} of the
     * response's lines, for consuming a {@code text/event-stream} (SSE) reply. The
     * caller must {@link StreamResult#close() close} the result to release the
     * connection. The default implementation throws
     * {@link UnsupportedOperationException}; {@link OpenRouterLlmClient} then falls
     * back to the non-streaming path, so a transport need only implement {@link #post}.
     *
     * <p><strong>Implementations must bound how long the returned stream will wait for
     * a line.</strong> {@link OpenRouterLlmClient} consumes the {@link StreamResult}
     * inside {@code generate} and never lets it escape, so no caller holds a handle to
     * close from another thread; a stream that waits forever therefore parks the agent
     * thread with no {@code AgentResult} and nothing for a retry wrapper to catch. Give
     * up after an idle period and throw {@link dev.agentkit.core.llm.LlmException}, as
     * {@link #jdk()} does. Bounding <em>silence</em> rather than total duration is what
     * keeps a legitimately long generation alive.
     */
    default StreamResult postStreaming(String url, Map<String, String> headers, String jsonBody) {
        throw new UnsupportedOperationException("This transport does not support streaming");
    }

    /**
     * A raw HTTP response.
     *
     * @param statusCode the HTTP status code
     * @param body       the response body; never {@code null} (empty if none)
     */
    record HttpResult(int statusCode, String body) {
        public HttpResult {
            Objects.requireNonNull(body, "body");
        }
    }

    /**
     * A streaming HTTP response: a status and a lazy stream of body lines. Closing it
     * closes the underlying line stream (and its connection).
     *
     * @param statusCode the HTTP status code
     * @param lines      the response body as a lazy line stream; never {@code null}
     */
    record StreamResult(int statusCode, Stream<String> lines) implements AutoCloseable {
        public StreamResult {
            Objects.requireNonNull(lines, "lines");
        }

        @Override
        public void close() {
            lines.close();
        }
    }

    /**
     * The default transport, backed by the JDK {@link java.net.http.HttpClient}: a
     * 30-second connect timeout, a 120-second request timeout, and a 120-second idle
     * timeout on a streamed body.
     */
    static OpenRouterTransport jdk() {
        return JdkHttpTransport.INSTANCE;
    }

    /**
     * As {@link #jdk()}, but giving up on a streamed body after {@code streamIdleTimeout}
     * without a line instead of the default 120 seconds. The clock restarts on every
     * line, so this bounds how long the provider may go quiet, not how long the whole
     * generation may take. Each call builds its own {@link java.net.http.HttpClient};
     * prefer {@link #jdk()} unless you need a different deadline.
     *
     * @param streamIdleTimeout how long to wait for the next line; must be positive
     */
    static OpenRouterTransport jdk(java.time.Duration streamIdleTimeout) {
        return new JdkHttpTransport(streamIdleTimeout);
    }
}
