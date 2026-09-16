package dev.agentkit.chat.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.ChatUnavailable;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.chat.store.InMemoryChatStore;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Closing the console ends the work it was doing, rather than orphaning it.
 *
 * <h2>The defect this was written for</h2>
 *
 * <p>{@code close()} called {@code HttpServer.stop(0)} and stopped. {@code HttpServer} does not
 * shut down an executor it was handed — reasonably, since it cannot know whether the caller is
 * still using it — and this one built its own and then let go of the reference, so nothing
 * could ever shut it down.
 *
 * <p>For a request that answers and returns, that is a tidiness problem. For an event stream it
 * is not: that handler is a thread parked inside {@code streamEvents} holding a subscriber on
 * the runtime's event bus, and {@code stop} does not wake it. It closes the socket, and the
 * loop finds out on its next write — which for an idle stream is a keep-alive away, twenty
 * seconds. In between, a handler belonging to a closed server is reading a store its owner is
 * about to close, because {@code close()} on the runtime is the very next line every caller
 * writes.
 *
 * <p>That is the shape of #397: an intermittent in a class where each test starts a server,
 * closes it, and the next test starts another. It is not proof of the cause — that failure was
 * seen once and never reproduced — and this test does not claim to be. It closes a hole that is
 * demonstrably there, which is worth doing whether or not it was the one.
 *
 * <h2>Threads by name, and why that is the honest measurement</h2>
 *
 * <p>Counting a pool's own state would pass the moment {@code shutdownNow} was called, which is
 * the thing being tested rather than its effect. Asking the JVM which threads exist measures
 * what actually happened to them, and the factory names them for exactly this.
 */
class AClosedConsoleStopsTheThreadsItStartedTest {

    private final ChatStore store = new InMemoryChatStore();
    private final ChatEvents events = new ChatEvents();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static final ChatRuntime.Agents NO_MODEL = session -> {
        throw new ChatUnavailable("No model is configured.");
    };

    /** The live threads the console's factory named, right now. */
    private static Set<Thread> handlers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread.getName().equals("chat-http"))
                .collect(Collectors.toSet());
    }

    @Test
    void anOpenEventStreamDoesNotOutliveTheServerServingIt() throws Exception {
        Set<Thread> before = handlers();
        ChatRuntime runtime = new ChatRuntime(store, events, NO_MODEL);
        Conversation conversation = store.create("acme", "");
        ChatServer server = new ChatServer(0, runtime, "acme", Map::of);
        server.start();

        // Held open, not read to the end — a browser tab with the console on screen. The
        // stream never completes on its own, which is the whole point of it.
        HttpResponse<InputStream> stream = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.port()
                        + "/api/conversations/" + conversation.id() + "/events")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);
        // Read the stream's opening comment, so this is not racing the handler's own start.
        assertThat(stream.body().read()).isNotEqualTo(-1);

        assertThat(handlers()).as("no handler is serving the stream that was just opened")
                .hasSizeGreaterThan(before.size());

        server.close();

        // Immediately after close, not eventually: close() waits for them, so a caller's very
        // next line — runtime.close() — is safe. Polling here would test a weaker promise.
        assertThat(handlers()).as("a handler outlived the server that owned it")
                .isSubsetOf(before);

        runtime.close();
    }

    @Test
    void closingTwiceIsNotAnError() throws Exception {
        // Because AutoCloseable says so and because every caller in this repository closes in
        // a finally or a try-with-resources that may already have closed.
        ChatRuntime runtime = new ChatRuntime(store, events, NO_MODEL);
        ChatServer server = new ChatServer(0, runtime, "acme", Map::of);
        server.start();
        server.close();
        server.close();
        runtime.close();
    }

    @Test
    void twoConsolesInOneProcessDoNotShareABuildStamp() throws Exception {
        // The stamp is what tells a tab its server was replaced. Two servers are two of them
        // as far as a tab is concerned, whether or not they are two processes.
        ChatRuntime runtime = new ChatRuntime(store, events, NO_MODEL);
        try (ChatServer one = new ChatServer(0, runtime, "acme", Map::of);
                ChatServer other = new ChatServer(0, runtime, "acme", Map::of)) {
            assertThat(one.buildStamp()).isNotEqualTo(other.buildStamp()).isNotBlank();
        }
        runtime.close();
    }
}
