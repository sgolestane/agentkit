package dev.agentkit.host;

import dev.agentkit.core.tool.ForwardingTool;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A tool that changes something, telling the plan step it was given to when a call of it reports an error: so a step
 * whose grant, request or message failed is not called done, however many steps run at once and whatever order their
 * calls land in the trace.
 */
final class ReportingTool extends ForwardingTool {

    private final Tool delegate;
    private final Consumer<String> failed;

    ReportingTool(Tool delegate, Consumer<String> failed) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.failed = Objects.requireNonNull(failed, "failed");
    }

    @Override
    protected Tool delegate() {
        return delegate;
    }

    @Override
    protected Tool rebuiltAround(Tool bound) {
        return new ReportingTool(bound, failed);
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        ToolResult result = delegate.execute(invocation);
        if (result.isError()) {
            failed.accept(delegate.name());
        }
        return result;
    }
}
