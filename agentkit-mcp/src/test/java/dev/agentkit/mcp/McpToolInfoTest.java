package dev.agentkit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class McpToolInfoTest {

    @Test
    void nullDescriptionAndSchemaDefaultToEmpty() {
        McpToolInfo info = new McpToolInfo("t", null, null);
        assertThat(info.description()).isEmpty();
        assertThat(info.inputSchema()).isEmpty();
    }

    @Test
    void aSchemaWithANullValuedEntryIsToleratedNotRejected() {
        // Map.copyOf would throw on a null value; a server schema may carry one.
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("default", null);

        McpToolInfo info = new McpToolInfo("t", "d", schema);

        assertThat(info.inputSchema()).containsEntry("type", "object");
        assertThat(info.inputSchema()).containsKey("default");
        assertThat(info.inputSchema().get("default")).isNull();
    }

    @Test
    void theSchemaCopyIsDefensiveAndUnmodifiable() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        McpToolInfo info = new McpToolInfo("t", "d", schema);

        schema.put("type", "mutated"); // must not affect the stored copy
        assertThat(info.inputSchema()).containsEntry("type", "object");
    }

    @Test
    @DisplayName("the schema copy reaches every nested map and list, and has since #176")
    void theSchemaCopyIsDeep() {
        // #133 listed this component as one of seven one-level copies, and it had already
        // stopped being one: #176 made the walk reach every depth so it could hold the
        // prose annotations that sit at every depth, and copying the containers came with
        // it. Pinned here rather than left as a paragraph, because ToolSpec's own copy is
        // deliberately one level deep (#133) and this is the reason that is safe — an MCP
        // server's schema is the only one in the repository written by a party outside the
        // deployment, and it arrives at ToolSpec already detached from the server.
        List<Object> serversList = new ArrayList<>(List.of("a"));
        Map<String, Object> serversNested = new LinkedHashMap<>();
        serversNested.put("type", "string");
        serversNested.put("enum", serversList);
        Map<String, Object> serversProperties = new LinkedHashMap<>();
        serversProperties.put("mode", serversNested);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", serversProperties);

        McpToolInfo info = new McpToolInfo("t", "d", schema);
        serversNested.put("type", "mutated");
        serversList.set(0, "mutated");

        Map<?, ?> properties = (Map<?, ?>) info.inputSchema().get("properties");
        assertThat(properties).isNotSameAs(serversProperties);
        Map<?, ?> mode = (Map<?, ?>) properties.get("mode");
        assertThat(mode).isNotSameAs(serversNested);
        assertThat(mode.get("type")).isEqualTo("string");
        // enum members are wire values a caller must reproduce exactly, so they are copied
        // rather than held — but copied is the point here.
        assertThat(mode.get("enum")).isNotSameAs(serversList).isEqualTo(List.of("a"));
    }
}
