package dev.agentkit.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.llm.LlmException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * #282: turning streaming on used to remove the adapter's only timeout. A provider that
 * sends response headers and then goes quiet parked the agent thread forever, because
 * {@code HttpRequest.timeout} does not apply to a lazily-consumed body.
 *
 * <p>These tests run against a real socket rather than a scripted transport, because the
 * thing under test is what the JDK's HTTP client does when the peer stops speaking —
 * which no in-memory fake reproduces.
 *
 * <p>Every test here carries a hard {@link Timeout}, which is not ceremony in a class
 * about hangs: a test that hangs reports nothing, and the failure mode under test is
 * precisely a thread that never returns. Mutation testing found this the direct way —
 * two mutants that stop the deadline releasing the stream wedged the whole surefire JVM
 * instead of failing, and a wedged build is not a result. The ceiling is far above the
 * {@link #PATIENCE} these tests actually wait, so it never fires on a healthy run.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StalledStreamIsBoundedTest {

    /** How long a test waits for the transport's own (much shorter) deadline to fire. */
    private static final Duration PATIENCE = Duration.ofSeconds(15);

    private static final Duration IDLE_TIMEOUT = Duration.ofMillis(300);

    private ScriptedServer server;

    /** Every consumer thread this test started, so teardown can prove none is parked. */
    private final List<Thread> consumers = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = new ScriptedServer();
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        // Checked BEFORE the server is torn down, and the order is the whole point.
        // A consumer still parked in a body read means an HttpClient operation is still
        // in flight, and such an operation keeps a NON-DAEMON selector thread alive that
        // can wedge the whole surefire JVM — a failure that surfaces as an unrelated
        // forked-VM crash in whatever module runs next. Closing the server first would
        // release every parked reader as a side effect and make this assertion pass no
        // matter what, which is how it was written first: it has to prove the transport's
        // own deadline let go of the reader, not that the peer went away.
        try {
            for (Thread consumer : consumers) {
                consumer.join(PATIENCE.toMillis());
                assertThat(consumer.isAlive())
                        .as("Consumer thread '%s' is still parked with the server still up, "
                                + "so nothing in the transport released it and an HttpClient "
                                + "operation is still in flight", consumer.getName())
                        .isFalse();
            }
        } finally {
            server.close();
        }
    }

    @Test
    void aProviderThatSendsHeadersThenGoesQuietFailsInsteadOfHanging() throws Exception {
        server.sendLinesThenStall(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}"));

        Outcome outcome = consumeOnItsOwnThread(transport());

        assertThat(outcome.finished())
                .as("A stalled stream must end as a reported failure the caller receives. "
                        + "The consuming thread was still parked %s after the %s idle deadline "
                        + "should have fired, which is the hang #282 describes: no AgentResult, "
                        + "no onFinish, and nothing for a retry wrapper to catch.",
                        PATIENCE, IDLE_TIMEOUT)
                .isTrue();
        assertThat(outcome.failure())
                .as("The stall must surface as an LlmException, since that is the type "
                        + "RetryingLlmClient acts on")
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("stalled")
                .hasMessageContaining("no data for");
        assertThat(outcome.linesRead())
                .as("The lines that did arrive before the stall must still have been delivered")
                .isEqualTo(1);
    }

    @Test
    void aStreamThatStallsBeforeSendingAnythingAlsoFails() throws Exception {
        server.sendLinesThenStall(List.of());

        Outcome outcome = consumeOnItsOwnThread(transport());

        assertThat(outcome.finished())
                .as("A provider that sends headers and then no body at all must fail too")
                .isTrue();
        assertThat(outcome.failure()).isInstanceOf(LlmException.class);
        assertThat(outcome.linesRead()).isZero();
    }

    @Test
    void aStreamSlowerThanTheDeadlineButNeverIdleThatLongSucceeds() throws Exception {
        // Six lines, each arriving after a delay that is a large fraction of the idle
        // deadline: total elapsed comfortably exceeds the deadline, but no single gap
        // does. The bound is on silence, not on total duration — a positive control that
        // a fix which bounded the whole stream would fail.
        server.sendLinesSlowly(
                List.of("data: a", "data: b", "data: c", "data: d", "data: e", "data: [DONE]"),
                IDLE_TIMEOUT.dividedBy(2));

        Outcome outcome = consumeOnItsOwnThread(transport());

        assertThat(outcome.finished()).isTrue();
        assertThat(outcome.failure())
                .as("No gap between lines reached the idle deadline, so nothing should have failed")
                .isNull();
        assertThat(outcome.linesRead()).isEqualTo(6);
        assertThat(outcome.elapsed())
                .as("The stream must genuinely have outlived the idle deadline overall, "
                        + "or this control proves nothing")
                .isGreaterThan(IDLE_TIMEOUT);
    }

    @Test
    void aStreamThatCompletesNormallyIsUntouched() throws Exception {
        server.sendLinesThenClose(List.of("data: one", "data: two", "data: [DONE]"));

        Outcome outcome = consumeOnItsOwnThread(transport());

        assertThat(outcome.finished()).isTrue();
        assertThat(outcome.failure()).isNull();
        assertThat(outcome.linesRead()).isEqualTo(3);
    }

    // --- the wrapper itself, without a socket ---------------------------------
    //
    // Two properties of idleBounded are invisible through the socket tests above:
    // whether closing the wrapper releases the stream underneath it, and what a second
    // read after the deadline has fired reports. Both are driven here against a source
    // that mimics the behaviour measured from the JDK's line stream — a blocked read
    // wakes with an UncheckedIOException when the stream is closed.

    @Test
    void closingTheBoundedStreamClosesTheStreamUnderneathIt() {
        BlockingSource source = new BlockingSource();

        try (Stream<String> bounded = JdkHttpTransport.idleBounded(source.stream(), Duration.ofHours(1))) {
            assertThat(source.closed()).isFalse();
        }

        assertThat(source.closed())
                .as("The bounded stream must forward close() to the response body, or the "
                        + "HTTP connection is never released and the caller's "
                        + "try-with-resources silently does nothing")
                .isTrue();
    }

    @Test
    void aReadAfterTheDeadlineHasFiredStillReportsTheTimeout() {
        BlockingSource source = new BlockingSource();
        Stream<String> bounded = JdkHttpTransport.idleBounded(source.stream(), IDLE_TIMEOUT);
        java.util.Iterator<String> lines = bounded.iterator();

        assertThatThrownBy(lines::hasNext)
                .as("the first read past the deadline")
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("stalled");

        assertThatThrownBy(lines::hasNext)
                .as("A second read must report the same timeout. Measured against the "
                        + "JDK's line stream, a fresh hasNext() on a closed body returns "
                        + "true rather than throwing, so without the guard a caller that "
                        + "caught the timeout and kept iterating is handed a dead stream "
                        + "that claims to have more.")
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("stalled");
    }

    /**
     * A line source that reproduces the two behaviours measured from the JDK's own line
     * stream, rather than a guess at them:
     *
     * <ul>
     *   <li>a read that is <em>already blocked</em> when the stream is closed wakes with
     *       an {@link java.io.UncheckedIOException};</li>
     *   <li>a read that <em>starts after</em> the stream is closed returns {@code true} —
     *       it does not throw and does not block.</li>
     * </ul>
     *
     * <p>The second is the surprising one, and it is why the guard being pinned here
     * exists. No sleeps anywhere: the close is the signal.
     */
    private static final class BlockingSource {

        private final CountDownLatch closed = new CountDownLatch(1);

        boolean closed() {
            return closed.getCount() == 0;
        }

        Stream<String> stream() {
            java.util.Iterator<String> blocking = new java.util.Iterator<>() {
                @Override
                public boolean hasNext() {
                    if (closed()) {
                        // Measured: a fresh read on a closed line stream claims more data.
                        return true;
                    }
                    try {
                        closed.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.UncheckedIOException(new IOException("interrupted"));
                    }
                    throw new java.io.UncheckedIOException(new IOException("closed"));
                }

                @Override
                public String next() {
                    return "stale";
                }
            };
            return java.util.stream.StreamSupport.stream(
                            java.util.Spliterators.spliteratorUnknownSize(blocking, 0), false)
                    .onClose(closed::countDown);
        }
    }

    // --- the value a user actually gets ---------------------------------------
    //
    // Everything above injects a 300ms deadline, so it exercises the mechanism and never
    // the shipped default. Setting DEFAULT_STREAM_IDLE_TIMEOUT to a day left all of it
    // green in 2.3 seconds. These three pin the default itself.
    //
    // Driving a real stall through the default-constructed transport would mean waiting
    // out the real 120-second deadline, which is not worth two minutes on every build.
    // So the value is pinned exactly, the wiring from constant to shipped instance is
    // pinned by reflection, and the one direction that *can* be checked cheaply against
    // a real socket — that the default does not fire on a merely slow stream — is.

    @Test
    void theShippedDefaultIsTheValueTheJavadocPromises() {
        assertThat(JdkHttpTransport.DEFAULT_STREAM_IDLE_TIMEOUT)
                .as("The class javadoc, OpenRouterLlmClient's javadoc and "
                        + "OpenRouterTransport.jdk() all tell the reader 120 seconds. A "
                        + "default of a day is not a bound and a default of zero fails "
                        + "every call; neither would be noticed by the tests above, which "
                        + "inject their own deadline. Changing the shipped default is "
                        + "fine — change it here and in those three javadocs together.")
                .isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void theDefaultTransportIsWiredToTheShippedDefault() throws Exception {
        OpenRouterTransport shipped = OpenRouterTransport.jdk();

        Field field = JdkHttpTransport.class.getDeclaredField("streamIdleTimeout");
        field.setAccessible(true);

        assertThat(field.get(shipped))
                .as("OpenRouterTransport.jdk() is what OpenRouterLlmClient uses by "
                        + "default, so its idle bound is the one real callers get. A "
                        + "constant nothing is constructed from bounds nothing.")
                .isEqualTo(JdkHttpTransport.DEFAULT_STREAM_IDLE_TIMEOUT);
    }

    @Test
    void theDefaultTransportDoesNotGiveUpOnAStreamThatIsMerelySlow() throws Exception {
        // Through the real default-constructed transport and a real socket: a gap far
        // longer than the injected deadline the other tests use, but far shorter than the
        // shipped one. Fails if the default is ever set to zero or to something small
        // enough to cut off a working provider.
        server.sendLinesSlowly(List.of("data: a", "data: [DONE]"), Duration.ofSeconds(1));

        Outcome outcome = consumeOnItsOwnThread(OpenRouterTransport.jdk());

        assertThat(outcome.finished()).isTrue();
        assertThat(outcome.failure())
                .as("A one-second gap is nowhere near the shipped %s deadline, so the "
                        + "default transport must have read straight through it",
                        JdkHttpTransport.DEFAULT_STREAM_IDLE_TIMEOUT)
                .isNull();
        assertThat(outcome.linesRead()).isEqualTo(2);
    }

    private OpenRouterTransport transport() {
        return OpenRouterTransport.jdk(IDLE_TIMEOUT);
    }

    /**
     * Consumes the stream on a separate daemon thread and reports whether that thread
     * finished. A hang is otherwise indistinguishable from a slow pass, and a test that
     * hangs reports nothing at all.
     */
    private Outcome consumeOnItsOwnThread(OpenRouterTransport transport) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Integer> lines = new AtomicReference<>(0);
        AtomicReference<Duration> elapsed = new AtomicReference<>(Duration.ZERO);

        Thread consumer = new Thread(() -> {
            long start = System.nanoTime();
            int count = 0;
            try (OpenRouterTransport.StreamResult stream =
                    transport.postStreaming(server.url(), Map.of(), "{}")) {
                for (String ignored : (Iterable<String>) stream.lines()::iterator) {
                    count++;
                    lines.set(count);
                }
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                lines.set(count);
                elapsed.set(Duration.ofNanos(System.nanoTime() - start));
                done.countDown();
            }
        }, "stalled-stream-consumer");
        consumer.setDaemon(true);
        consumers.add(consumer);
        consumer.start();

        boolean finished = done.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            // The verdict is already captured, so this changes no assertion. It exists so
            // that a run in which the deadline does NOT fire still lets the JVM exit: an
            // HttpClient with an operation in flight keeps a non-daemon selector thread
            // alive, so a consumer left parked here wedges surefire rather than failing
            // it — and a test that hangs reports nothing. Interrupting is what unwedges
            // it (measured: it unblocks a stalled ofLines() body, 25 attempts of 25).
            consumer.interrupt();
            consumer.join(PATIENCE.toMillis());
        }
        return new Outcome(finished, failure.get(), lines.get(), elapsed.get());
    }

    private record Outcome(boolean finished, Throwable failure, int linesRead, Duration elapsed) {
    }

    /** A minimal HTTP server that can stop speaking mid-body and stay connected. */
    private static final class ScriptedServer implements AutoCloseable {

        private final ServerSocket socket;
        private final CountDownLatch shutdown = new CountDownLatch(1);
        private final List<Thread> threads = new ArrayList<>();

        ScriptedServer() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        }

        String url() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/chat/completions";
        }

        void sendLinesThenStall(List<String> lines) {
            serve(lines, Duration.ZERO, false);
        }

        void sendLinesSlowly(List<String> lines, Duration gap) {
            serve(lines, gap, true);
        }

        void sendLinesThenClose(List<String> lines) {
            serve(lines, Duration.ZERO, true);
        }

        private void serve(List<String> lines, Duration gap, boolean closeAtEnd) {
            Thread acceptor = new Thread(() -> {
                try (Socket client = socket.accept()) {
                    readRequest(client);
                    OutputStream out = client.getOutputStream();
                    write(out, "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/event-stream\r\n"
                            + "Transfer-Encoding: chunked\r\n\r\n");
                    for (String line : lines) {
                        if (!gap.isZero()) {
                            // Not hiding a race: this is the server deliberately being
                            // slow, which is the condition under test.
                            Thread.sleep(gap.toMillis());
                        }
                        String chunk = line + "\n";
                        byte[] bytes = chunk.getBytes(StandardCharsets.UTF_8);
                        write(out, Integer.toHexString(bytes.length) + "\r\n" + chunk + "\r\n");
                    }
                    if (closeAtEnd) {
                        write(out, "0\r\n\r\n");
                    } else {
                        // Go quiet holding the connection open — the #282 shape — until
                        // the test tears the server down.
                        shutdown.await();
                    }
                } catch (IOException | InterruptedException ignored) {
                    // Teardown, or the client hung up first: either way there is nothing
                    // left to serve.
                }
            }, "scripted-openrouter-server");
            acceptor.setDaemon(true);
            threads.add(acceptor);
            acceptor.start();
        }

        private static void readRequest(Socket client) throws IOException {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            int contentLength = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            for (int i = 0; i < contentLength; i++) {
                if (in.read() < 0) {
                    break;
                }
            }
        }

        private static void write(OutputStream out, String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public void close() {
            shutdown.countDown();
            threads.forEach(Thread::interrupt);
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }
}
