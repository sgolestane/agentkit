package dev.agentkit.accessdesk.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import dev.agentkit.core.tool.DeclaredTools;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Serves an {@link McpServer} over stdio: newline-delimited JSON-RPC on standard input and output.
 * Standard output carries protocol messages only; anything a server wants to log goes to standard
 * error.
 */
public final class StdioMcpServer {

    private StdioMcpServer() {
    }

    /** Reads messages until {@code in} ends, answering each on {@code out}. */
    public static void serve(McpServer server, DeclaredTools tools, InputStream in, OutputStream out) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
             Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode message;
                try {
                    message = McpServer.mapper().readTree(line);
                } catch (JsonProcessingException e) {
                    write(writer, McpServer.error(null, -32700, "Parse error"));
                    continue;
                }
                server.handle(message, tools).ifPresent(response -> write(writer, response));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Writer writer, JsonNode response) {
        try {
            writer.write(McpServer.mapper().writeValueAsString(response));
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
