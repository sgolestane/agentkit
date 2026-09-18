package dev.agentkit.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The client's half of the protocol that does not depend on the transport: what an {@code initialize} asks for,
 * and how a tool listing, a tool result and a resource are read. {@link StdioMcpConnection} and
 * {@link HttpMcpConnection} share it, so a server answers the same whichever way it is reached.
 */
final class McpMessages {

    /** The revision a client asks for; a server may answer with an older one it speaks. */
    static final String PROTOCOL_VERSION = "2025-06-18";

    /** The most pages of {@code tools/list} followed, so a server that always sends a cursor cannot loop us. */
    static final int MAX_TOOL_PAGES = 100;

    static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private McpMessages() {
    }

    static ObjectNode initializeParams() {
        return initializeParams(false);
    }

    /** @param canBeAsked whether this client answers {@code elicitation/create}, which a server may then send */
    static ObjectNode initializeParams(boolean canBeAsked) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = params.putObject("capabilities");
        if (canBeAsked) {
            capabilities.putObject("elicitation");
        }
        ObjectNode clientInfo = MAPPER.createObjectNode();
        clientInfo.put("name", "agentkit");
        clientInfo.put("version", "0.1.0");
        params.set("clientInfo", clientInfo);
        return params;
    }

    /** Every tool the server lists, following {@code nextCursor} across pages. */
    static List<McpToolInfo> listTools(Function<JsonNode, JsonNode> request) {
        List<McpToolInfo> tools = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_TOOL_PAGES; page++) {
            ObjectNode params = MAPPER.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = request.apply(params);
            for (JsonNode tool : result.path("tools")) {
                tools.add(new McpToolInfo(
                        tool.path("name").asText(),
                        tool.path("description").asText(""),
                        toMap(tool.get("inputSchema")),
                        toMap(tool.get("_meta")),
                        McpToolAnnotations.from(toMap(tool.get("annotations")))));
            }
            cursor = result.path("nextCursor").asText("");
            if (cursor.isEmpty()) {
                return tools;
            }
        }
        throw new McpException("tools/list returned more than " + MAX_TOOL_PAGES + " pages");
    }

    static ObjectNode callParams(String name, Map<String, Object> arguments) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", MAPPER.valueToTree(arguments == null ? Map.of() : arguments));
        return params;
    }

    static McpCallResult callResult(JsonNode result) {
        return new McpCallResult(textOf(result.path("content")), result.path("isError").asBoolean(false));
    }

    static ObjectNode readParams(String uri) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("uri", uri);
        return params;
    }

    /**
     * The first text content of a {@code resources/read} result. Blobs are base64 binary; the only content type the
     * MCP Apps extension defines is HTML as text, so a blob is a resource this host has no use for.
     */
    static Optional<McpResource> resource(String uri, JsonNode result) {
        for (JsonNode content : result.path("contents")) {
            if (content.hasNonNull("text")) {
                return Optional.of(new McpResource(uri, content.path("mimeType").asText(""),
                        content.path("text").asText("")));
            }
        }
        return Optional.empty();
    }

    /** Concatenates the text of every {@code type: "text"} block in a content array. */
    static String textOf(JsonNode content) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        return text.toString();
    }

    static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        try {
            return MAPPER.convertValue(node, MAP_TYPE);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }
}
