package dev.agentkit.mcp;

import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.ToolDeclaration;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;

/** A small declared catalog for serving over MCP in tests: a read, a grant that names whom it acts on, and a notice. */
public final class ExampleTools {

    private ExampleTools() {
    }

    public static DeclaredTools catalog() {
        return new DeclaredTools()
                .add(FunctionTool.builder("list_resources", "Find resources by name")
                                .schema(schema("query"))
                                .sideEffects(SideEffects.NONE)
                                .handler(inv -> ToolResult.ok("[{\"id\":\"db-payments-prod\",\"query\":\""
                                        + inv.stringArgument("query") + "\"}]"))
                                .build(),
                        new ToolDeclaration("catalog", ToolEffect.READ, null))
                .add(FunctionTool.builder("grant_access", "Give someone access")
                                .schema(schema("email"))
                                .sideEffects(SideEffects.EXTERNAL)
                                .handler(inv -> ToolResult.ok("granted to " + inv.stringArgument("email")))
                                .build(),
                        new ToolDeclaration("iam", ToolEffect.GRANT, "email"))
                .add(FunctionTool.builder("send_message", "Message someone")
                                .schema(schema("to_email"))
                                .sideEffects(SideEffects.EXTERNAL)
                                .handler(inv -> ToolResult.error("nobody called " + inv.stringArgument("to_email")))
                                .build(),
                        new ToolDeclaration("chat", ToolEffect.NOTIFY, "to_email"));
    }

    private static Map<String, Object> schema(String property) {
        return Map.of("type", "object", "properties", Map.of(property, Map.of("type", "string")),
                "required", List.of(property));
    }
}
