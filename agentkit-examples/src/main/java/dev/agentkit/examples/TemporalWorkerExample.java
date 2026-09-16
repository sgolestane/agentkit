package dev.agentkit.examples;

import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.temporal.AgentRunResult;
import dev.agentkit.temporal.DurableAgentRun;
import dev.agentkit.temporal.TemporalAgent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

/**
 * Runs the AgentKit loop durably on Temporal. This wires a worker (whose
 * activities hold the real model client and tools) and a client sharing the
 * AgentKit data converter, then starts one durable run.
 *
 * <p>Requires a reachable Temporal service (e.g. {@code temporal server
 * start-dev} on localhost) and credentials for the configured backend
 * ({@code ANTHROPIC_API_KEY} by default, or the AWS/Bedrock vars — see
 * {@link ExampleBackend}); it is a {@code main} demo rather than a test. The
 * in-memory, no-server durability tests live in {@code agentkit-temporal}.
 *
 * <p>{@code newLocalServiceStubs()} targets a local dev server; point at Temporal
 * Cloud by building the {@code WorkflowServiceStubs} with your Cloud target,
 * namespace, and API-key or mTLS auth instead (see the README) — the rest is
 * unchanged.
 */
public final class TemporalWorkerExample {

    private static final String TASK_QUEUE = "agentkit-examples";

    private TemporalWorkerExample() {
    }

    public static void main(String[] args) {
        ExampleBackend backend = ExampleBackend.fromEnv();
        LlmClient llm = backend.llm();
        ToolRegistry tools = new SimpleToolRegistry();

        // Client and worker must share the AgentKit data converter.
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder()
                .setDataConverter(TemporalAgent.dataConverter())
                .build());

        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TASK_QUEUE);
        // The gate is wired here rather than in the run's input: it is a lambda over
        // local state, like the tools, so it travels with the worker. Omitting it is
        // registerUngated, which says so in its name.
        TemporalAgent.register(worker, llm, tools, ToolGates.readOnly());
        factory.start();

        AgentConfig config = AgentConfig.builder(backend.model())
                .systemPrompt("You are a helpful assistant.")
                .maxSteps(8)
                .build();

        AgentRunResult result = TemporalAgent.newStub(client, TASK_QUEUE)
                .run(DurableAgentRun.of(Goal.of("Say hello and explain what durable execution is."), config));

        System.out.println("stopReason=" + result.stopReason());
        System.out.println(result.output());

        // Tidy shutdown; System.exit is needed because the worker threads are non-daemon.
        factory.shutdown();
        service.shutdown();
        System.exit(0);
    }
}
