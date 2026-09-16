package dev.agentkit.mcp;

/**
 * Thrown when an MCP transport or protocol operation fails — a broken connection,
 * a malformed message, or a JSON-RPC error returned by the server.
 */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
