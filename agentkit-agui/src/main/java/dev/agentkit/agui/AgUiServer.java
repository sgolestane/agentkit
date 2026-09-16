package dev.agentkit.agui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agentkit.chat.ChatEvent;
import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.Quoted;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One endpoint, spoken as AG-UI, so a frontend built for that protocol can drive this runtime.
 *
 * <p>AG-UI's client side — {@code HttpAgent} in its SDK, and the CopilotKit and assistant-ui
 * components on top of it — posts a {@code RunAgentInput} and reads Server-Sent Events back.
 * That is the whole contract, and it is small enough that meeting it is a translation rather
 * than an adoption: {@link AgUiEvents} turns this runtime's stream into theirs and this puts a
 * socket in front of it.
 *
 * <h2>Beside the native console, not instead of it</h2>
 *
 * <p>Nothing here replaces {@code ChatServer}. The two speak to the same {@link ChatRuntime}
 * over the same {@link ChatEvents}, and a deployment can run both — its own console on one
 * port and somebody's CopilotKit app on another, against one set of conversations. That is the
 * shape the card asked for and it is also the only shape that lets the question "is AG-UI the
 * better native contract" be answered by evidence rather than by argument.
 *
 * <h2>What is deliberately missing</h2>
 *
 * <p><strong>Frontend tools.</strong> {@code RunAgentInput} carries {@code tools} — tools the
 * <em>frontend</em> offers, which the agent may call and the frontend executes. It is AG-UI's
 * answer to human-in-the-loop, and this ignores them: a deployment's tools are decided by its
 * {@code ChatRuntime.Agents}, and letting a request add to them would let whoever can reach
 * this port widen what the agent may do. The right way in is the deployment registering them
 * itself, and until somebody needs it the honest thing is to not silently drop them but to say
 * so — which is what {@code ignoredFrontendTools} on the run's result does.
 *
 * <p><strong>State.</strong> {@code state}, {@code STATE_SNAPSHOT} and {@code STATE_DELTA} are
 * AG-UI's shared-state feature. This runtime has no such concept and inventing one to fill the
 * field would be inventing a feature.
 */
public final class AgUiServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AgUiServer.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** How long a request body may be. A run's input is messages, not a payload. */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    /** How long the stream waits with nothing to say before it sends a keep-alive comment. */
    private static final Duration KEEP_ALIVE = Duration.ofSeconds(15);

    private final HttpServer server;
    private final ChatRuntime runtime;
    private final ChatStore store;
    private final ChatEvents events;
    private final String tenantId;

    /** Somebody else's thread ids, matched to ours. See {@link #conversationFor}. */
    private final Map<String, String> threads = new java.util.concurrent.ConcurrentHashMap<>();

    public AgUiServer(int port, ChatRuntime runtime, String tenantId) throws IOException {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.store = runtime.store();
        this.events = runtime.events();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        // Cached, for ChatServer's reason: every open stream holds a thread for as long as the
        // client is connected, and a fixed pool stops answering once it is full of streams.
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "agui-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/agent", this::run);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // --- one run ----------------------------------------------------------------------

    private void run(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "This endpoint takes a POST of a RunAgentInput."));
            return;
        }
        Map<String, Object> input;
        try {
            input = readInput(exchange);
        } catch (IOException notJson) {
            send(exchange, 400, Map.of("error", "That body is not a RunAgentInput: "
                    + Cut.to(String.valueOf(notJson.getMessage()), 200)));
            return;
        }

        String threadId = text(input, "threadId");
        String said = lastUserMessage(input);
        if (said.isEmpty()) {
            send(exchange, 400, Map.of("error",
                    "A run needs at least one user message in 'messages'."));
            return;
        }

        Conversation conversation = conversationFor(threadId);

        // The caller's thread id goes back out, not ours. A threadId names the CLIENT's
        // conversation — it is the id they will send again on the next turn — and answering
        // with our internal conversation id told them their run belonged to a thread they had
        // never heard of. A client that files runs under the id on RUN_STARTED would open a
        // second thread beside the one it is showing.
        //
        // Not a measured break: @ag-ui/client never compares the two, so nothing here caught
        // it and no frontend built on that SDK would misbehave today. It is wrong on its face
        // and it costs a line, which is a better trade than waiting for a client that does
        // compare.
        //
        // Blank is the exception, and the only case where our id is the right answer: a caller
        // that named no thread is being given one, and needs to be told which.
        AgUiEvents.Stream translation = new AgUiEvents.Stream(
                threadId.isBlank() ? conversation.id() : threadId);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.getResponseHeaders().add("X-Accel-Buffering", "no");
        exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, 0);

        // Subscribed BEFORE the turn is started, or a fast first event is one nobody sees.
        // Then filtered to THIS turn, which is what makes that safe: an AG-UI client is
        // starting a run rather than resuming a view, so it wants what this run produces and
        // nothing before it.
        //
        // Filtered by turn id rather than started from a cursor at the current end, and the
        // difference is a race worth naming. A cursor read before subscribing can be stale by
        // one event, and the one event most likely to be sitting there is a previous turn's
        // TURN_FINISHED — which translates to RUN_FINISHED and would end this client's stream
        // before its own run had said anything. An id cannot be stale.
        try (ChatEvents.Subscriber subscriber = events.subscribe(conversation.id(), 0);
                OutputStream out = exchange.getResponseBody()) {
            String runId = runtime.say(tenantId, conversation.id(), said, List.of()).id();
            while (subscriber.active()) {
                ChatEvent event = subscriber.poll(KEEP_ALIVE);
                if (event == null) {
                    write(out, ": keep-alive\n\n");
                    continue;
                }
                if (!runId.equals(event.turnId())) {
                    continue;
                }
                for (Map<String, Object> translated : translation.translate(event)) {
                    write(out, "data: " + JSON.writeValueAsString(translated) + "\n\n");
                }
                if (event.endsTurn()) {
                    return;
                }
            }
        } catch (IOException gone) {
            // The frontend closed the tab. Not a fault, and the run carries on — a person who
            // navigated away has not cancelled anything.
            LOG.debug("An AG-UI client disconnected", gone);
        } catch (InterruptedException stopped) {
            // The server is shutting down. The flag goes back on the thread, because a pooled
            // thread that swallowed it would start the next request already interrupted.
            Thread.currentThread().interrupt();
        }
    }

    // --- reading the input --------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readInput(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES);
        return body.length == 0 ? Map.of() : JSON.readValue(body, Map.class);
    }

    /**
     * The conversation a thread id means.
     *
     * <p>AG-UI's client mints its own thread ids and expects them to mean the same
     * conversation next time; this runtime mints its own conversation ids and has no column
     * for somebody else's. So the two are matched here.
     *
     * <p>An id this runtime has never seen becomes a new conversation rather than a 404 —
     * refusing one would mean a frontend could not start a conversation at all — and the
     * first draft <em>only</em> did that: it looked the thread id up as a conversation id,
     * never found one, and made a new conversation on every request. Every run was turn one
     * of a fresh thread, the client's continuity was silently gone, and it passed every test
     * because no test had sent two messages to one thread.
     *
     * <p><strong>The mapping is in memory, and that is a real limitation.</strong> A restart
     * re-keys every thread: the transcripts survive, because the store is the store, but a
     * client reconnecting with yesterday's thread id gets a new conversation. Fixing it
     * properly means the chat store knowing about foreign ids, which is a change to a module
     * that should not have to know this adapter exists — so it is stated here rather than
     * papered over.
     */
    private Conversation conversationFor(String threadId) {
        if (threadId.isBlank()) {
            return store.create(tenantId, "");
        }
        String known = threads.get(threadId);
        if (known != null) {
            java.util.Optional<Conversation> existing = store.conversation(tenantId, known);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        // Or the client sent one of OUR ids, which is what a client does when it took the
        // thread id from a conversation this runtime created.
        java.util.Optional<Conversation> ours = store.conversation(tenantId, threadId);
        if (ours.isPresent()) {
            threads.put(threadId, threadId);
            return ours.get();
        }
        Conversation made = store.create(tenantId, "");
        threads.put(threadId, made.id());
        return made;
    }

    /**
     * What the person just said.
     *
     * <p>AG-UI sends the whole conversation on every run, because its client owns the
     * transcript. This runtime owns its own, so only the newest user message is new — taking
     * the last one rather than replaying all of them is what keeps the two transcripts from
     * being written twice.
     */
    @SuppressWarnings("unchecked")
    private static String lastUserMessage(Map<String, Object> input) {
        Object raw = input.get("messages");
        if (!(raw instanceof List<?> messages)) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Map<?, ?> message
                    && "user".equals(String.valueOf(message.get("role")))) {
                Object content = ((Map<String, Object>) message).get("content");
                return content == null ? "" : String.valueOf(content);
            }
        }
        return "";
    }

    private static String text(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static void write(OutputStream out, String frame) throws IOException {
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
        LOG.debug("Refused an AG-UI request with {}: {}", status, Quoted.of(String.valueOf(body)));
    }

    /** What a request asked for and this endpoint did not do, for the record. */
    static List<String> ignoredFrontendTools(Map<String, Object> input) {
        Object raw = input.get("tools");
        if (!(raw instanceof List<?> tools) || tools.isEmpty()) {
            return List.of();
        }
        List<String> names = new java.util.ArrayList<>();
        for (Object tool : tools) {
            if (tool instanceof Map<?, ?> one) {
                names.add(String.valueOf(one.get("name")));
            }
        }
        return List.copyOf(names);
    }
}
