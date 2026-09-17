package dev.agentkit.accessdesk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentkit.mcp.McpCallResult;
import dev.agentkit.mcp.McpConnection;
import dev.agentkit.mcp.McpException;
import dev.agentkit.mcp.McpToolInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An MCP client over stdio that keeps what {@code agentkit-mcp}'s client drops: each tool's
 * {@code annotations}.
 *
 * <p>It implements {@link McpConnection}, so tools are still wrapped by {@code agentkit-mcp}'s own
 * {@code McpTool} — with its sanitising of descriptions and schemas, and its fencing of every result —
 * and only listing goes through {@link #listDeclaredTools()}. It exists inside this application
 * because the annotations are needed here; the smaller change, capturing them in
 * {@code StdioMcpConnection.listTools}, belongs in {@code agentkit-mcp}.
 *
 * <p>Requests are synchronous and one at a time. Messages the server sends that are not the awaited
 * response (notifications, server requests) are skipped.
 */
public final class StdioMcpClient implements McpConnection {

    /** A listed tool with the parts of its listing that {@link McpToolInfo} does not hold. */
    public record ListedTool(McpToolInfo info, Map<String, Object> annotations) {
        public ListedTool {
            Objects.requireNonNull(info, "info");
            annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final Process process;
    private final BufferedReader in;
    private final Writer out;
    private final AtomicLong ids = new AtomicLong();

    private StdioMcpClient(Process process) {
        this.process = process;
        this.in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.out = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
    }

    /** Launches {@code command} as an MCP server and completes the initialize handshake. */
    public static StdioMcpClient start(List<String> command) {
        Objects.requireNonNull(command, "command");
        Process process;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        } catch (IOException e) {
            throw new McpException("failed to start MCP server " + command, e);
        }
        StdioMcpClient client = new StdioMcpClient(process);
        try {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.putObject("capabilities");
            params.putObject("clientInfo").put("name", "access-desk").put("version", "0.1.0");
            client.request("initialize", params);
            client.notify("notifications/initialized");
            return client;
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    /** Every tool the server lists, with its annotations. */
    public List<ListedTool> listDeclaredTools() {
        JsonNode result = request("tools/list", MAPPER.createObjectNode());
        List<ListedTool> tools = new ArrayList<>();
        for (JsonNode tool : result.path("tools")) {
            McpToolInfo info = new McpToolInfo(tool.path("name").asText(), tool.path("description").asText(""),
                    toMap(tool.get("inputSchema")), toMap(tool.get("_meta")));
            tools.add(new ListedTool(info, toMap(tool.get("annotations"))));
        }
        return tools;
    }

    @Override
    public List<McpToolInfo> listTools() {
        return listDeclaredTools().stream().map(ListedTool::info).toList();
    }

    @Override
    public McpCallResult callTool(String name, Map<String, Object> arguments) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", MAPPER.valueToTree(arguments == null ? Map.of() : arguments));
        JsonNode result = request("tools/call", params);
        StringBuilder text = new StringBuilder();
        for (JsonNode item : result.path("content")) {
            if ("text".equals(item.path("type").asText())) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(item.path("text").asText());
            }
        }
        return new McpCallResult(text.toString(), result.path("isError").asBoolean(false));
    }

    @Override
    public void close() {
        try {
            out.close();
        } catch (IOException ignored) {
            // closing stdin is how a stdio server is asked to exit; failing to is not worth reporting
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
        }
    }

    private synchronized JsonNode request(String method, JsonNode params) {
        long id = ids.incrementAndGet();
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params);
        write(message);
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode response = MAPPER.readTree(line);
                if (response.has("method") || response.path("id").asLong(-1) != id) {
                    continue;
                }
                if (response.hasNonNull("error")) {
                    throw new McpException(method + " failed: " + response.get("error").path("message").asText());
                }
                return response.path("result");
            }
        } catch (IOException e) {
            throw new McpException(method + " failed: " + e.getMessage(), e);
        }
        throw new McpException(method + " failed: the MCP server closed its output");
    }

    private synchronized void notify(String method) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        write(message);
    }

    private void write(JsonNode message) {
        try {
            out.write(MAPPER.writeValueAsString(message));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new McpException("could not write to the MCP server: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        return node == null || !node.isObject() ? new LinkedHashMap<>() : MAPPER.convertValue(node, LinkedHashMap.class);
    }
}
