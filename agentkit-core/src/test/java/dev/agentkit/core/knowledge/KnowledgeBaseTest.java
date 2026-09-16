package dev.agentkit.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeBaseTest {

    private static KnowledgeBase populated() {
        KnowledgeBase kb = InMemoryKnowledgeBase.bm25();
        kb.ingestAll(List.of(
                Document.of("policy", "Refunds are available within 30 days of purchase with a receipt."),
                Document.of("shipping", "Orders ship within two business days via standard courier.")));
        return kb;
    }

    @Test
    void ingestAndSearchReturnsRelevantChunk() {
        KnowledgeBase kb = populated();
        List<SearchResult> results = kb.search("how long do I have for a refund", 3);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).chunk().documentId()).isEqualTo("policy");
        assertThat(kb.chunkCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void knowledgeSearchToolFormatsResults() {
        Tool tool = KnowledgeTools.knowledgeSearchTool(populated());
        ToolResult result = tool.execute(new ToolInvocation("1", "knowledge_search",
                Map.of("query", "refund receipt")));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("policy#0").contains("Refunds are available");
    }

    @Test
    void knowledgeSearchToolReportsNoResults() {
        Tool tool = KnowledgeTools.knowledgeSearchTool(populated());
        ToolResult result = tool.execute(new ToolInvocation("1", "knowledge_search",
                Map.of("query", "zzzz nonexistent qqqq")));
        assertThat(result.content()).contains("No results");
    }

    @Test
    void knowledgeSearchToolRejectsBlankQuery() {
        Tool tool = KnowledgeTools.knowledgeSearchTool(populated());
        ToolResult result = tool.execute(new ToolInvocation("1", "knowledge_search",
                Map.of("query", "  ")));
        assertThat(result.isError()).isTrue();
    }

    @Test
    void vectorKnowledgeBaseSearches() {
        KnowledgeBase kb = InMemoryKnowledgeBase.vector(new HashingEmbeddingModel(64));
        kb.ingestAll(List.of(
                Document.of("db", "database migration schema indexes"),
                Document.of("food", "cooking recipe dinner ingredients")));
        List<SearchResult> results = kb.search("database schema", 3);
        assertThat(results.get(0).chunk().documentId()).isEqualTo("db");
    }

    @Test
    void citationIncludesSourceMetadata() {
        KnowledgeBase kb = InMemoryKnowledgeBase.bm25();
        kb.ingest(new Document("policy", "Refunds within 30 days with a receipt.",
                Map.of("title", "Refund Policy", "url", "https://example.com/refunds")));
        ToolResult result = KnowledgeTools.knowledgeSearchTool(kb)
                .execute(new ToolInvocation("1", "knowledge_search", Map.of("query", "receipt")));
        assertThat(result.content()).contains("Refund Policy").contains("https://example.com/refunds");
    }

    @Test
    void agentUsesKnowledgeSearchTool() {
        dev.agentkit.core.tool.SimpleToolRegistry registry = new dev.agentkit.core.tool.SimpleToolRegistry()
                .register(KnowledgeTools.knowledgeSearchTool(populated()));
        dev.agentkit.core.llm.FakeLlmClient llm = new dev.agentkit.core.llm.FakeLlmClient(
                dev.agentkit.core.llm.FakeLlmClient.toolUse("k1", "knowledge_search",
                        Map.of("query", "receipt")),
                dev.agentkit.core.llm.FakeLlmClient.text("You have 30 days."));

        dev.agentkit.core.agent.Agent agent = new dev.agentkit.core.agent.Agent(llm, registry,
                dev.agentkit.core.agent.AgentConfig.builder("m").maxSteps(5).build());
        dev.agentkit.core.agent.AgentResult r = agent.run(
                dev.agentkit.core.agent.Goal.of("refund window?"));

        assertThat(r.isSuccess()).isTrue();
        var toolMsg = llm.received().get(1).messages().get(2);
        assertThat(((dev.agentkit.core.message.ToolResultBlock) toolMsg.content().get(0)).content())
                .contains("Refunds are available");
    }

    @Test
    void knowledgeSearchToolClampsMaxResults() {
        Tool tool = KnowledgeTools.knowledgeSearchTool(populated());
        // Requesting a huge max_results must not error (it is capped internally).
        ToolResult result = tool.execute(new ToolInvocation("1", "knowledge_search",
                Map.of("query", "refund", "max_results", 9999)));
        assertThat(result.isError()).isFalse();
    }
}
