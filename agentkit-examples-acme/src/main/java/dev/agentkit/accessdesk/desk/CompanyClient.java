package dev.agentkit.accessdesk.desk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.mcp.McpCallResult;
import dev.agentkit.mcp.McpConnection;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The desk's own, typed view of the company systems, over the same MCP connection the agent's tools use.
 *
 * <p>The desk's rules — who may approve, how long access may last — are enforced in code, so the code
 * reads the directory and the catalog itself rather than trusting what a model says they contain. It
 * talks to the connection directly: these results are the desk's inputs, not text for a model, so they
 * are not fenced.
 */
public final class CompanyClient {

    /** A person in the directory. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Person(String email, String name, String title, String department, String manager) {
    }

    /** Something a person can be given access to. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Resource(String id, String name, String system, List<String> levels, String sensitivity,
                           String owner, int max_hours) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpConnection connection;

    public CompanyClient(McpConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    public Optional<Person> person(String email) {
        McpCallResult result = connection.callTool("directory_lookup", Map.of("email", email == null ? "" : email));
        return result.isError() ? Optional.empty() : Optional.of(parse(result.text(), new TypeReference<Person>() {
        }));
    }

    public Optional<Resource> resource(String id) {
        McpCallResult result = connection.callTool("list_resources", Map.of("query", id == null ? "" : id));
        if (result.isError()) {
            throw new IllegalStateException("The resource catalog could not be read: " + result.text());
        }
        return parse(result.text(), new TypeReference<List<Resource>>() {
        }).stream().filter(r -> r.id().equals(id)).findFirst();
    }

    /** Grants access; empty on success, or why it failed. */
    public Optional<String> grant(String resourceId, String email, String level) {
        return outcome(connection.callTool("grant_access", Map.of("resource_id", resourceId, "email", email, "level", level)));
    }

    /** Revokes access; empty on success, or why it failed. */
    public Optional<String> revoke(String resourceId, String email, String level) {
        return outcome(connection.callTool("revoke_access", Map.of("resource_id", resourceId, "email", email, "level", level)));
    }

    /** Sends a direct message; empty on success, or why it failed. */
    public Optional<String> message(String to, String text) {
        return outcome(connection.callTool("send_message", Map.of("to_email", to, "text", text)));
    }

    private static Optional<String> outcome(McpCallResult result) {
        return result.isError() ? Optional.of(result.text()) : Optional.empty();
    }

    private static <T> T parse(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new IllegalStateException("The company systems returned something unreadable: " + e.getMessage(), e);
        }
    }
}
