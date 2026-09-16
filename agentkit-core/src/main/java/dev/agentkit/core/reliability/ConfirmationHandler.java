package dev.agentkit.core.reliability;

import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;

/**
 * Decides whether a gated tool invocation is approved. In an unsupervised setting
 * the safe default is {@link #DENY_ALL}; interactive harnesses can prompt a human.
 */
@FunctionalInterface
public interface ConfirmationHandler {

    /** Denies every request — the safe default for unsupervised runs. */
    ConfirmationHandler DENY_ALL = (tool, invocation) -> false;

    /** Approves every request. */
    ConfirmationHandler ALLOW_ALL = (tool, invocation) -> true;

    /**
     * Whether {@code invocation} is approved to run against {@code tool}.
     *
     * <p>The tool is here for the same reason it is on {@link Approver}: a person deciding
     * wants its description and its declared side effects. It is never null.
     */
    boolean confirm(Tool tool, ToolInvocation invocation);
}
