package dev.agentkit.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;

/**
 * A minimal synchronous JSON-RPC 2.0 client speaking the MCP stdio framing:
 * one JSON message per line. It writes a request and blocks reading lines until the
 * response with the matching id arrives, skipping any interleaved server
 * notifications or server-initiated requests.
 *
 * <p>This is a client for a cooperative single-server session; it does not answer
 * server-initiated requests (e.g. a server {@code ping}) — it simply ignores them.
 * {@code request} and {@code notify} are synchronized so an accidental shared use
 * across threads serializes rather than corrupting the framing; there is still only
 * one outstanding request at a time. {@code awaitResponse} blocks with no timeout,
 * so a hung-but-alive server blocks the caller until the connection closes.
 */
final class JsonRpcPeer {

    /**
     * Default cap on the length of a single JSON-RPC line, in characters (~64M chars,
     * so up to ~128 MiB of heap in the accumulating buffer). Generous enough for large
     * tool results, but bounded so a malicious or faulty server cannot exhaust heap
     * with an unterminated line (see {@link #readLine}).
     */
    static final int DEFAULT_MAX_LINE_LENGTH = 64 * 1024 * 1024;

    private final BufferedReader in;
    private final Writer out;
    private final ObjectMapper mapper;
    private final int maxLineLength;
    private long nextId = 1;

    JsonRpcPeer(BufferedReader in, Writer out, ObjectMapper mapper) {
        this(in, out, mapper, DEFAULT_MAX_LINE_LENGTH);
    }

    JsonRpcPeer(BufferedReader in, Writer out, ObjectMapper mapper, int maxLineLength) {
        this.in = in;
        this.out = out;
        this.mapper = mapper;
        if (maxLineLength <= 0) {
            throw new IllegalArgumentException("maxLineLength must be > 0, was: " + maxLineLength);
        }
        this.maxLineLength = maxLineLength;
    }

    /**
     * Sends a request and returns its {@code result} node.
     *
     * @throws McpException on transport failure or a JSON-RPC error response
     */
    synchronized JsonNode request(String method, JsonNode params) {
        long id = nextId++;
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        writeMessage(request);
        return awaitResponse(id, method);
    }

    /** Sends a notification (a request with no id, expecting no response). */
    synchronized void notify(String method, JsonNode params) {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }
        writeMessage(notification);
    }

    private JsonNode awaitResponse(long id, String method) {
        try {
            String line;
            while ((line = readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode message = mapper.readTree(line);
                // A message carrying "method" is a server notification or a
                // server-initiated request (e.g. ping), never a response — skip it,
                // even if its id happens to collide with ours.
                if (message.has("method")) {
                    continue;
                }
                JsonNode idNode = message.get("id");
                // Skip responses to other (already-consumed) requests.
                if (idNode == null || idNode.isNull() || idNode.asLong() != id) {
                    continue;
                }
                if (message.hasNonNull("error")) {
                    JsonNode error = message.get("error");
                    throw new McpException("MCP error " + error.path("code").asInt()
                            + " on " + method + ": " + error.path("message").asText());
                }
                return message.path("result");
            }
            throw new McpException("connection closed while awaiting response to " + method);
        } catch (IOException e) {
            throw new McpException("failed reading response to " + method, e);
        }
    }

    /**
     * Reads one {@code \n}-terminated line, enforcing {@link #maxLineLength}. Unlike
     * {@link BufferedReader#readLine()}, which grows an internal buffer without limit,
     * this throws once the line exceeds the cap — before the whole line is
     * materialized — so a server that never sends a newline cannot exhaust heap. A
     * trailing {@code \r} before the {@code \n} is stripped (CRLF framing); unlike
     * {@link BufferedReader#readLine()} a lone {@code \r} is not a line terminator,
     * which is fine for the newline-delimited JSON MCP uses.
     *
     * @return the line without its terminator, or {@code null} at end of stream
     * @throws McpException if the line exceeds {@link #maxLineLength}
     */
    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            if (sb.length() >= maxLineLength) {
                throw new McpException("MCP message exceeded the maximum line length of "
                        + maxLineLength + " characters; aborting to avoid unbounded memory use");
            }
            sb.append((char) c);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private void writeMessage(JsonNode message) {
        try {
            out.write(mapper.writeValueAsString(message));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new McpException("failed writing JSON-RPC message", e);
        }
    }

    /**
     * Best-effort close of both streams, releasing their file descriptors.
     *
     * <p>Deliberately <em>not</em> {@code synchronized}. This does not by itself
     * unblock a {@link #request} blocked in {@code readLine} — a {@link BufferedReader}
     * shares its monitor between {@code readLine} and {@code close}, so it must be
     * called only after the underlying transport has been torn down (e.g. the
     * subprocess destroyed), which is what actually releases the blocked read. See
     * {@code StdioMcpConnection#shutdown}.
     */
    void close() {
        try {
            in.close();
        } catch (IOException ignored) {
            // best effort
        }
        try {
            out.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
