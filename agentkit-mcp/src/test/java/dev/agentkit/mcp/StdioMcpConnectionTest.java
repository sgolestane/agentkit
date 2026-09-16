package dev.agentkit.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class StdioMcpConnectionTest {

    private static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    @Test
    void completesTheHandshakeThenListsAndCallsTools() {
        // Scripted server responses in id order: initialize(1), tools/list(2), tools/call(3).
        String script = lines(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\","
                        + "\"capabilities\":{},\"serverInfo\":{\"name\":\"t\",\"version\":\"1\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"echo\","
                        + "\"description\":\"echoes\",\"inputSchema\":{\"type\":\"object\"}}]}}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"hello\"}],\"isError\":false}}");
        StringWriter out = new StringWriter();
        StdioMcpConnection connection = new StdioMcpConnection(new StringReader(script), out, () -> { });

        List<McpToolInfo> tools = connection.listTools();
        assertThat(tools).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("echo");
            assertThat(t.description()).isEqualTo("echoes");
            assertThat(t.inputSchema()).containsEntry("type", "object");
        });

        McpCallResult result = connection.callTool("echo", Map.of("msg", "hi"));
        assertThat(result.text()).isEqualTo("hello");
        assertThat(result.isError()).isFalse();

        // The client sent initialize, the initialized notification, then the two calls.
        String sent = out.toString();
        assertThat(sent).contains("\"method\":\"initialize\"")
                .contains("\"method\":\"notifications/initialized\"")
                .contains("\"method\":\"tools/list\"")
                .contains("\"method\":\"tools/call\"")
                .contains("\"name\":\"echo\"");
    }

    @Test
    void callToolFlattensTextBlocksAndHonoursIsError() {
        // initialize(1) then tools/call(2) returning mixed content with isError=true.
        String script = lines(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":["
                        + "{\"type\":\"text\",\"text\":\"a\"},"
                        + "{\"type\":\"image\",\"data\":\"...\"},"
                        + "{\"type\":\"text\",\"text\":\"b\"}],\"isError\":true}}");
        StdioMcpConnection connection =
                new StdioMcpConnection(new StringReader(script), new StringWriter(), () -> { });

        McpCallResult result = connection.callTool("t", Map.of());

        assertThat(result.text()).isEqualTo("ab"); // text blocks concatenated, image ignored
        assertThat(result.isError()).isTrue();
    }

    @Test
    void aFailedHandshakeReleasesTheTransportInsteadOfLeakingIt() {
        // The server returns a JSON-RPC error to initialize; the constructor must
        // throw AND run the closer (in production: destroy the subprocess).
        String script = lines(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"boom\"}}");
        AtomicBoolean closed = new AtomicBoolean(false);

        assertThatThrownBy(() ->
                new StdioMcpConnection(new StringReader(script), new StringWriter(), () -> closed.set(true)))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("boom");
        assertThat(closed).as("transport released on handshake failure").isTrue();
    }

    /** An {@code Error} a test can throw without breaking the harness a real one would. */
    private static final class TestError extends Error {
        TestError(String message) {
            super(message);
        }
    }

    @Test
    void anErrorDuringTheHandshakeAlsoReleasesTheTransport() {
        // The same invariant as the test above, and the one the catch did not enforce
        // (#135). "A failed handshake must not leak the transport" was written as
        // catch (RuntimeException) while the thing being protected is a process: an Error
        // out of initialize() — an OutOfMemoryError while Jackson builds the request node is
        // the plausible one — left a live subprocess with no reference to it anywhere, for
        // the lifetime of the JVM.
        AtomicBoolean closed = new AtomicBoolean(false);
        Reader breaks = new Reader() {
            @Override
            public int read(char[] cbuf, int off, int len) {
                throw new TestError("the JVM had opinions");
            }

            @Override
            public void close() {
            }
        };

        assertThatThrownBy(() -> new StdioMcpConnection(breaks, new StringWriter(),
                () -> closed.set(true)))
                .as("the Error was rewrapped on its way out, so a caller with an Error branch"
                        + " would never see one")
                .isInstanceOf(TestError.class);
        assertThat(closed)
                .as("the subprocess leaked: the closer never ran for a handshake that threw"
                        + " outside RuntimeException")
                .isTrue();
    }

    @Test
    void aServerRequestWithACollidingIdIsSkippedNotMistakenForTheResponse() {
        // The server sends a ping (id 1, same as our initialize id) before its real
        // initialize result. The ping must be skipped so the stream stays in sync.
        String script = lines(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"echo\"}]}}");
        StdioMcpConnection connection =
                new StdioMcpConnection(new StringReader(script), new StringWriter(), () -> { });

        // If the ping had desynced the stream, listTools would read the wrong line.
        assertThat(connection.listTools()).singleElement()
                .satisfies(t -> assertThat(t.name()).isEqualTo("echo"));
    }

    @Test
    void aJsonRpcErrorOnToolsCallPropagatesAsMcpException() {
        String script = lines(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32602,\"message\":\"bad args\"}}");
        StdioMcpConnection connection =
                new StdioMcpConnection(new StringReader(script), new StringWriter(), () -> { });

        assertThatThrownBy(() -> connection.callTool("t", Map.of()))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("bad args");
    }

    @Test
    void closeInvokesTheTransportCloser() {
        String script = lines("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        AtomicBoolean closed = new AtomicBoolean(false);
        StdioMcpConnection connection =
                new StdioMcpConnection(new StringReader(script), new StringWriter(), () -> closed.set(true));

        connection.close();

        assertThat(closed).isTrue();
    }

    /**
     * A reader that yields {@code head} (the initialize line) then blocks every later
     * read until {@code pipeClosed} trips, at which point it behaves like a closed
     * pipe — modelling a hung server whose subprocess is later destroyed.
     */
    private static Reader blockingAfter(String head, CountDownLatch pipeClosed) {
        return new Reader() {
            private final StringReader headReader = new StringReader(head);
            private boolean headDone;

            @Override
            public int read(char[] cbuf, int off, int len) throws IOException {
                if (!headDone) {
                    int n = headReader.read(cbuf, off, len);
                    if (n != -1) {
                        return n;
                    }
                    headDone = true;
                }
                try {
                    pipeClosed.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                throw new IOException("pipe closed");
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    void closeFromAnotherThreadAbortsAHungCall() throws Exception {
        // initialize succeeds, then a tools/call blocks on a silent server. close()
        // must abort it: the closer (here, trip the "pipe") runs first and unblocks
        // the read, so the call fails fast instead of hanging forever.
        CountDownLatch pipeClosed = new CountDownLatch(1);
        Reader in = blockingAfter("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n", pipeClosed);
        StdioMcpConnection connection =
                new StdioMcpConnection(in, new StringWriter(), pipeClosed::countDown);

        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        try {
            Future<?> call = worker.submit(() -> connection.callTool("slow", Map.of()));
            Thread.sleep(100); // let the call reach the blocking read

            connection.close(); // aborts the hung call from this thread

            assertThatThrownBy(() -> call.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(McpException.class);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void closeIsIdempotent() {
        String script = lines("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        StdioMcpConnection connection =
                new StdioMcpConnection(new StringReader(script), new StringWriter(), () -> { });

        connection.close();
        connection.close(); // second close must not throw
    }
}
