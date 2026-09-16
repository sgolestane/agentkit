package dev.agentkit.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.AgentRun;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.verify.SelfVerifyingAgent;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Drives the fully-wired {@link EndToEndAgent} against a scripted fake model,
 * proving the subsystems compose into a working run without any network.
 */
class EndToEndAgentTest {

    private static final String MODEL = "fake-model";

    @Test
    void groundsAnswerInKnowledgeThenPassesVerification() {
        // Turn 1: search the knowledge base. Turn 2: answer. Then the verifier passes.
        FakeLlm llm = new FakeLlm()
                .then(FakeLlm.toolUse("t1", "knowledge_search",
                        Map.of("query", "return final-sale item")))
                .then(FakeLlm.text("Final-sale items are not returnable, even within 30 days."))
                .then(FakeLlm.text("PASS"));

        SelfVerifyingAgent agent = EndToEndAgent.build(llm, MODEL);
        AgentResult result = agent.run(Goal.of(
                "Can a customer return a final-sale item bought 10 days ago?"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).contains("not returnable");
        assertThat(llm.remaining()).isZero(); // script consumed exactly
    }

    @Test
    void gateDeniesTheDestructiveToolAsAnErrorResult() {
        // The model reveals delete_document via search, then tries to call it.
        FakeLlm llm = new FakeLlm()
                .then(FakeLlm.toolUse("s1", "search_tools", Map.of("query", "delete document")))
                .then(FakeLlm.toolUse("d1", "delete_document", Map.of("id", "doc-1")))
                .then(FakeLlm.text("I'm not able to delete that."))
                .then(FakeLlm.text("PASS"));

        // Capture the tool result the gate produced for delete_document.
        AtomicReference<ToolResult> deleteResult = new AtomicReference<>();
        AgentObserver observer = new AgentObserver() {
            @Override
            public void onToolResult(AgentRun run, int step,
                                     ToolInvocation proposed, ToolInvocation invocation,
                                     ToolResult result,
                                     dev.agentkit.core.tool.Disposition disposition) {
                if (invocation.name().equals(EndToEndAgent.DELETE_DOCUMENT)) {
                    deleteResult.set(result);
                }
            }
        };

        AgentResult result = EndToEndAgent.build(llm, MODEL, observer)
                .run(Goal.of("Delete document doc-1."));

        assertThat(result.isSuccess()).isTrue();       // run recovered after the denial
        assertThat(deleteResult.get()).isNotNull();     // the tool was actually invoked
        assertThat(deleteResult.get().isError()).isTrue();          // ...and blocked
        assertThat(deleteResult.get().content()).contains("policy"); // gate's reason
    }

    @Test
    void rememberedFactIsRecalledWithinTheRun() {
        FakeLlm llm = new FakeLlm()
                .then(FakeLlm.toolUse("r1", "remember", Map.of("note", "Customer prefers email contact.")))
                .then(FakeLlm.toolUse("r2", "recall", Map.of()))
                .then(FakeLlm.text("Noted: you prefer email contact."))
                .then(FakeLlm.text("PASS"));

        AgentResult result = EndToEndAgent.build(llm, MODEL)
                .run(Goal.of("Remember how the customer prefers to be contacted."));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).contains("email");
    }

    @Test
    void revealsAndUsesADeferredToolViaSearch() {
        // order_lookup is deferred; the model finds it via search_tools, then calls it.
        FakeLlm llm = new FakeLlm()
                .then(FakeLlm.toolUse("s1", "search_tools", Map.of("query", "order status")))
                .then(FakeLlm.toolUse("t1", "order_lookup", Map.of("orderId", "123")))
                .then(FakeLlm.text("Your order 123 has shipped."))
                .then(FakeLlm.text("PASS"));

        SelfVerifyingAgent agent = EndToEndAgent.build(llm, MODEL);
        AgentResult result = agent.run(Goal.of("What is the status of order 123?"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).contains("shipped");
    }

    @Test
    void retriesWhenVerificationFailsThenSucceeds() {
        // First attempt answers but the critic FAILs; second attempt passes.
        FakeLlm llm = new FakeLlm()
                .then(FakeLlm.text("Final-sale items can be returned."))     // attempt 1 answer
                .then(FakeLlm.text("FAIL\nThat contradicts the returns policy.")) // critic fails it
                .then(FakeLlm.text("Final-sale items are not returnable."))   // attempt 2 answer
                .then(FakeLlm.text("PASS"));                                   // critic passes

        SelfVerifyingAgent agent = EndToEndAgent.build(llm, MODEL);
        AgentResult result = agent.run(Goal.of("Are final-sale items returnable?"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).contains("not returnable");
    }
}
