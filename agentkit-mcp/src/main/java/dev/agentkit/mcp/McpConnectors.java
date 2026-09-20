package dev.agentkit.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.tool.DeclaredTools;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connects to the MCP servers named in a connectors file and turns their tools into a {@link DeclaredTools}, each
 * tool with what it does declared.
 *
 * <pre>{@code
 * {
 *   "servers": [
 *     {
 *       "name": "company",
 *       "command": ["${java}", "-cp", "${classpath}", "com.example.CompanySystemsServer"],
 *       "trustAnnotations": true
 *     },
 *     {
 *       "name": "ledger",
 *       "url": "https://ledger.example.com/mcp",
 *       "headers": { "Authorization": "Bearer ${ledgerToken}" },
 *       "timeoutSeconds": 120,
 *       "tools": { "send_message": { "effect": "notify", "subject": "to_email" } }
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>A server is started as a subprocess spoken to over stdio ({@code command}) or reached over streamable HTTP
 * ({@code url}, with optional {@code headers}). {@code ${name}} in a command, a URL or a header value is replaced
 * from the placeholders the caller supplies, so a credential never has to be written in the file.
 *
 * <p><strong>Where a tool's declaration comes from</strong> is {@link McpDeclarations#declare}: this file's
 * {@code tools} entry for it, then the server's own {@code _meta}, then — for a server marked
 * {@code "trustAnnotations": true} — a {@code readOnlyHint}. A tool none of these declare is left out and logged.
 * An undeclared tool could do anything, and every rule applied to tools is a rule about declarations.
 *
 * <p><strong>Side effects</strong> follow the same trust: a {@code trustAnnotations} server has its tools'
 * annotations acted on ({@link McpTool#trustingAnnotations}); any other server's tools stay {@code UNKNOWN},
 * because annotations are hints a server can lie in.
 */
public final class McpConnectors {

    private static final Logger LOG = LoggerFactory.getLogger(McpConnectors.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The catalog, and each server's connection by name, to use directly and to close when the application stops. */
    public record Connected(DeclaredTools catalog, Map<String, McpConnection> clients) implements AutoCloseable {
        /** The connection to the named server. */
        public McpConnection client(String serverName) {
            McpConnection client = clients.get(serverName);
            if (client == null) {
                throw new IllegalArgumentException("No MCP server named " + serverName + " among the connectors");
            }
            return client;
        }

        @Override
        public void close() {
            clients.values().forEach(McpConnection::close);
        }
    }

    private McpConnectors() {
    }

    /**
     * Connects to every server in {@code json}. If one fails, those already connected are closed.
     *
     * @param placeholders values substituted for {@code ${name}} in each server's command, URL and headers
     */
    public static Connected connect(String json, Map<String, String> placeholders) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException("The connectors file is not valid JSON", e);
        }
        DeclaredTools catalog = new DeclaredTools();
        Map<String, McpConnection> clients = new LinkedHashMap<>();
        try {
            for (JsonNode server : root.path("servers")) {
                String name = server.path("name").asText("mcp");
                if (clients.containsKey(name)) {
                    throw new IllegalArgumentException("Two MCP servers are named " + name);
                }
                boolean trusted = server.path("trustAnnotations").asBoolean(false);
                McpConnection client = open(name, server, placeholders);
                clients.put(name, client);
                for (McpToolInfo listed : client.listTools()) {
                    JsonNode override = server.path("tools").path(listed.name());
                    McpDeclarations.declare(name, listed, new McpDeclarations.Override(text(override, "effect"),
                                    text(override, "system"), text(override, "subject")), trusted)
                            .ifPresentOrElse(
                                    info -> catalog.add(trusted ? McpTool.trustingAnnotations(client, listed)
                                            : new McpTool(client, listed), info),
                                    () -> LOG.warn("Leaving out MCP tool {}/{}, which does not declare what it does",
                                            name, listed.name()));
                }
            }
        } catch (RuntimeException e) {
            clients.values().forEach(McpConnection::close);
            throw e;
        }
        return new Connected(catalog, Collections.unmodifiableMap(clients));
    }

    private static McpConnection open(String name, JsonNode server, Map<String, String> placeholders) {
        boolean hasCommand = server.has("command");
        boolean hasUrl = server.hasNonNull("url");
        if (hasCommand == hasUrl) {
            throw new IllegalArgumentException("MCP server " + name + " needs exactly one of \"command\" or \"url\"");
        }
        if (hasCommand) {
            List<String> command = new ArrayList<>();
            server.path("command").forEach(part -> command.add(substitute(part.asText(), placeholders)));
            return StdioMcpConnection.start(command);
        }
        HttpMcpConnection.Builder http = HttpMcpConnection.builder(URI.create(substitute(server.get("url").asText(),
                placeholders)));
        server.path("headers").fields().forEachRemaining(header ->
                http.header(header.getKey(), substitute(header.getValue().asText(), placeholders)));
        if (server.has("timeoutSeconds")) {
            http.timeout(Duration.ofSeconds(server.get("timeoutSeconds").asLong()));
        }
        return http.connect();
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
}
