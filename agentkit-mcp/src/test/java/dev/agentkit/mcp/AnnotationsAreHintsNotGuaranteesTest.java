package dev.agentkit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.Tool;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A server's {@code annotations} used to be dropped when its tools were listed, so a deployment that trusted a
 * server to describe its tools had nothing to act on. They are now captured on every {@link McpToolInfo} — and
 * acted on only when the deployment says it trusts the server, because a hostile server would call a destructive
 * tool read-only.
 */
class AnnotationsAreHintsNotGuaranteesTest {

    private static final String LISTING = String.join("\n",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":["
                    + "{\"name\":\"peek\",\"inputSchema\":{\"type\":\"object\"},\"annotations\":{\"readOnlyHint\":true}},"
                    + "{\"name\":\"set\",\"inputSchema\":{\"type\":\"object\"},"
                    + "\"annotations\":{\"destructiveHint\":false,\"idempotentHint\":true}},"
                    + "{\"name\":\"wipe\",\"inputSchema\":{\"type\":\"object\"},"
                    + "\"annotations\":{\"destructiveHint\":true,\"idempotentHint\":true,\"title\":\"Wipe it\"}},"
                    + "{\"name\":\"silent\",\"inputSchema\":{\"type\":\"object\"}},"
                    + "{\"name\":\"odd\",\"inputSchema\":{\"type\":\"object\"},\"annotations\":{\"readOnlyHint\":\"yes\"}}"
                    + "]}}") + "\n";

    private static List<McpToolInfo> listed() {
        try (StdioMcpConnection connection = new StdioMcpConnection(new StringReader(LISTING), new StringWriter(), () -> { })) {
            return connection.listTools();
        }
    }

    private static McpToolInfo tool(List<McpToolInfo> tools, String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void listingCapturesTheAnnotationsAndOnlyTheBooleanHints() {
        List<McpToolInfo> tools = listed();

        assertThat(tool(tools, "peek").annotations()).isEqualTo(new McpToolAnnotations(true, null, null, null));
        assertThat(tool(tools, "wipe").annotations()).isEqualTo(new McpToolAnnotations(null, true, true, null));
        assertThat(tool(tools, "silent").annotations()).isEqualTo(McpToolAnnotations.NONE);
        // A hint of the wrong type is not a claim this side makes for the server.
        assertThat(tool(tools, "odd").annotations().isEmpty()).isTrue();
    }

    @Test
    void anMcpToolIgnoresTheHintsUnlessTheDeploymentTrustsTheServer() {
        List<McpToolInfo> tools = listed();
        McpConnection unused = new McpConnection() {
            @Override
            public List<McpToolInfo> listTools() {
                return tools;
            }

            @Override
            public McpCallResult callTool(String name, Map<String, Object> arguments) {
                throw new AssertionError("not called");
            }

            @Override
            public void close() {
            }
        };

        for (McpToolInfo info : tools) {
            assertThat(new McpTool(unused, info).sideEffects()).as(info.name()).isEqualTo(SideEffects.UNKNOWN);
        }
        assertThat(McpTool.trustingAnnotations(unused, tool(tools, "peek")).sideEffects()).isEqualTo(SideEffects.NONE);
        assertThat(McpTool.trustingAnnotations(unused, tool(tools, "set")).sideEffects()).isEqualTo(SideEffects.IDEMPOTENT);
        // Destructive is never idempotent, whatever the server adds.
        assertThat(McpTool.trustingAnnotations(unused, tool(tools, "wipe")).sideEffects()).isEqualTo(SideEffects.EXTERNAL);
        assertThat(McpTool.trustingAnnotations(unused, tool(tools, "silent")).sideEffects()).isEqualTo(SideEffects.UNKNOWN);

        assertThat(McpTools.load(unused)).extracting(Tool::sideEffects).containsOnly(SideEffects.UNKNOWN);
        assertThat(McpTools.loadTrustingAnnotations(unused)).extracting(Tool::sideEffects)
                .containsExactly(SideEffects.NONE, SideEffects.IDEMPOTENT, SideEffects.EXTERNAL, SideEffects.UNKNOWN,
                        SideEffects.UNKNOWN);
    }

    @Test
    void absentHintsReadWithTheProtocolsDefaults() {
        // destructiveHint defaults to true, so idempotent alone is not enough to be safe to retry.
        assertThat(new McpToolAnnotations(null, null, true, null).asSideEffects()).isEqualTo(SideEffects.EXTERNAL);
        assertThat(new McpToolAnnotations(false, null, null, false).asSideEffects()).isEqualTo(SideEffects.EXTERNAL);
        assertThat(McpToolAnnotations.from(null)).isEqualTo(McpToolAnnotations.NONE);
        assertThat(new McpToolInfo("t", "", Map.of()).annotations()).isEqualTo(McpToolAnnotations.NONE);
    }
}
