package dev.agentkit.examples;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.context.ClearToolResultsEditor;
import dev.agentkit.core.context.ContextStrategies;
import dev.agentkit.core.context.ContextStrategy;
import dev.agentkit.core.context.SummarizingCompactor;
import dev.agentkit.core.knowledge.Document;
import dev.agentkit.core.knowledge.InMemoryKnowledgeBase;
import dev.agentkit.core.knowledge.KnowledgeBase;
import dev.agentkit.core.knowledge.KnowledgeTools;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.memory.MemoryStore;
import dev.agentkit.core.memory.MemoryTools;
import dev.agentkit.core.memory.WorkingMemory;
import dev.agentkit.core.reliability.RetryPolicy;
import dev.agentkit.core.reliability.RetryingLlmClient;
import dev.agentkit.core.reliability.ToolGates;
import dev.agentkit.core.tool.DisclosingToolRegistry;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.verify.LlmVerifier;
import dev.agentkit.core.verify.SelfVerifyingAgent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A single end-to-end wiring that exercises most AgentKit subsystems together:
 * a knowledge base, durable + working memory, progressive tool disclosure, a
 * gated destructive tool, engineered context, and outcome verification with
 * retry.
 *
 * <p>The moving parts:
 * <ul>
 *   <li><b>Knowledge base</b> — a small BM25 corpus exposed as {@code
 *       knowledge_search}.</li>
 *   <li><b>Memory</b> — a durable {@link MemoryStore} ({@code memory}) plus a
 *       per-run {@link WorkingMemory} scratchpad ({@code remember}/{@code
 *       recall}).</li>
 *   <li><b>Progressive disclosure</b> — a couple of long-tail tools are deferred
 *       behind {@code search_tools} rather than always advertised.</li>
 *   <li><b>Action gating</b> — a hard-to-reverse {@code delete_document} tool is
 *       denied by policy; a denial comes back as an error the model reacts to.</li>
 *   <li><b>Context engineering</b> — old tool results are cleared and long
 *       histories are summarised before each turn.</li>
 *   <li><b>Verification</b> — the whole agent is wrapped so it must pass an
 *       independent critic before its answer is accepted, retrying with feedback
 *       otherwise.</li>
 * </ul>
 *
 * <p>Each verification attempt builds a <em>fresh</em> {@link Agent} (via a
 * {@link Supplier}) so the per-run tool registry and scratchpad don't leak
 * between attempts; the knowledge base and durable memory are shared.
 */
public final class EndToEndAgent {

    /** Shared so the tool name and the gate's deny-set can never drift apart. */
    static final String DELETE_DOCUMENT = "delete_document";

    private EndToEndAgent() {
    }

    /** The knowledge, tools, context strategy, gate, and verifier wired together. */
    public static SelfVerifyingAgent build(LlmClient llm, String model) {
        return build(llm, model, AgentObserver.NONE);
    }

    /** As {@link #build(LlmClient, String)}, with an observer on each agent (for tests/telemetry). */
    static SelfVerifyingAgent build(LlmClient llm, String model, AgentObserver observer) {
        // Absorb transient model failures with backoff — the reliable client is used
        // by the agent, the compactor, and the verifier alike.
        LlmClient reliable = new RetryingLlmClient(llm, RetryPolicy.defaults());
        KnowledgeBase knowledge = InMemoryKnowledgeBase.bm25();
        knowledge.ingest(Document.of("returns-policy",
                "Customers may return most items within 30 days of delivery for a full refund, "
                        + "provided they have the original receipt. Final-sale items are not returnable."));
        knowledge.ingest(Document.of("shipping-policy",
                "Standard shipping takes 3-5 business days. Expedited shipping arrives next day."));

        // Durable, cross-session memory is shared across verification attempts.
        MemoryStore memory = MemoryStore.inMemory();

        ContextStrategy context = ContextStrategies.of(
                new ClearToolResultsEditor(6),
                SummarizingCompactor.builder(reliable, model)
                        .triggerTokens(120_000).keepRecentMessages(8).build());

        AgentConfig config = AgentConfig.builder(model)
                .systemPrompt("You are a careful support agent. Use knowledge_search to ground "
                        + "answers in policy, and remember durable facts about the customer. "
                        + "Never guess when a tool can tell you.")
                .maxSteps(12)
                .build();

        Supplier<Agent> agentFactory = () -> {
            WorkingMemory scratch = new WorkingMemory();
            ToolRegistry tools = DisclosingToolRegistry.builder()
                    .alwaysAvailable(KnowledgeTools.knowledgeSearchTool(knowledge))
                    .alwaysAvailable(MemoryTools.memoryTool(memory))
                    .alwaysAvailable(MemoryTools.rememberTool(scratch))
                    .alwaysAvailable(MemoryTools.recallTool(scratch))
                    // Long-tail tools stay out of context until searched for.
                    .deferred(orderLookupTool())
                    .deferred(deleteDocumentTool())
                    .build();

            return Agent.builder(reliable, tools, config)
                    .contextStrategy(context)
                    // Deny the destructive tool even if the model reveals and calls it.
                    .toolGate(ToolGates.denyTools(Set.of(DELETE_DOCUMENT)))
                    .observer(observer)
                    .build();
        };

        return new SelfVerifyingAgent(agentFactory, new LlmVerifier(reliable, model), /* maxAttempts */ 3);
    }

    private static FunctionTool orderLookupTool() {
        return FunctionTool.builder("order_lookup", "Look up the status of an order by its id")
                .schema(Map.of("type", "object",
                        "properties", Map.of("orderId", Map.of("type", "string")),
                        "required", List.of("orderId")))
                .handler(inv -> ToolResult.ok("Order " + inv.stringArgument("orderId") + " shipped."))
                .readOnly()
                .build();
    }

    private static FunctionTool deleteDocumentTool() {
        return FunctionTool.builder(DELETE_DOCUMENT, "Permanently delete a document (destructive)")
                .schema(Map.of("type", "object",
                        "properties", Map.of("id", Map.of("type", "string")),
                        "required", List.of("id")))
                .handler(inv -> ToolResult.ok("deleted " + inv.stringArgument("id")))
                // The point of the example: this is what a rehearsal must refuse, and the
                // declaration says so once rather than every gate naming it again.
                .sideEffects(SideEffects.EXTERNAL)
                .build();
    }

    /** Runs the example against the configured backend (see {@link ExampleBackend}). */
    public static void main(String[] args) {
        ExampleBackend backend = ExampleBackend.fromEnv();
        SelfVerifyingAgent agent = build(backend.llm(), backend.model());
        AgentResult result = agent.run(Goal.of(
                "A customer asks whether they can return a final-sale item bought 10 days ago. Answer."));
        System.out.println("stopReason=" + result.stopReason());
        System.out.println(result.output());
    }
}
