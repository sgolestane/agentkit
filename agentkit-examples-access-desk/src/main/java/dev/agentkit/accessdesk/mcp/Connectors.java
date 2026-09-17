package dev.agentkit.accessdesk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.ToolDeclaration;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.mcp.McpConnection;
import dev.agentkit.mcp.McpTool;
import dev.agentkit.mcp.McpToolInfo;
import dev.agentkit.mcp.StdioMcpConnection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Connects to the MCP servers named in a {@code connectors.json} and turns their tools into a
 * {@link DeclaredTools}, each tool with what it does declared.
 *
 * <pre>{@code
 * {
 *   "servers": [
 *     {
 *       "name": "company",
 *       "command": ["${java}", "-cp", "${classpath}", "dev.agentkit.accessdesk.systems.CompanySystemsServer", "${dataDir}"],
 *       "trustAnnotations": true,
 *       "tools": { "send_message": { "effect": "notify", "subject": "to_email" } }
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p><strong>Where a tool's declaration comes from</strong>, first match wins:
 * <ol>
 *   <li>the {@code tools} entry for it in this file — for a third-party server that does not describe
 *       itself, and for an operator who disagrees with one that does;</li>
 *   <li>the server's own {@code _meta} keys ({@link McpServer#META_EFFECT} and friends);</li>
 *   <li>for a server with {@code "trustAnnotations": true}, the standard {@code readOnlyHint}, which is enough to
 *       declare a read.</li>
 * </ol>
 * A tool none of these declare is left out, and said so on standard error. An undeclared tool could do
 * anything, and every rule this application applies to tools is a rule about declarations.
 *
 * <p><strong>Side effects</strong> come from {@code agentkit-mcp}: a server marked {@code trustAnnotations} has its
 * tools' annotations acted on ({@link McpTool#trustingAnnotations}); any other server's tools stay
 * {@code UNKNOWN}, because annotations are hints a server can lie in.
 */
public final class Connectors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The catalog, and each server's connection by name, to use directly and to close when the application stops. */
    public record Connected(DeclaredTools catalog, Map<String, McpConnection> clients) implements AutoCloseable {
        /** The connection to the named server. */
        public McpConnection client(String serverName) {
            McpConnection client = clients.get(serverName);
            if (client == null) {
                throw new IllegalArgumentException("No MCP server named " + serverName + " in connectors.json");
            }
            return client;
        }

        @Override
        public void close() {
            clients.values().forEach(McpConnection::close);
        }
    }

    private Connectors() {
    }

    /**
     * Connects to every server in {@code json}.
     *
     * @param placeholders values substituted for {@code ${name}} in each server's command
     */
    public static Connected connect(String json, Map<String, String> placeholders) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException("connectors.json is not valid JSON", e);
        }
        DeclaredTools catalog = new DeclaredTools();
        Map<String, McpConnection> clients = new LinkedHashMap<>();
        try {
            for (JsonNode server : root.path("servers")) {
                String name = server.path("name").asText("mcp");
                boolean trusted = server.path("trustAnnotations").asBoolean(false);
                List<String> command = new ArrayList<>();
                server.path("command").forEach(part -> command.add(substitute(part.asText(), placeholders)));
                StdioMcpConnection client = StdioMcpConnection.start(command);
                clients.put(name, client);
                for (McpToolInfo listed : client.listTools()) {
                    declare(name, listed, server.path("tools").path(listed.name()), trusted)
                            .ifPresentOrElse(
                                    info -> catalog.add(trusted ? McpTool.trustingAnnotations(client, listed)
                                            : new McpTool(client, listed), info),
                                    () -> System.err.println("Access Desk: leaving out MCP tool "
                                            + name + "/" + listed.name() + ", which does not declare what it does"));
                }
            }
        } catch (RuntimeException e) {
            clients.values().forEach(McpConnection::close);
            throw e;
        }
        return new Connected(catalog, Collections.unmodifiableMap(clients));
    }

    /** The declaration for one listed tool, from the override, the server's meta, or a trusted read-only hint. */
    static Optional<ToolDeclaration> declare(String serverName, McpToolInfo listed, JsonNode override, boolean trustAnnotations) {
        Map<String, Object> meta = listed.meta();
        Optional<ToolEffect> effect = ToolEffect.parse(text(override, "effect"))
                .or(() -> ToolEffect.parse(asString(meta.get(McpServer.META_EFFECT))))
                .or(() -> trustAnnotations && Boolean.TRUE.equals(listed.annotations().readOnlyHint())
                        ? Optional.of(ToolEffect.READ) : Optional.empty());
        if (effect.isEmpty()) {
            return Optional.empty();
        }
        String system = Optional.ofNullable(text(override, "system"))
                .or(() -> Optional.ofNullable(asString(meta.get(McpServer.META_SYSTEM))))
                .orElse(serverName);
        String subject = Optional.ofNullable(text(override, "subject"))
                .orElse(asString(meta.get(McpServer.META_SUBJECT)));
        return Optional.of(new ToolDeclaration(system, effect.get(), subject));
    }

    private static String substitute(String value, Map<String, String> placeholders) {
        String result = value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
