package dev.agentkit.temporal;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.reliability.TrustFloor;
import dev.agentkit.core.tool.ToolRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.worker.Worker;
import java.util.Objects;

/**
 * Ergonomic wiring for running the AgentKit loop on Temporal.
 *
 * <p>The moving parts:
 * <ul>
 *   <li>{@link #dataConverter()} — the shared converter; set it on the
 *       {@code WorkflowClient} (client side) and the worker's client.</li>
 *   <li>{@link #register(Worker, LlmClient, ToolRegistry)} — registers the
 *       workflow implementation and the two activity implementations (which hold
 *       your real model client and tools) on a worker.</li>
 *   <li>{@link #newStub(WorkflowClient, String)} — a typed workflow stub to start
 *       a run.</li>
 * </ul>
 *
 * <p>Typical worker + client setup:
 * <pre>{@code
 * WorkflowClientOptions opts = WorkflowClientOptions.newBuilder()
 *         .setDataConverter(TemporalAgent.dataConverter()).build();
 * WorkflowClient client = WorkflowClient.newInstance(service, opts);
 * WorkerFactory factory = WorkerFactory.newInstance(client);
 * Worker worker = factory.newWorker("agentkit");
 * TemporalAgent.register(worker, llmClient, toolRegistry, ToolGates.readOnly());
 * factory.start();
 *
 * AgentRunResult result = TemporalAgent.newStub(client, "agentkit")
 *         .run(DurableAgentRun.of(goal, config, toolSpecs));
 * }</pre>
 */
public final class TemporalAgent {

    private TemporalAgent() {
    }

    /** The data converter to install on every client that touches the agent. */
    public static DataConverter dataConverter() {
        return DurableJson.dataConverter();
    }

    /**
     * Registers the durable agent's workflow and activity implementations on
     * {@code worker}. The activity implementations carry the real, side-effecting
     * collaborators — the model client and the tool registry.
     *
     * <p><strong>The client must be stateless across runs.</strong> One instance is
     * registered for the whole worker and serves every workflow on its task queue, so
     * a decorator that accumulates per-run state leaks that state between unrelated
     * runs. {@link dev.agentkit.core.reliability.BudgetLlmClient} is the shipped
     * example and is rejected (by {@link LlmActivitiesImpl}, so registering that
     * activity directly is covered too): cap a durable run with
     * {@link DurableAgentRun#withBudget(TokenBudget)} instead, which the workflow
     * enforces per run. That check is best-effort — a budget client hidden inside
     * another decorator cannot be detected, and the same caution applies to any
     * stateful client you write.
     *
     * <p>Ungated for the run's calls, but the registry is still inspected: a tool holding
     * its own blocking or per-run gate is refused here too (#283), since choosing no gate
     * for the worker says nothing about the gate a tool holds below it.
     *
     * <p>Nothing is registered if the client is rejected: the activities are
     * constructed before either registration call.
     *
     * @throws IllegalArgumentException if {@code llm} is a {@code BudgetLlmClient}
     */
    public static void registerUngated(Worker worker, LlmClient llm, ToolRegistry tools) {
        register(worker, llm, tools, ToolGate.ALLOW_ALL);
    }

    /**
     * @deprecated ungated, and named as though it were the ordinary choice. Use
     *     {@link #register(Worker, LlmClient, ToolRegistry, ToolGate)} with a policy, or
     *     {@link #registerUngated(Worker, LlmClient, ToolRegistry)} to say plainly that
     *     there is not one. #58 was a gate that went missing without anyone noticing;
     *     keeping the ungated form as the short, documented default reproduces the
     *     conditions for it. {@code ToolBridges.ofUngated} sets the precedent — the
     *     explicit name keeps the ungated choice visible at the call site instead of
     *     hiding behind a convenient default.
     */
    @Deprecated
    public static void register(Worker worker, LlmClient llm, ToolRegistry tools) {
        registerUngated(worker, llm, tools);
    }

    /**
     * As {@link #register(Worker, LlmClient, ToolRegistry)}, with {@code gate} consulted
     * before every tool call.
     *
     * <p>This is how a durable run is gated, and it has to be said explicitly because a
     * gate cannot travel in the workflow input: it is a lambda over local state, like the
     * tools it guards, so it is wired here on the worker rather than sent from the client.
     * A run that omits it is ungated, exactly as an in-process {@code Agent.Builder} that
     * never calls {@code toolGate} is.
     *
     * <p>The gate is evaluated inside the tool activity, so its decision is memoized in
     * history rather than recomputed on replay — see {@link ToolActivitiesImpl}.
     *
     * <p><strong>The gate here is the worker's, not a run's (#63).</strong> One
     * {@link ToolActivitiesImpl} serves every run on the task queue, so a gate carrying one
     * run's objective — which is what OWASP's action screening is — would judge every other
     * run's calls against it. Such a gate says so through {@link ToolGate#boundToOneRun()}
     * and is refused here; that method's javadoc carries what was measured before the check
     * existed. Gate a durable run on what is true of <em>every</em> run.
     *
     * <p><strong>And a gate a tool holds is refused too (#283).</strong> A tool can carry a
     * gate of its own — {@code CodeExecutionTool} requires one for the tools a script calls
     * — and it is consulted inside {@code Tool.execute}, below everything this class sees.
     * Both checks above therefore had a blind spot a documented wiring walked through. Every
     * tool in {@code tools} is now asked the same questions, through
     * {@link dev.agentkit.core.tool.Tool#holdsGateWaitingForAHuman()} and
     * {@link dev.agentkit.core.tool.Tool#holdsGateBoundToOneRun()} — and, since #328,
     * {@link dev.agentkit.core.tool.Tool#boundToOneRun()}, which is about one run's state
     * rather than one run's gate. Like the gate checks it catches what declares itself and
     * nothing more; {@link ToolActivitiesImpl} lists what still gets past.
     *
     * @throws IllegalArgumentException if {@code llm} is a {@code BudgetLlmClient}, or if
     *     {@code gate} may block waiting for a person ({@link ToolGate#waitsForAHuman()}),
     *     or was built for one run ({@link ToolGate#boundToOneRun()}), or if a tool in
     *     {@code tools} declares that it holds a gate that does either, or that it holds
     *     state built for one run
     */
    public static void register(Worker worker, LlmClient llm, ToolRegistry tools, ToolGate gate) {
        // Construct first, so a rejected client or gate leaves the worker untouched rather
        // than half-registered with a workflow type but no activities.
        register(worker, llm, new ToolActivitiesImpl(tools, gate));
    }

    /**
     * As above, but tightening the policy for the rest of a run once it has read somebody
     * else's words (#122).
     *
     * <p>Both of the floor's gates travel with the worker, for the reason the single-gate
     * form gives: a gate closes over local state and cannot be serialised into a workflow
     * input. What the <em>workflow</em> holds is one bit — whether this run has read
     * somebody else's words yet — which is run state and must not live on a worker-wide
     * activity instance.
     *
     * @throws IllegalArgumentException if {@code llm} is a {@code BudgetLlmClient}, or if
     *     <em>either</em> of the floor's policies may block waiting for a person or was
     *     built for one run. Both are checked in both cases, because a run reaches both — a
     *     floor whose tightened gate waits would otherwise be accepted and kill the run the
     *     first time anything returned a page, and one whose tightened gate is per-run would
     *     start screening strangers' calls at the same moment. Also if a tool in
     *     {@code tools} declares that it holds a gate that does either (#283)
     */
    public static void register(Worker worker, LlmClient llm, ToolRegistry tools,
                                TrustFloor floor) {
        register(worker, llm, new ToolActivitiesImpl(tools, floor));
    }

    /**
     * As {@link #register(Worker, LlmClient, ToolRegistry)}, but taking the tool
     * activity implementation directly. Package-private: it exists so tests can script
     * activity-level failures through the <em>same</em> wiring the public entry point
     * uses, rather than duplicating it and drifting from it.
     */
    static void register(Worker worker, LlmClient llm, ToolActivities toolActivities) {
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(toolActivities, "toolActivities");
        LlmActivitiesImpl llmActivities = new LlmActivitiesImpl(llm);
        worker.registerWorkflowImplementationTypes(AgentWorkflowImpl.class);
        worker.registerActivitiesImplementations(llmActivities, toolActivities);
    }

    /** A typed workflow stub bound to {@code taskQueue} for starting a run. */
    public static AgentWorkflow newStub(WorkflowClient client, String taskQueue) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(taskQueue, "taskQueue");
        return client.newWorkflowStub(AgentWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build());
    }
}
