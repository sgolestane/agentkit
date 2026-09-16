package dev.agentkit.itops.runtime;

import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Objects;

/**
 * One run, more than one witness.
 *
 * <p>{@code Agent.Builder.observer} holds a single observer, and this execution now has two
 * things to tell: {@link AuditObserver}, which writes the compliance trail, and
 * {@link RunHistory}, which policy reads back mid-run. Wrapping one inside the other was the
 * cheaper option and the wrong one — the audit trail and a policy input are different
 * concerns with different failure modes, and a class that did both would be a class a later
 * edit could quietly make policy depend on what a store write did.
 *
 * <p><strong>Every callback, or the class does not compile.</strong> Every method on
 * {@link AgentObserver} is a {@code default}, which is what makes a hand-written forwarder
 * dangerous: one added to the interface tomorrow silently stops reaching both members here,
 * and a run whose observer looked wired would be missing rows nobody asked for. That is the
 * same trap {@code Tool}'s declarations had before #296 — "a hazard described rather than
 * removed" — and it is not fixable from this side of the interface. What is fixable is
 * noticing: {@code ObserversForwardEveryCallbackTest} walks {@link AgentObserver}'s declared
 * methods with reflection and fails if this class does not override one of them, so a
 * seventh callback breaks a test here rather than a trail in production.
 *
 * <p>Order is the order given, and each member is called for every callback: a member that
 * throws does not stop the next one, because {@code Observations.ran} absorbs it at the
 * runner before this class is re-entered. Nothing here catches anything itself — an
 * observer's failure policy is {@code Agent.Builder.onObservationFailure}'s, and a second
 * one here would be a second answer.
 */
public final class Observers implements AgentObserver {

    private final List<AgentObserver> all;

    private Observers(List<AgentObserver> all) {
        this.all = List.copyOf(all);
    }

    /** Everything in {@code observers}, told in order, on every callback. */
    public static AgentObserver of(AgentObserver... observers) {
        Objects.requireNonNull(observers, "observers");
        for (AgentObserver observer : observers) {
            Objects.requireNonNull(observer, "observer");
        }
        return new Observers(List.of(observers));
    }

    @Override
    public void onStart(AgentRun run, Goal goal) {
        for (AgentObserver observer : all) {
            observer.onStart(run, goal);
        }
    }

    @Override
    public void onTextDelta(AgentRun run, int step, String delta) {
        for (AgentObserver observer : all) {
            observer.onTextDelta(run, step, delta);
        }
    }

    @Override
    public void onModelResponse(AgentRun run, int step, LlmResponse response) {
        for (AgentObserver observer : all) {
            observer.onModelResponse(run, step, response);
        }
    }

    @Override
    public void onToolProposed(AgentRun run, int step, ToolInvocation invocation) {
        for (AgentObserver observer : all) {
            observer.onToolProposed(run, step, invocation);
        }
    }

    @Override
    public void onToolResult(AgentRun run, int step, ToolInvocation proposed,
                             ToolInvocation effective, ToolResult result,
                             Disposition disposition) {
        for (AgentObserver observer : all) {
            observer.onToolResult(run, step, proposed, effective, result, disposition);
        }
    }

    @Override
    public void onFinish(AgentRun run, AgentResult result) {
        for (AgentObserver observer : all) {
            observer.onFinish(run, result);
        }
    }
}
