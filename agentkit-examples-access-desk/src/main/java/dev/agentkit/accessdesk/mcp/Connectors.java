package dev.agentkit.accessdesk.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.accessdesk.tools.Effect;
import dev.agentkit.accessdesk.tools.ToolCatalog;
import dev.agentkit.accessdesk.tools.ToolInfo;
import dev.agentkit.core.tool.ForwardingTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.mcp.McpTool;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Connects to the MCP servers named in a {@code connectors.json} and turns their tools into a
 * {@link ToolCatalog}, each tool with what it does declared.
 *
 * <pre>{@code
 * {
 *   "servers": [
 *     {
 *       "name": "company",
 *       "command": ["${java}", "-cp", "${classpath}", "dev.agentkit.accessdesk.systems.CompanySystemsServer", "${dataDir}"],
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
 *   <li>the standard {@code readOnlyHint}, which is enough to declare a read.</li>
 * </ol>
 * A tool none of these declare is left out, and said so on standard error. An undeclared tool could do
 * anything, and every rule this application applies to tools is a rule about declarations.
 *
 * <p><strong>Side effects</strong> come from the standard annotations: {@code readOnlyHint} is
 * {@link SideEffects#NONE}; {@code idempotentHint} without {@code destructiveHint} is
 * {@link SideEffects#IDEMPOTENT}; any other annotated tool is {@link SideEffects#EXTERNAL}; a tool with no
 * annotations stays {@link SideEffects#UNKNOWN}.
 */
public final class Connectors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The catalog, and each server's connection by name, to use directly and to close when the application stops. */
    public record Connected(ToolCatalog catalog, Map<String, StdioMcpClient> clients) implements AutoCloseable {
        /** The connection to the named server. */
        public StdioMcpClient client(String serverName) {
            StdioMcpClient client = clients.get(serverName);
            if (client == null) {
                throw new IllegalArgumentException("No MCP server named " + serverName + " in connectors.json");
            }
            return client;
        }

        @Override
        public void close() {
            clients.values().forEach(StdioMcpClient::close);
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
        ToolCatalog catalog = new ToolCatalog();
        Map<String, StdioMcpClient> clients = new java.util.LinkedHashMap<>();
        try {
            for (JsonNode server : root.path("servers")) {
                String name = server.path("name").asText("mcp");
                List<String> command = new ArrayList<>();
                server.path("command").forEach(part -> command.add(substitute(part.asText(), placeholders)));
                StdioMcpClient client = StdioMcpClient.start(command);
                clients.put(name, client);
                for (StdioMcpClient.ListedTool listed : client.listDeclaredTools()) {
                    declare(name, listed, server.path("tools").path(listed.info().name()))
                            .ifPresentOrElse(
                                    info -> catalog.add(new DeclaredMcpTool(new McpTool(client, listed.info()),
                                            sideEffects(listed.annotations())), info),
                                    () -> System.err.println("Access Desk: leaving out MCP tool "
                                            + name + "/" + listed.info().name()
                                            + ", which does not declare what it does"));
                }
            }
        } catch (RuntimeException e) {
            clients.values().forEach(StdioMcpClient::close);
            throw e;
        }
        return new Connected(catalog, java.util.Collections.unmodifiableMap(clients));
    }

    /** The declaration for one listed tool, from the override, the server's meta, or its read-only hint. */
    static Optional<ToolInfo> declare(String serverName, StdioMcpClient.ListedTool listed, JsonNode override) {
        Map<String, Object> meta = listed.info().meta();
        Optional<Effect> effect = Effect.parse(text(override, "effect"))
                .or(() -> Effect.parse(asString(meta.get(McpServer.META_EFFECT))))
                .or(() -> Boolean.TRUE.equals(listed.annotations().get("readOnlyHint"))
                        ? Optional.of(Effect.READ) : Optional.empty());
        if (effect.isEmpty()) {
            return Optional.empty();
        }
        String system = Optional.ofNullable(text(override, "system"))
                .or(() -> Optional.ofNullable(asString(meta.get(McpServer.META_SYSTEM))))
                .orElse(serverName);
        String subject = Optional.ofNullable(text(override, "subject"))
                .orElse(asString(meta.get(McpServer.META_SUBJECT)));
        return Optional.of(new ToolInfo(system, effect.get(), subject));
    }

    static SideEffects sideEffects(Map<String, Object> annotations) {
        if (annotations.isEmpty()) {
            return SideEffects.UNKNOWN;
        }
        if (Boolean.TRUE.equals(annotations.get("readOnlyHint"))) {
            return SideEffects.NONE;
        }
        if (Boolean.TRUE.equals(annotations.get("idempotentHint"))
                && !Boolean.TRUE.equals(annotations.get("destructiveHint"))) {
            return SideEffects.IDEMPOTENT;
        }
        return SideEffects.EXTERNAL;
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

    /** An MCP tool whose side effects come from its annotations rather than {@code McpTool}'s default. */
    static final class DeclaredMcpTool extends ForwardingTool {
        private final Tool delegate;
        private final SideEffects sideEffects;

        DeclaredMcpTool(Tool delegate, SideEffects sideEffects) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.sideEffects = Objects.requireNonNull(sideEffects, "sideEffects");
        }

        @Override
        protected Tool delegate() {
            return delegate;
        }

        @Override
        public SideEffects sideEffects() {
            return sideEffects;
        }
    }
}
