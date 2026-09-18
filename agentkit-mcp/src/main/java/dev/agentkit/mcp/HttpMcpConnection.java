package dev.agentkit.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * An {@link McpConnection} over MCP's streamable HTTP transport: the way to reach a server that runs somewhere
 * else, such as a customer's connector.
 *
 * <p>Each JSON-RPC message is its own {@code POST}. The server answers a request with either one JSON body or an
 * event stream that carries the response among any notifications it sends first; both are read, and anything that
 * is not the response is skipped, as {@link StdioMcpConnection} does. A session id the server issues on
 * {@code initialize} is sent back on every later request, with the negotiated protocol version. A session the
 * server has forgotten ({@code 404}) is started again once, and the request retried.
 *
 * <p><strong>Credentials</strong> are headers from {@link Builder#headers}, asked for on every request, so a token
 * that is refreshed elsewhere is picked up without reconnecting.
 *
 * <p><strong>Bounds.</strong> {@link Builder#timeout} bounds how long a request waits for the server to start
 * answering; a body or stream is then read until it ends, up to {@link Builder#maxMessageBytes} per message, so a
 * server cannot exhaust the heap. Safe for use from several threads: requests are independent HTTP exchanges, and
 * re-establishing a session is serialized.
 */
public final class HttpMcpConnection implements McpConnection {

    /** The header a server issues a session in, and a client returns it in. */
    public static final String SESSION_HEADER = "Mcp-Session-Id";

    /** The header naming the protocol revision, on every request after {@code initialize}. */
    public static final String PROTOCOL_HEADER = "MCP-Protocol-Version";

    /** How long a request waits for the server to begin answering, unless {@link Builder#timeout} says otherwise. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /** The largest single message read, unless {@link Builder#maxMessageBytes} says otherwise. */
    public static final int DEFAULT_MAX_MESSAGE_BYTES = 64 * 1024 * 1024;

    private final URI endpoint;
    private final HttpClient http;
    private final Supplier<Map<String, String>> headers;
    private final Duration timeout;
    private final int maxMessageBytes;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Object sessionLock = new Object();
    private volatile String sessionId;
    private volatile String protocolVersion;

    private HttpMcpConnection(Builder builder) {
        this.endpoint = builder.endpoint;
        this.http = builder.http != null ? builder.http
                : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.headers = builder.headers;
        this.timeout = builder.timeout;
        this.maxMessageBytes = builder.maxMessageBytes;
    }

    /** Connects to {@code endpoint} with no credentials and the default bounds. */
    public static HttpMcpConnection connect(URI endpoint) {
        return builder(endpoint).connect();
    }

    public static Builder builder(URI endpoint) {
        return new Builder(endpoint);
    }

    /** How to reach a server over HTTP. */
    public static final class Builder {
        private final URI endpoint;
        private HttpClient http;
        private Supplier<Map<String, String>> headers = Map::of;
        private Duration timeout = DEFAULT_TIMEOUT;
        private int maxMessageBytes = DEFAULT_MAX_MESSAGE_BYTES;

        private Builder(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            String scheme = endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("An MCP endpoint is an http or https URL, not " + endpoint);
            }
        }

        /** A fixed header, such as {@code Authorization}, sent on every request. */
        public Builder header(String name, String value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            Supplier<Map<String, String>> before = headers;
            headers = () -> {
                Map<String, String> all = new LinkedHashMap<>(before.get());
                all.put(name, value);
                return all;
            };
            return this;
        }

        /** Headers asked for on every request, so a credential refreshed elsewhere is used at once. */
        public Builder headers(Supplier<Map<String, String>> headers) {
            this.headers = Objects.requireNonNull(headers, "headers");
            return this;
        }

        public Builder timeout(Duration timeout) {
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        public Builder maxMessageBytes(int maxMessageBytes) {
            if (maxMessageBytes <= 0) {
                throw new IllegalArgumentException("maxMessageBytes must be > 0");
            }
            this.maxMessageBytes = maxMessageBytes;
            return this;
        }

        public Builder httpClient(HttpClient http) {
            this.http = Objects.requireNonNull(http, "http");
            return this;
        }

        /**
         * Opens the session: the {@code initialize} handshake.
         *
         * @throws McpException if the server cannot be reached or refuses the handshake
         */
        public HttpMcpConnection connect() {
            HttpMcpConnection connection = new HttpMcpConnection(this);
            connection.initialize();
            return connection;
        }
    }

    @Override
    public List<McpToolInfo> listTools() {
        return McpMessages.listTools(params -> request("tools/list", params));
    }

    @Override
    public McpCallResult callTool(String name, Map<String, Object> arguments) {
        Objects.requireNonNull(name, "name");
        return McpMessages.callResult(request("tools/call", McpMessages.callParams(name, arguments)));
    }

    @Override
    public Optional<McpResource> readResource(String uri) {
        Objects.requireNonNull(uri, "uri");
        JsonNode result;
        try {
            result = request("resources/read", McpMessages.readParams(uri));
        } catch (McpException noSuchResource) {
            // As over stdio: a server without the resource answers with an error, and the tool still works.
            return Optional.empty();
        }
        return McpMessages.resource(uri, result);
    }

    /** Ends the session on the server, if it issued one. Best effort: a server may not support it. */
    @Override
    public void close() {
        String session = sessionId;
        sessionId = null;
        if (session == null) {
            return;
        }
        try {
            HttpRequest.Builder delete = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).DELETE();
            headers.get().forEach(delete::header);
            delete.header(SESSION_HEADER, session);
            http.send(delete.build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            // Nothing to do: the server will expire the session on its own.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The session the server issued, if any. */
    public Optional<String> sessionId() {
        return Optional.ofNullable(sessionId);
    }

    // ---------------------------------------------------------------- protocol

    private void initialize() {
        synchronized (sessionLock) {
            sessionId = null;
            protocolVersion = null;
            Exchange opened = send("initialize", McpMessages.initializeParams(), true);
            JsonNode result = opened.result();
            protocolVersion = result.path("protocolVersion").asText(McpMessages.PROTOCOL_VERSION);
            sessionId = opened.sessionId();
            notification("notifications/initialized");
        }
    }

    private JsonNode request(String method, JsonNode params) {
        String session = sessionId;
        try {
            return send(method, params, false).result();
        } catch (SessionExpired expired) {
            synchronized (sessionLock) {
                // Another thread may have re-established it already.
                if (Objects.equals(session, sessionId)) {
                    initialize();
                }
            }
            return send(method, params, false).result();
        }
    }

    private void notification(String method) {
        ObjectNode message = McpMessages.MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        HttpResponse<InputStream> response = post(message, "notification " + method);
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status != 200 && status != 202 && status != 204) {
                throw new McpException("MCP server answered " + status + " to " + method + ": " + snippet(body));
            }
        } catch (IOException e) {
            throw new McpException("failed reading the answer to " + method, e);
        }
    }

    private record Exchange(JsonNode result, String sessionId) {
    }

    private static final class SessionExpired extends RuntimeException {
        SessionExpired() {
            super(null, null, false, false);
        }
    }

    private Exchange send(String method, JsonNode params, boolean opening) {
        long id = nextId.getAndIncrement();
        ObjectNode message = McpMessages.MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        HttpResponse<InputStream> response = post(message, method);
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == 404 && !opening && sessionId != null) {
                throw new SessionExpired();
            }
            if (status != 200) {
                throw new McpException("MCP server answered " + status + " to " + method + ": " + snippet(body));
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            JsonNode answer = contentType.startsWith("text/event-stream")
                    ? fromStream(body, id, method)
                    : fromJson(body, id, method);
            return new Exchange(resultOf(answer, method), response.headers().firstValue(SESSION_HEADER).orElse(null));
        } catch (IOException e) {
            throw new McpException("failed reading the answer to " + method, e);
        }
    }

    private HttpResponse<InputStream> post(JsonNode message, String what) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        try {
            request.POST(HttpRequest.BodyPublishers.ofString(McpMessages.MAPPER.writeValueAsString(message)));
        } catch (JsonProcessingException e) {
            throw new McpException("could not write " + what, e);
        }
        headers.get().forEach(request::header);
        String session = sessionId;
        if (session != null) {
            request.header(SESSION_HEADER, session);
        }
        String version = protocolVersion;
        if (version != null) {
            request.header(PROTOCOL_HEADER, version);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new McpException("could not reach the MCP server at " + endpoint + " for " + what, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("interrupted waiting for " + what, e);
        }
    }

    /** A plain JSON answer: the response itself, or a batch holding it. */
    private JsonNode fromJson(InputStream body, long id, String method) throws IOException {
        byte[] bytes = body.readNBytes(maxMessageBytes + 1);
        if (bytes.length > maxMessageBytes) {
            throw new McpException("the answer to " + method + " exceeded " + maxMessageBytes + " bytes");
        }
        JsonNode answer = McpMessages.MAPPER.readTree(bytes);
        if (answer != null && answer.isArray()) {
            for (JsonNode message : answer) {
                if (isResponseTo(message, id)) {
                    return message;
                }
            }
        } else if (isResponseTo(answer, id)) {
            return answer;
        }
        throw new McpException("the answer to " + method + " did not contain its response");
    }

    /**
     * A server-sent event stream: events until the one carrying the response. Notifications and requests the server
     * sends first are skipped; this client does not answer server-initiated requests.
     */
    private JsonNode fromStream(InputStream body, long id, String method) throws IOException {
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = readLine(body)) != null) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    JsonNode message = McpMessages.MAPPER.readTree(data.toString());
                    data.setLength(0);
                    if (isResponseTo(message, id)) {
                        return message;
                    }
                }
                continue;
            }
            if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(value.startsWith(" ") ? value.substring(1) : value);
                if (data.length() > maxMessageBytes) {
                    throw new McpException("an event in the answer to " + method + " exceeded " + maxMessageBytes + " bytes");
                }
            }
            // "event:", "id:", "retry:" and comments carry nothing this client needs.
        }
        if (!data.isEmpty()) {
            JsonNode message = McpMessages.MAPPER.readTree(data.toString());
            if (isResponseTo(message, id)) {
                return message;
            }
        }
        throw new McpException("the event stream ended before the response to " + method);
    }

    /** One line of an event stream, without its terminator; null at the end. Bounded like a message. */
    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return stripCr(line);
            }
            if (line.size() >= maxMessageBytes) {
                throw new McpException("an event stream line exceeded " + maxMessageBytes + " bytes");
            }
            line.write(b);
        }
        return line.size() == 0 ? null : stripCr(line);
    }

    private static String stripCr(ByteArrayOutputStream line) {
        String text = line.toString(StandardCharsets.UTF_8);
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }

    private static boolean isResponseTo(JsonNode message, long id) {
        if (message == null || !message.isObject() || message.has("method")) {
            return false;
        }
        JsonNode idNode = message.get("id");
        return idNode != null && !idNode.isNull() && idNode.asLong() == id;
    }

    private static JsonNode resultOf(JsonNode response, String method) {
        if (response.hasNonNull("error")) {
            JsonNode error = response.get("error");
            throw new McpException("MCP error " + error.path("code").asInt() + " on " + method + ": "
                    + error.path("message").asText());
        }
        return response.path("result");
    }

    private static String snippet(InputStream body) throws IOException {
        String text = new String(body.readNBytes(500), StandardCharsets.UTF_8).strip();
        return text.isEmpty() ? "(no body)" : text;
    }
}
