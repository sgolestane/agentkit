package dev.agentkit.core.codeexec;

import dev.agentkit.core.tool.ToolResult;
import java.util.Map;

/**
 * The callback a {@link CodeSandbox} uses to invoke AgentKit tools from inside
 * running code. When a script calls one of the exposed tools, the sandbox routes
 * the call here, which executes the real tool and returns its result to the script.
 *
 * <p>This is the seam that makes "programmatic tool calling" provider-agnostic: the
 * sandbox owns the code runtime and the transport, while the tools stay ordinary
 * AgentKit {@link dev.agentkit.core.tool.Tool}s. See {@link ToolBridges#of} for a
 * registry-backed implementation.
 *
 * <p>If a sandbox may invoke tools concurrently (a parallel script), the registry
 * must be built during setup and then shared read-only, and the individual
 * {@code Tool}s — and any {@link dev.agentkit.core.reliability.ToolGate} threaded
 * through the bridge — must be
 * thread-safe; the bridge adds no synchronization of its own.
 */
@FunctionalInterface
public interface ToolBridge {

    /**
     * Invokes the tool named {@code toolName} with {@code arguments}.
     *
     * @return the tool's result; an error result if the tool is unknown, if its arguments
     *         are ones this framework will not carry (#246), or if it fails — the bridge
     *         never throws, so a bad call surfaces to the script as data
     */
    ToolResult invoke(String toolName, Map<String, Object> arguments);
}
