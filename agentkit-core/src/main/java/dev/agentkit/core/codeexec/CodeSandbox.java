package dev.agentkit.core.codeexec;

/**
 * Runs a snippet of code in an isolated environment, with the agent's tools callable
 * from within it via a {@link ToolBridge}.
 *
 * <p>This is the seam for "programmatic tool calling": instead of the model invoking
 * tools one at a time through the API, it writes a script that orchestrates them, and
 * only the script's final output returns. AgentKit does not ship a code runtime —
 * executing untrusted model-written code safely requires real isolation (microVMs,
 * gVisor). Implement this against a sandbox provider (e.g. E2B, Modal, Daytona,
 * Vercel, Cloudflare) or your own container, wiring tool calls in the running code
 * back to {@code tools.invoke(...)}.
 */
@FunctionalInterface
public interface CodeSandbox {

    /**
     * Executes {@code code}, letting it call AgentKit tools through {@code tools}.
     *
     * @param code  the script to run; never {@code null}
     * @param tools the bridge the running code uses to invoke tools; never {@code null}
     * @return the execution result; never {@code null}
     */
    SandboxExecution run(String code, ToolBridge tools);
}
