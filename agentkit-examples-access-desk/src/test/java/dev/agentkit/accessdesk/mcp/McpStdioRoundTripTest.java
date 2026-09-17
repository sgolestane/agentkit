package dev.agentkit.accessdesk.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.accessdesk.systems.CompanySystemsServer;
import dev.agentkit.accessdesk.tools.Effect;
import dev.agentkit.accessdesk.tools.ToolInfo;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The company systems MCP server, launched as a real subprocess and reached through
 * {@link StdioMcpClient} and {@link Connectors} — the same path the application takes.
 */
class McpStdioRoundTripTest {

    @TempDir
    Path dataDir;

    private static List<String> serverCommand(Path stateDir) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(java, "-cp", System.getProperty("java.class.path"),
                CompanySystemsServer.class.getName(), stateDir.toString());
    }

    @Test
    void theServerDescribesEachToolWithStandardAnnotationsAndItsDeclaration() {
        try (StdioMcpClient client = StdioMcpClient.start(serverCommand(dataDir))) {
            Map<String, StdioMcpClient.ListedTool> tools = client.listDeclaredTools().stream()
                    .collect(Collectors.toMap(t -> t.info().name(), t -> t));

            assertThat(tools).containsKeys("directory_lookup", "list_resources", "list_access", "list_messages",
                    "grant_access", "revoke_access", "send_message");
            assertThat(tools.get("list_resources").annotations()).containsEntry("readOnlyHint", true);
            assertThat(tools.get("revoke_access").annotations()).containsEntry("destructiveHint", true)
                    .containsEntry("idempotentHint", true);
            assertThat(tools.get("grant_access").info().meta())
                    .containsEntry(McpServer.META_EFFECT, "grant").containsEntry(McpServer.META_SUBJECT, "email");
        }
    }

    @Test
    void connectorsDeclareToolsFromTheServerAndMapAnnotationsToSideEffects() {
        String json = """
                {"servers": [{"name": "company",
                  "command": ["${java}", "-cp", "${classpath}", "%s", "${dataDir}"],
                  "tools": {"send_message": {"system": "slack"}}}]}
                """.formatted(CompanySystemsServer.class.getName());
        try (Connectors.Connected connected = Connectors.connect(json, placeholders(dataDir))) {
            assertThat(connected.catalog().info("grant_access")).contains(new ToolInfo("access", Effect.GRANT, "email"));
            assertThat(connected.catalog().info("list_resources")).contains(new ToolInfo("catalog", Effect.READ, null));
            // The operator's override wins over what the server says.
            assertThat(connected.catalog().info("send_message")).contains(new ToolInfo("slack", Effect.NOTIFY, "to_email"));

            Tool list = connected.catalog().entry("list_resources").orElseThrow().tool();
            Tool revoke = connected.catalog().entry("revoke_access").orElseThrow().tool();
            Tool send = connected.catalog().entry("send_message").orElseThrow().tool();
            assertThat(list.sideEffects()).isEqualTo(SideEffects.NONE);
            assertThat(revoke.sideEffects()).isEqualTo(SideEffects.EXTERNAL);
            assertThat(send.sideEffects()).isEqualTo(SideEffects.EXTERNAL);
            assertThat(connected.catalog().entry("grant_access").orElseThrow().tool().sideEffects())
                    .isEqualTo(SideEffects.IDEMPOTENT);
        }
    }

    @Test
    void callsReachTheServerAndItsStateSurvivesARestart() throws Exception {
        String json = """
                {"servers": [{"name": "company",
                  "command": ["${java}", "-cp", "${classpath}", "%s", "${dataDir}"]}]}
                """.formatted(CompanySystemsServer.class.getName());
        try (Connectors.Connected connected = Connectors.connect(json, placeholders(dataDir))) {
            ToolResult granted = call(connected, "grant_access", Map.of("resource_id", "datadog-payments",
                    "email", "priya.natarajan@acme.example", "level", "read"));
            assertThat(granted.isError()).isFalse();
            assertThat(granted.content()).contains("Granted read");
        }
        assertThat(Files.exists(dataDir.resolve("state.json"))).isTrue();

        try (Connectors.Connected again = Connectors.connect(json, placeholders(dataDir))) {
            ToolResult listed = call(again, "list_access", Map.of("email", "priya.natarajan@acme.example"));
            assertThat(listed.content()).contains("datadog-payments");
            ToolResult unknown = call(again, "grant_access", Map.of("resource_id", "nope",
                    "email", "priya.natarajan@acme.example", "level", "read"));
            assertThat(unknown.isError()).isTrue();
        }
    }

    @Test
    void aToolTheServerDoesNotDeclareIsLeftOut() {
        StdioMcpClient.ListedTool undeclared = new StdioMcpClient.ListedTool(
                new dev.agentkit.mcp.McpToolInfo("mystery", "does something", Map.of("type", "object")), Map.of());
        StdioMcpClient.ListedTool hintedRead = new StdioMcpClient.ListedTool(
                new dev.agentkit.mcp.McpToolInfo("peek", "reads", Map.of("type", "object")),
                Map.of("readOnlyHint", true));

        assertThat(Connectors.declare("other", undeclared, null)).isEmpty();
        assertThat(Connectors.declare("other", hintedRead, null)).contains(new ToolInfo("other", Effect.READ, null));
        assertThat(Connectors.sideEffects(Map.of())).isEqualTo(SideEffects.UNKNOWN);
    }

    private static ToolResult call(Connectors.Connected connected, String name, Map<String, Object> args) {
        return connected.catalog().entry(name).orElseThrow().tool()
                .execute(new ToolInvocation("t", name, new java.util.HashMap<>(args)));
    }

    private static Map<String, String> placeholders(Path dataDir) {
        return Map.of("java", Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "classpath", System.getProperty("java.class.path"), "dataDir", dataDir.toString());
    }
}
