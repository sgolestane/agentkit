package dev.agentkit.mcp;

/**
 * The outcome of an MCP {@code tools/call}: the tool's text content and whether the
 * server flagged it as an error.
 *
 * <p>MCP tool results carry a list of content blocks; this flattens the text blocks
 * into a single string, which is what AgentKit's {@code ToolResult} consumes.
 *
 * @param text    the concatenated text content (may be empty)
 * @param isError whether the server marked the call as failed
 */
public record McpCallResult(String text, boolean isError) {

    public McpCallResult {
        text = text == null ? "" : text;
    }
}
