package dev.agentkit.accessdesk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolDeclaration;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The transport-independent half of an MCP server: one JSON-RPC message in, at most one out, over a
 * {@link DeclaredTools}.
 *
 * <p>Implements the tool subset of the protocol: {@code initialize}, {@code notifications/initialized},
 * {@code ping}, {@code tools/list} and {@code tools/call}. Anything else is answered with JSON-RPC's
 * "method not found". {@link StdioMcpServer} and {@link HttpMcpEndpoint} carry the messages.
 *
 * <h2>How a tool describes itself</h2>
 *
 * <p>Each listed tool carries the standard MCP annotations, derived from what it declares:
 * {@code readOnlyHint} for {@link ToolEffect#READ}, {@code destructiveHint} for {@link ToolEffect#REVOKE},
 * {@code idempotentHint} from its {@link SideEffects}. The annotations cannot say <em>grant</em> or
 * <em>revoke</em>, or which argument names the person, so the full declaration also travels in
 * {@code _meta} under {@link #META_EFFECT}, {@link #META_SYSTEM} and {@link #META_SUBJECT}. A client
 * that knows these keys needs no configuration to bound what the tools may do; one that does not
 * still gets the standard hints.
 */
public final class McpServer {

    public static final String META_EFFECT = "dev.agentkit/effect";
    public static final String META_SYSTEM = "dev.agentkit/system";
    public static final String META_SUBJECT = "dev.agentkit/subject";

    /** Protocol revisions this server speaks, newest first. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final String version;
    private final String instructions;

    /**
     * @param name         the server's name, sent in {@code serverInfo}
     * @param version      its version
     * @param instructions what a client's model should know about using this server; may be empty
     */
    public McpServer(String name, String version, String instructions) {
        this.name = Objects.requireNonNull(name, "name");
        this.version = Objects.requireNonNull(version, "version");
        this.instructions = instructions == null ? "" : instructions;
    }

    /**
     * Handles one message against {@code tools}.
     *
     * @return the response, or empty for a notification (which gets none)
     */
    public Optional<ObjectNode> handle(JsonNode message, DeclaredTools tools) {
        Objects.requireNonNull(tools, "tools");
        if (message == null || !message.isObject() || !message.hasNonNull("method")) {
            return Optional.of(error(message == null ? null : message.get("id"), -32600, "Invalid request"));
        }
        JsonNode id = message.get("id");
        String method = message.get("method").asText();
        if (id == null || id.isNull()) {
            // A notification: acknowledged by doing nothing, whatever it is.
            return Optional.empty();
        }
        JsonNode params = message.path("params");
        try {
            return Optional.of(switch (method) {
                case "initialize" -> result(id, initialize(params));
                case "ping" -> result(id, MAPPER.createObjectNode());
                case "tools/list" -> result(id, listTools(tools));
                case "tools/call" -> callTool(id, params, tools);
                default -> error(id, -32601, "Method not found: " + method);
            });
        } catch (RuntimeException e) {
            return Optional.of(error(id, -32603, "Internal error: " + e.getMessage()));
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String asked = params.path("protocolVersion").asText("");
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSIONS.contains(asked) ? asked : PROTOCOL_VERSIONS.get(0));
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", name);
        info.put("version", version);
        if (!instructions.isBlank()) {
            result.put("instructions", instructions);
        }
        return result;
    }

    private ObjectNode listTools(DeclaredTools tools) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode list = result.putArray("tools");
        for (DeclaredTools.Entry entry : tools.entries()) {
            Tool tool = entry.tool();
            ToolDeclaration info = entry.declaration();
            ObjectNode listed = list.addObject();
            listed.put("name", tool.name());
            listed.put("description", tool.description());
            listed.set("inputSchema", MAPPER.valueToTree(tool.inputSchema()));
            ObjectNode annotations = listed.putObject("annotations");
            annotations.put("readOnlyHint", info.effect() == ToolEffect.READ);
            annotations.put("destructiveHint", info.effect() == ToolEffect.REVOKE);
            annotations.put("idempotentHint", tool.sideEffects() == SideEffects.NONE
                    || tool.sideEffects() == SideEffects.IDEMPOTENT);
            annotations.put("openWorldHint", false);
            ObjectNode meta = listed.putObject("_meta");
            meta.put(META_EFFECT, info.effect().wire());
            meta.put(META_SYSTEM, info.system());
            if (info.subjectParam() != null) {
                meta.put(META_SUBJECT, info.subjectParam());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private ObjectNode callTool(JsonNode id, JsonNode params, DeclaredTools tools) {
        String toolName = params.path("name").asText("");
        Optional<DeclaredTools.Entry> entry = tools.entry(toolName);
        if (entry.isEmpty()) {
            return error(id, -32602, "Unknown tool: " + toolName);
        }
        Map<String, Object> arguments = params.has("arguments") && params.get("arguments").isObject()
                ? MAPPER.convertValue(params.get("arguments"), Map.class)
                : Map.of();
        ToolResult outcome;
        try {
            outcome = entry.get().tool().execute(new ToolInvocation("mcp-" + UUID.randomUUID(), toolName, arguments));
        } catch (RuntimeException e) {
            outcome = ToolResult.error(toolName + " failed: " + e.getMessage());
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", outcome.content());
        result.put("isError", outcome.isError());
        return result(id, result);
    }

    private static ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? MAPPER.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }
}
