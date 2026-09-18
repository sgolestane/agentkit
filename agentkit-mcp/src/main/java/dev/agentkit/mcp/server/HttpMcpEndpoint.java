package dev.agentkit.mcp.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.mcp.HttpMcpConnection;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Serves an {@link McpServer} over MCP's streamable HTTP transport: a client POSTs one JSON-RPC message and gets one
 * JSON response back ({@code application/json}), or {@code 202 Accepted} for a notification. No stream is opened
 * with {@code GET}, so {@code GET} is {@code 405}.
 *
 * <p><strong>Who is calling</strong> is the {@link Callers}' answer for the request's headers, and the catalog
 * served is the one {@code toolsFor} returns for that caller — so the same tool acts as whoever asked. A request
 * whose caller is unknown, or who has no catalog, is refused with {@code 401}. {@link Callers#header} trusts a
 * header as it stands, which is a demo identity for a local deployment only.
 *
 * <p><strong>Sessions, and asking the person.</strong> {@code initialize} issues a session, remembered with its
 * caller and whether the client can be asked things ({@code capabilities.elicitation}). A later request naming a
 * session this endpoint does not know, or one begun by another caller, is answered {@code 404}, which tells a client
 * to start again. For a client that can be asked, a {@code tools/call} is answered as an event stream: while the tool
 * runs, {@link McpCall#current()} lets it put a question to the person, sent on that stream as
 * {@code elicitation/create}; the client POSTs the answer back as a JSON-RPC response, and the call's own response
 * ends the stream. Answering a question needs a second request to be served while the first waits, so the
 * {@code HttpServer} this is mounted on must have an executor that runs requests concurrently — the JDK default runs
 * them one at a time, and a question would then wait out its patience.
 *
 * <p><strong>Where requests may come from.</strong> A browser page on another origin could otherwise reach a
 * server on localhost, so a request carrying an {@code Origin} header is refused unless {@code origins} allows it;
 * by default only localhost origins are. Clients such as Claude Code send none.
 */
public final class HttpMcpEndpoint implements HttpHandler {

    static final String SESSION_HEADER = HttpMcpConnection.SESSION_HEADER;

    /** How long an idle event stream waits before writing a comment to keep proxies from closing it. */
    private static final Duration KEEP_ALIVE = Duration.ofSeconds(15);

    /** The most sessions remembered; past it, sessions with no question outstanding are forgotten to make room. */
    static final int MAX_SESSIONS = 10_000;

    /** The call being served on this thread, for {@link McpCall#current()}. */
    static final ThreadLocal<McpCall> CURRENT = new ThreadLocal<>();

    /** Who a request comes from, judged from its headers; empty when it cannot be told. */
    @FunctionalInterface
    public interface Callers {
        Optional<String> identify(Headers headers);

        /** The value of {@code header}, unauthenticated: whoever sends it is taken at their word. */
        static Callers header(String header) {
            Objects.requireNonNull(header, "header");
            return headers -> Optional.ofNullable(headers.getFirst(header)).map(String::strip).filter(s -> !s.isEmpty());
        }
    }

    /** One client's session: who began it, whether it can be asked things, and the questions it has not answered. */
    private record Session(String caller, boolean canBeAsked, Map<String, CompletableFuture<JsonNode>> questions,
                           AtomicLong nextQuestion) {
    }

    private final McpServer server;
    private final Callers callers;
    private final Function<String, Optional<DeclaredTools>> toolsFor;
    private final Predicate<String> origins;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Allows localhost origins only.
     *
     * @param toolsFor the catalog to serve to a caller, or empty if the caller may not use this server
     */
    public HttpMcpEndpoint(McpServer server, Callers callers, Function<String, Optional<DeclaredTools>> toolsFor) {
        this(server, callers, toolsFor, HttpMcpEndpoint::localOrigin);
    }

    /** @param origins whether a request carrying this {@code Origin} header may be answered */
    public HttpMcpEndpoint(McpServer server, Callers callers, Function<String, Optional<DeclaredTools>> toolsFor,
                           Predicate<String> origins) {
        this.server = Objects.requireNonNull(server, "server");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.toolsFor = Objects.requireNonNull(toolsFor, "toolsFor");
        this.origins = Objects.requireNonNull(origins, "origins");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.isBlank() && !origins.test(origin.strip())) {
                send(exchange, 403, McpServer.error(null, -32000, "Origin not allowed"));
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod())) {
                String id = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
                Session ended = id == null ? null : sessions.remove(id);
                if (ended != null) {
                    ended.questions().values().forEach(q -> q.cancel(false));
                }
                exchange.sendResponseHeaders(ended == null ? 404 : 204, -1);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Allow", "POST, DELETE");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            JsonNode message;
            try {
                message = McpServer.mapper().readTree(exchange.getRequestBody().readAllBytes());
            } catch (JsonProcessingException e) {
                send(exchange, 400, McpServer.error(null, -32700, "Parse error"));
                return;
            }
            Optional<String> caller = callers.identify(exchange.getRequestHeaders());
            Optional<DeclaredTools> tools = caller.flatMap(toolsFor);
            if (tools.isEmpty()) {
                send(exchange, 401, McpServer.error(message == null ? null : message.get("id"), -32001,
                        "Unknown caller"));
                return;
            }
            String method = message == null ? "" : message.path("method").asText("");

            if (method.equals("initialize")) {
                Optional<ObjectNode> response = server.handle(message, tools.get());
                String id = UUID.randomUUID().toString();
                if (sessions.size() >= MAX_SESSIONS) {
                    sessions.entrySet().removeIf(e -> e.getValue().questions().isEmpty() && sessions.size() >= MAX_SESSIONS / 2);
                }
                sessions.put(id, new Session(caller.get(), message.path("params").path("capabilities").has("elicitation"),
                        new ConcurrentHashMap<>(), new AtomicLong(1)));
                exchange.getResponseHeaders().add(SESSION_HEADER, id);
                send(exchange, 200, response.orElseThrow());
                return;
            }

            String sessionId = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
            Session session = null;
            if (sessionId != null) {
                session = sessions.get(sessionId);
                if (session == null || !session.caller().equals(caller.get())) {
                    send(exchange, 404, McpServer.error(message == null ? null : message.get("id"), -32001,
                            "No such session; initialize again"));
                    return;
                }
            }

            if (message != null && message.isObject() && !message.has("method") && message.has("id")) {
                // The client answering a question this endpoint put to it.
                CompletableFuture<JsonNode> waiting = session == null ? null
                        : session.questions().remove(message.get("id").asText());
                if (waiting != null) {
                    waiting.complete(message);
                }
                exchange.sendResponseHeaders(waiting == null ? 400 : 202, -1);
                return;
            }

            if (session != null && session.canBeAsked() && method.equals("tools/call")) {
                stream(exchange, message, tools.get(), session);
                return;
            }
            Optional<ObjectNode> response = server.handle(message, tools.get());
            if (response.isEmpty()) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            send(exchange, 200, response.get());
        }
    }

    /** A call answered as an event stream, so the tool can ask the person something before it answers. */
    private void stream(HttpExchange exchange, JsonNode message, DeclaredTools tools, Session session) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            Object lock = new Object();
            CURRENT.set((question, schema, patience) -> ask(out, lock, session, question, schema, patience));
            Optional<ObjectNode> response;
            try {
                response = server.handle(message, tools);
            } finally {
                CURRENT.remove();
            }
            if (response.isPresent()) {
                synchronized (lock) {
                    event(out, response.get());
                }
            }
        }
    }

    private McpCall.Answer ask(OutputStream out, Object lock, Session session, String question,
                               Map<String, Object> schema, Duration patience) {
        String id = "q-" + session.nextQuestion().getAndIncrement();
        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        session.questions().put(id, answer);
        ObjectNode request = McpServer.mapper().createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", "elicitation/create");
        ObjectNode params = request.putObject("params");
        params.put("message", question);
        params.set("requestedSchema", McpServer.mapper().valueToTree(schema));
        try {
            synchronized (lock) {
                event(out, request);
            }
            long deadline = System.nanoTime() + patience.toNanos();
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return new McpCall.Answer(McpCall.Action.CANCEL, Map.of());
                }
                try {
                    JsonNode response = answer.get(Math.min(left, KEEP_ALIVE.toNanos()), TimeUnit.NANOSECONDS);
                    return answerOf(response);
                } catch (TimeoutException quiet) {
                    synchronized (lock) {
                        out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            }
        } catch (IOException | ExecutionException | java.util.concurrent.CancellationException gone) {
            return new McpCall.Answer(McpCall.Action.CANCEL, Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new McpCall.Answer(McpCall.Action.CANCEL, Map.of());
        } finally {
            session.questions().remove(id);
        }
    }

    @SuppressWarnings("unchecked")
    private static McpCall.Answer answerOf(JsonNode response) {
        JsonNode result = response.path("result");
        McpCall.Action action = switch (result.path("action").asText("")) {
            case "accept" -> McpCall.Action.ACCEPT;
            case "decline" -> McpCall.Action.DECLINE;
            default -> McpCall.Action.CANCEL;
        };
        Map<String, Object> content = result.path("content").isObject()
                ? McpServer.mapper().convertValue(result.get("content"), Map.class) : Map.of();
        return new McpCall.Answer(action, action == McpCall.Action.ACCEPT ? content : Map.of());
    }

    private static void event(OutputStream out, JsonNode message) throws IOException {
        out.write(("data: " + McpServer.mapper().writeValueAsString(message) + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Whether {@code origin} is a page served from this machine. */
    public static boolean localOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            String host = URI.create(origin.strip()).getHost();
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host)
                    || "::1".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void send(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = McpServer.mapper().writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
