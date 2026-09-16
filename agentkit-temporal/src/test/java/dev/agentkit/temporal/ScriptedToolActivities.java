package dev.agentkit.temporal;

import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;

/**
 * A {@link ToolActivities} written as one lambda, for tests about the workflow loop rather
 * than about approval.
 *
 * <p>{@code ToolActivities} stopped being a functional interface when it gained
 * {@code resumeTool} (#101) and {@code executeToolUnderLoweredTrust} (#122). These tests
 * script the layer beneath {@code ToolActivitiesImpl} — an activity that fails outright, one
 * that is too slow, one that fails only for tool B — and none of them is about a parked call
 * or a trust floor, so answering every method the same way keeps them saying what they said
 * before.
 */
@FunctionalInterface
interface ScriptedToolActivities extends ToolActivities {

    /** What this activity does with an invocation, however it was dispatched. */
    ToolResult call(ToolInvocation invocation);

    @Override
    default ToolOutcome executeTool(ToolInvocation invocation) {
        return ToolOutcome.of(call(invocation));
    }

    @Override
    default ToolOutcome resumeTool(ToolInvocation invocation, ApprovalVerdict verdict) {
        return ToolOutcome.of(call(invocation));
    }

    @Override
    default ToolOutcome resumeToolUnderLoweredTrust(ToolInvocation invocation,
                                                    ApprovalVerdict verdict) {
        return ToolOutcome.of(call(invocation));
    }

    @Override
    default ToolOutcome executeToolUnderLoweredTrust(ToolInvocation invocation) {
        return ToolOutcome.of(call(invocation));
    }
}
