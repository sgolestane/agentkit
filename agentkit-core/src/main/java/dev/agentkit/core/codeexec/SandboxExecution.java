package dev.agentkit.core.codeexec;

import java.util.Objects;

/**
 * The result of running code in a {@link CodeSandbox}: what the script produced and
 * whether execution failed.
 *
 * <p>Only this final output flows back to the model — the intermediate tool results
 * the script consumed stay in the sandbox. That is the point of programmatic tool
 * calling: a script can call tools dozens of times and return a few summary lines,
 * instead of each call round-tripping through the model's context.
 *
 * @param output the script's final output (or the error message when {@code error}); never {@code null}
 * @param error  whether execution failed (a runtime error, timeout, or non-zero exit)
 */
public record SandboxExecution(String output, boolean error) {

    public SandboxExecution {
        Objects.requireNonNull(output, "output");
    }

    public static SandboxExecution ok(String output) {
        return new SandboxExecution(output, false);
    }

    public static SandboxExecution error(String message) {
        return new SandboxExecution(message, true);
    }
}
