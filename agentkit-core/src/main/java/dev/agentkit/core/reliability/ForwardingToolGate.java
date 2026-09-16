package dev.agentkit.core.reliability;

import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;

/**
 * A {@link ToolGate} that hands every decision to another one.
 *
 * <p>Extend this to decorate a gate — to trace it, log it, count it — and override only
 * what you are decorating. Everything else forwards, including the parts that are easy to
 * forget and expensive to get wrong.
 *
 * <p>A gate carries more than one thing a decorator must pass on: the decision itself,
 * {@link ToolGate#guaranteesReadOnly()}, which {@code CodeExecutionTool} reads to decide
 * whether a script may be declared harmless, and {@link ToolGate#boundToOneRun()}, which the
 * durable runner reads to decide whether the gate may be registered on a worker at all.
 * Two hand-written decorators here have
 * dropped the decision, turning a read-only policy into deny-everything — silently,
 * because it fails closed and so looks like the policy working. The guarantee has not been
 * dropped in this repo yet; it is a default, so it is the one still available to forget.
 *
 * <p>This is opt-in and does not remove the mistake: a hand-written
 * {@code implements ToolGate} still compiles and still returns {@code false} for the
 * guarantee by default. What the abstract method reshape removed for everyone is the
 * <em>decision</em> trap; this makes the rest inheritable for anyone who takes it. A
 * decorator that needs to intercept overrides {@link #evaluate(Tool, ToolInvocation)} —
 * the only decision method there is — and still inherits the rest.
 */
public abstract class ForwardingToolGate implements ToolGate {

    /** The gate every unoverridden decision goes to. */
    protected abstract ToolGate delegate();

    @Override
    public GateResult evaluate(Tool tool, ToolInvocation invocation) {
        return delegate().evaluate(tool, invocation);
    }

    @Override
    public boolean guaranteesReadOnly() {
        return delegate().guaranteesReadOnly();
    }

    @Override
    public boolean waitsForAHuman() {
        return delegate().waitsForAHuman();
    }

    @Override
    public boolean boundToOneRun() {
        return delegate().boundToOneRun();
    }
}
