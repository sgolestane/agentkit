package dev.agentkit.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An {@link McpConnection} that speaks JSON-RPC over an MCP server subprocess's
 * standard input/output — the most common MCP transport.
 *
 * <p>{@link #start(List)} launches the server, performs the {@code initialize}
 * handshake, and leaves the connection ready for {@link #listTools} and
 * {@link #callTool}. {@link #close()} terminates the subprocess and releases its
 * stdio. Only the text content of a tool result is surfaced; other content types
 * are ignored.
 *
 * <p>Every operation blocks until the server responds or the connection closes —
 * there is no read timeout, so a hung-but-alive server blocks the calling thread.
 * Thread interruption does not unblock a blocking pipe read; to abort a hung call,
 * call {@link #close()} from another thread, which closes the stdio (unblocking the
 * read with an {@link McpException}) and terminates the subprocess.
 */
public final class StdioMcpConnection implements McpConnection {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final JsonRpcPeer peer;
    private final Runnable closer;

    // Package-private: lets tests drive the protocol over scripted streams.
    StdioMcpConnection(Reader in, Writer out, Runnable closer) {
        this.peer = new JsonRpcPeer(new BufferedReader(in), out, MAPPER);
        this.closer = Objects.requireNonNull(closer, "closer");
        try {
            initialize();
        } catch (Throwable t) {
            // A failed handshake must not leak the transport (e.g. the subprocess), and
            // "failed" was written as RuntimeException while the thing being protected is a
            // process (#135). An Error out of the handshake — an OutOfMemoryError while
            // Jackson builds the request node is the plausible one — left a live subprocess
            // with no reference to it anywhere, for the lifetime of the JVM.
            //
            // Nothing else changes, and that is the point: the throwable is rethrown as
            // itself, so this is terminal exactly as it was and an Error still reaches the
            // caller as an Error. Widening the catch here buys a reaped subprocess and
            // costs nothing, which is why this is the least contentious of #135's five.
            // Precise rethrow keeps the constructor's signature: the try body declares no
            // checked exception, so `t` is inferred as RuntimeException | Error.
            shutdown();
            throw t;
        }
    }

    /**
     * Launches {@code command} as an MCP server and completes the initialize
     * handshake. The process's stderr is inherited for visibility.
     *
     * @throws McpException if the process cannot be started or the handshake fails
     */
    public static StdioMcpConnection start(List<String> command) {
        Objects.requireNonNull(command, "command");
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
        } catch (IOException e) {
            throw new McpException("failed to start MCP server " + command, e);
        }
        Reader in = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
        Writer out = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        return new StdioMcpConnection(in, out, process::destroy);
    }

    private void initialize() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", MAPPER.createObjectNode());
        ObjectNode clientInfo = MAPPER.createObjectNode();
        clientInfo.put("name", "agentkit");
        clientInfo.put("version", "0.1.0");
        params.set("clientInfo", clientInfo);

        peer.request("initialize", params);
        peer.notify("notifications/initialized", null);
    }

    @Override
    public List<McpToolInfo> listTools() {
        JsonNode result = peer.request("tools/list", MAPPER.createObjectNode());
        List<McpToolInfo> tools = new ArrayList<>();
        for (JsonNode tool : result.path("tools")) {
            tools.add(new McpToolInfo(
                    tool.path("name").asText(),
                    tool.path("description").asText(""),
                    toMap(tool.get("inputSchema")),
                    toMap(tool.get("_meta")),
                    McpToolAnnotations.from(toMap(tool.get("annotations")))));
        }
        return tools;
    }

    @Override
    public java.util.Optional<McpResource> readResource(String uri) {
        Objects.requireNonNull(uri, "uri");
        ObjectNode params = MAPPER.createObjectNode();
        params.put("uri", uri);
        JsonNode result;
        try {
            result = peer.request("resources/read", params);
        } catch (McpException noSuchResource) {
            // A server that does not implement resources, or does not have this one, answers
            // with a JSON-RPC error. That is an answer rather than a fault: the tool it
            // belongs to still works, it just has no interface, and a host that threw here
            // would take the tool down with the decoration.
            return java.util.Optional.empty();
        }
        for (JsonNode content : result.path("contents")) {
            if (content.hasNonNull("text")) {
                return java.util.Optional.of(new McpResource(uri,
                        content.path("mimeType").asText(""),
                        content.path("text").asText("")));
            }
        }
        // Blobs are base64 binary. The extension's only content type is HTML as text, so a
        // blob here is a resource this host has no use for rather than one to decode.
        return java.util.Optional.empty();
    }

    @Override
    public McpCallResult callTool(String name, Map<String, Object> arguments) {
        Objects.requireNonNull(name, "name");
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", MAPPER.valueToTree(arguments == null ? Map.of() : arguments));

        JsonNode result = peer.request("tools/call", params);
        return new McpCallResult(textOf(result.path("content")), result.path("isError").asBoolean(false));
    }

    /** Concatenates the text of every {@code type: "text"} block in a content array. */
    private static String textOf(JsonNode content) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        return text.toString();
    }

    private static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        try {
            return MAPPER.convertValue(node, MAP_TYPE);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    /**
     * Aborts the transport, then releases the stdio streams.
     *
     * <p>The {@code closer} runs <em>first</em>: destroying the subprocess closes its
     * pipe, which unblocks a read blocked on a hung server (closing the reader itself
     * cannot — a blocked {@code readLine} holds the reader's monitor, so closing it
     * would deadlock). Only after the read is unblocked is it safe to close the
     * streams for deterministic fd release.
     */
    private void shutdown() {
        closer.run();
        peer.close();
    }
}
