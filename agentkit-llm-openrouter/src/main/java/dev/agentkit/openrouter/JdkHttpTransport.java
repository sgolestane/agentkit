package dev.agentkit.openrouter;

import dev.agentkit.core.llm.LlmException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The default {@link OpenRouterTransport}, backed by the JDK {@link HttpClient}.
 *
 * <p>Stateless and thread-safe: one shared client with a connect timeout, a per-request
 * timeout, and a stream idle timeout. Every call is bounded, whichever path it takes:
 *
 * <ul>
 *   <li>{@link #post} — the per-request timeout bounds the whole exchange, because the
 *       body is buffered before the call returns.</li>
 *   <li>{@link #postStreaming} — the per-request timeout bounds only the time to
 *       response headers. {@link HttpRequest.Builder#timeout} does <em>not</em> apply to
 *       a lazily-consumed body: measured against a server that sends headers, sends one
 *       line and then goes quiet, a 3-second request timeout left the consuming thread
 *       parked indefinitely. The streamed body is therefore bounded separately, by the
 *       idle timeout below.</li>
 * </ul>
 *
 * <p><strong>Stream idle timeout.</strong> The line stream handed back by
 * {@link #postStreaming} gives up after {@link #DEFAULT_STREAM_IDLE_TIMEOUT} without a
 * line, raising an {@link LlmException} on the consuming thread, so a provider that
 * stalls mid-stream ends as a reported failure a {@code RetryingLlmClient} can act on
 * rather than a parked thread. The clock restarts on every line, so a legitimately long
 * generation is never cut short — only silence is bounded. That is deliberately looser
 * than {@link #post}'s bound on the whole exchange: any stream that would have succeeded
 * non-streaming still succeeds, since 120 seconds of silence is strictly more permissive
 * than 120 seconds in total.
 *
 * <p>A non-2xx status is returned as data (not thrown) so the higher layer maps provider
 * errors uniformly; only genuine transport failures raise {@link LlmException}.
 *
 * <p>Prior to this class carrying an idle timeout, its javadoc read: <em>"the streamed
 * body is then consumed lazily with no idle timeout, so a server that stalls mid-stream
 * is not interrupted; abort such a call by closing the {@code StreamResult} from another
 * thread."</em> That was accurate but unreachable — {@link OpenRouterLlmClient#generate}
 * consumes the {@code StreamResult} in try-with-resources and never lets it escape, so no
 * caller going through {@code LlmClient} held a handle to close. Closing it from another
 * thread is exactly what the watchdog below now does, on the caller's behalf.
 */
final class JdkHttpTransport implements OpenRouterTransport {

    // Declared before INSTANCE: a static field is initialized in source order, and
    // INSTANCE's constructor reads these, so they must already hold their values.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    /**
     * How long {@link #postStreaming}'s line stream waits for the next line before
     * giving up.
     *
     * <p><strong>Deliberately its own constant, not an alias for
     * {@link #REQUEST_TIMEOUT}.</strong> The two are different quantities that currently
     * share a number: a request timeout bounds a whole call, while this bounds a single
     * gap between lines, and a stream that runs for an hour delivering a token a second
     * is fine by this bound and would blow through the other one. They were written as
     * one expression at first, which would have meant the next person tuning either
     * silently moved both. Chosen to equal the request timeout so the streaming and
     * non-streaming paths give the same answer to "how long may the provider go quiet?",
     * and safe as an equality because bounding silence is strictly more permissive than
     * bounding the whole exchange — but change one without the other freely.
     *
     * <p>Pinned by {@code StalledStreamIsBoundedTest}, because the tests that exercise
     * the mechanism inject their own short deadline and would not notice this value
     * being changed to a day or to zero.
     */
    static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(120);

    /**
     * Fires the idle deadlines. One shared daemon thread: a deadline that expires only
     * closes a response body stream, which does not block, so streams cannot delay each
     * other's alarms in any meaningful way. Cancelled alarms are removed from the queue
     * rather than left to expire, so a long stream's per-line arming does not accumulate.
     */
    private static final ScheduledExecutorService WATCHDOG = newWatchdog();

    static final JdkHttpTransport INSTANCE = new JdkHttpTransport(DEFAULT_STREAM_IDLE_TIMEOUT);

    /**
     * The shared client, deliberately never closed.
     *
     * <p>{@link HttpClient} became {@link AutoCloseable} in Java 21, which reads as an
     * invitation to close it — #294 proposes exactly that, as a test fixture that owns a
     * client and closes it. Measured on JDK 21.0.10, that is the one thing that must not
     * be done here: {@code close()} runs {@code shutdown()} and then joins the selector
     * manager thread, so with a streamed body still in flight it <em>never returns</em>.
     * A surefire fork parked in {@code HttpClient.close()} was still parked when the run
     * was abandoned three and a half minutes later, and it reported no per-class
     * {@code Tests run:} line — only {@code Tests run: 0} for the whole module.
     *
     * <p>Not closing it costs nothing that matters. Also measured on the same JDK: every
     * thread this client starts — {@code HttpClient-N-SelectorManager} and each
     * {@code HttpClient-N-Worker} — is a <strong>daemon</strong> thread, so an operation
     * left in flight does not hold the JVM open. A process that left a stalled stream
     * running and returned from {@code main} exited normally in 2.3 seconds, and the same
     * shape inside a surefire fork gave {@code Tests run: 1} and {@code BUILD SUCCESS}.
     * The client is unreachable once this transport is, and the JDK's own cleaner
     * releases it.
     *
     * <p>If a bounded release is ever wanted, {@code shutdownNow()} is the method that
     * returns; {@code close()} is not.
     */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private final Duration streamIdleTimeout;

    JdkHttpTransport(Duration streamIdleTimeout) {
        Objects.requireNonNull(streamIdleTimeout, "streamIdleTimeout");
        if (streamIdleTimeout.isNegative() || streamIdleTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "streamIdleTimeout must be positive, was " + streamIdleTimeout);
        }
        this.streamIdleTimeout = streamIdleTimeout;
    }

    private static ScheduledExecutorService newWatchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "agentkit-openrouter-stream-idle");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Override
    public HttpResult post(String url, Map<String, String> headers, String jsonBody) {
        try {
            HttpResponse<String> response = http.send(
                    request(url, headers, jsonBody), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpResult(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new LlmException("OpenRouter HTTP request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("OpenRouter HTTP request was interrupted", e);
        }
    }

    @Override
    public StreamResult postStreaming(String url, Map<String, String> headers, String jsonBody) {
        try {
            // ofLines() yields body lines lazily; send() returns once headers arrive,
            // so the status is known before the (streamed) body is consumed.
            HttpResponse<Stream<String>> response = http.send(
                    request(url, headers, jsonBody), HttpResponse.BodyHandlers.ofLines());
            return new StreamResult(response.statusCode(), idleBounded(response.body(), streamIdleTimeout));
        } catch (IOException e) {
            throw new LlmException("OpenRouter streaming request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("OpenRouter streaming request was interrupted", e);
        }
    }

    /**
     * Wraps {@code source} so that no single wait for the next line exceeds
     * {@code idleTimeout}. Package-private so a test can drive it with a short deadline
     * against a deliberately stalling server.
     */
    static Stream<String> idleBounded(Stream<String> source, Duration idleTimeout) {
        Iterator<String> lines = source.iterator();
        IdleDeadline deadline = new IdleDeadline(source, idleTimeout);
        Iterator<String> guarded = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return deadline.bound(lines::hasNext);
            }

            @Override
            public String next() {
                return deadline.bound(lines::next);
            }
        };
        // ORDERED, unknown size: exactly what ofLines() itself reports.
        return StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(guarded, Spliterator.ORDERED), false)
                .onClose(source::close);
    }

    /**
     * Arms a watchdog around each blocking read of the underlying line stream. When it
     * expires it closes that stream, which is what unblocks a consumer parked inside
     * {@code hasNext()}; the consumer then sees the resulting {@code IOException} and,
     * because {@code expired} was published before the close, reports it as a timeout.
     */
    private static final class IdleDeadline {

        private final Stream<String> source;
        private final Duration idleTimeout;
        private volatile boolean expired;

        IdleDeadline(Stream<String> source, Duration idleTimeout) {
            this.source = source;
            this.idleTimeout = idleTimeout;
        }

        <T> T bound(Supplier<T> blockingRead) {
            if (expired) {
                // Load-bearing, not belt-and-braces. Measured against the JDK's line
                // stream: after the body is closed, a fresh hasNext() returns *true*
                // rather than throwing. So without this, a caller that caught the
                // timeout and kept iterating would be handed a dead stream still
                // claiming to have more. Once the deadline has fired the stream is
                // over, and every later read says so.
                throw timedOut(null);
            }
            ScheduledFuture<?> alarm = WATCHDOG.schedule(
                    this::expire, idleTimeout.toNanos(), TimeUnit.NANOSECONDS);
            try {
                return blockingRead.get();
            } catch (NoSuchElementException e) {
                throw e;
            } catch (RuntimeException e) {
                // The close performed by expire() surfaces here as an IOException wrapped
                // by the JDK's line stream. Anything else is a real transport failure and
                // keeps its own identity.
                throw expired ? timedOut(e) : e;
            } finally {
                // cancel(false) so an alarm already running is allowed to finish; the
                // expired flag, not the cancel, is what decides how a failure is reported.
                alarm.cancel(false);
            }
        }

        private void expire() {
            // Published before the close so a consumer woken by the close is guaranteed
            // to see it: the volatile write happens-before the close, which happens-before
            // the exception the consumer observes.
            expired = true;
            source.close();
        }

        private LlmException timedOut(RuntimeException cause) {
            String message = "OpenRouter stream stalled: no data for " + idleTimeout
                    + ". The provider sent response headers and then went quiet; "
                    + "the stream was closed so the call fails rather than hanging.";
            return cause == null ? new LlmException(message) : new LlmException(message, cause);
        }
    }

    private static HttpRequest request(String url, Map<String, String> headers, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return builder.build();
    }
}
