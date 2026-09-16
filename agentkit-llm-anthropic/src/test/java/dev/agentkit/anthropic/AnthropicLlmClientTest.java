package dev.agentkit.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import dev.agentkit.core.llm.LlmRequest;
import com.fasterxml.jackson.databind.JsonNode;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.OutputSchema;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.tool.ToolSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicLlmClientTest {

    @Test
    void toParamsMapsCoreFields() {
        LlmRequest request = LlmRequest.builder("claude-opus-4-8")
                .system("be helpful")
                .maxTokens(2048)
                .addMessage(Message.user("hello"))
                .tools(List.of(new ToolSpec("search", "search the web",
                        Map.of("type", "object",
                                "properties", Map.of("q", Map.of("type", "string")),
                                "required", List.of("q")))))
                .build();

        MessageCreateParams params = AnthropicLlmClient.toParams(request);

        assertThat(params.maxTokens()).isEqualTo(2048L);
        assertThat(params.model().toString()).contains("claude-opus-4-8");
        assertThat(params.system()).isPresent();
        assertThat(params.messages()).hasSize(1);
        assertThat(params.tools()).isPresent();
        assertThat(params.tools().orElseThrow()).hasSize(1);
    }

    @Test
    void toParamsMapsToolResultMessages() {
        LlmRequest request = LlmRequest.builder("claude-opus-4-8")
                .addMessage(Message.user("go"))
                .addMessage(Message.of(Role.ASSISTANT,
                        new dev.agentkit.core.message.ToolUseBlock("t1", "search", Map.of("q", "x"))))
                .addMessage(Message.of(Role.USER, ToolResultBlock.ok("t1", "result")))
                .build();

        MessageCreateParams params = AnthropicLlmClient.toParams(request);

        assertThat(params.messages()).hasSize(3);
    }

    @Test
    void aRefusedCallIsStillEchoedAsAToolUseSoItsResultBlockIsValid() {
        // #246. A call whose arguments this framework will not carry becomes an
        // UnusableToolUseBlock in the assistant turn, and the turn is echoed back on every
        // later request. The API matches a tool_result to a tool_use by id and rejects a
        // result with no call, so dropping the block here would make the very request that
        // carries its refusal invalid.
        LlmRequest request = LlmRequest.builder("claude-opus-4-8")
                .addMessage(Message.user("go"))
                .addMessage(Message.of(Role.ASSISTANT,
                        new dev.agentkit.core.message.UnusableToolUseBlock(
                                "t1", "search", "that call was refused")))
                .addMessage(Message.of(Role.USER,
                        ToolResultBlock.error("t1", "that call was refused")))
                .build();

        MessageCreateParams params = AnthropicLlmClient.toParams(request);

        assertThat(params.messages()).hasSize(3);
        var assistant = params.messages().get(1);
        var blocks = assistant.content().blockParams().orElseThrow();
        assertThat(blocks).hasSize(1);
        // Asked as a presence check rather than reached for with orElseThrow: an adapter
        // that dropped the block leaves an assistant turn with no content at all, which
        // toParams then fills with its "(no content)" placeholder — so the size assertion
        // above passes and only this one says what went wrong.
        assertThat(blocks.get(0).toolUse())
                .as("the refused call vanished, orphaning its tool_result")
                .isPresent();
        var toolUse = blocks.get(0).toolUse().orElseThrow();
        assertThat(toolUse.id()).isEqualTo("t1");
        assertThat(toolUse.name()).isEqualTo("search");
        // Empty arguments, not the model's tree: nothing froze it and the block does not
        // hold it. The refusal travels in the tool_result, not in the echo.
        assertThat(toolUse.input()._additionalProperties()).isEmpty();
    }

    @Test
    void modelResolverRewritesTheWireModel() {
        LlmRequest request = LlmRequest.builder("claude-opus-4-8")
                .addMessage(Message.user("hi")).build();
        ModelResolver resolver = ModelResolver.ofMap(Map.of(
                "claude-opus-4-8", "arn:aws:bedrock:us-east-1:123:application-inference-profile/abc"));

        MessageCreateParams params = AnthropicLlmClient.toParams(request, resolver);

        assertThat(params.model().toString())
                .contains("application-inference-profile/abc")
                .doesNotContain("claude-opus-4-8");
    }

    @Test
    void modelResolverOfMapPassesUnmappedIdsThrough() {
        assertThat(ModelResolver.ofMap(Map.of("a", "b")).resolve("z")).isEqualTo("z");
        assertThat(ModelResolver.IDENTITY.resolve("claude-opus-4-8")).isEqualTo("claude-opus-4-8");
        // An empty map is identity.
        assertThat(ModelResolver.ofMap(Map.of()).resolve("anything")).isEqualTo("anything");
    }

    @Test
    void modelResolverOfMapStrictRejectsUnmappedIds() {
        ModelResolver strict = ModelResolver.ofMapStrict(Map.of("a", "b"));
        assertThat(strict.resolve("a")).isEqualTo("b");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> strict.resolve("z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("z");
    }

    @Test
    void modelResolverAndThenComposes() {
        ModelResolver normalise = m -> m.replace("us.", "");
        ModelResolver mapped = ModelResolver.ofMap(Map.of("anthropic.claude-opus-4-8", "arn:profile"));
        assertThat(normalise.andThen(mapped).resolve("us.anthropic.claude-opus-4-8")).isEqualTo("arn:profile");
    }

    private static LlmRequest requestWithSystemToolAndMessage() {
        return LlmRequest.builder("claude-opus-4-8")
                .system("be helpful")
                .addMessage(Message.user("hello"))
                .tools(List.of(new ToolSpec("search", "search the web",
                        Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string")),
                                "required", List.of("q")))))
                .build();
    }

    /** The cache_control on the last content block of the last message (a text block). */
    private static java.util.Optional<CacheControlEphemeral> lastMessageBreakpoint(MessageCreateParams params) {
        List<ContentBlockParam> blocks = params.messages()
                .get(params.messages().size() - 1).content().asBlockParams();
        return blocks.get(blocks.size() - 1).text().orElseThrow().cacheControl();
    }

    @Test
    void noCachingLeavesSystemAsAStringWithNoBreakpoints() {
        MessageCreateParams params = AnthropicLlmClient.toParams(
                requestWithSystemToolAndMessage(), ModelResolver.IDENTITY, CachePolicy.NONE);

        assertThat(params.system().orElseThrow().isString()).isTrue();
        assertThat(lastMessageBreakpoint(params)).isEmpty();
        assertThat(params.tools().orElseThrow().get(0).tool().orElseThrow().cacheControl()).isEmpty();
        assertThat(params.cacheControl()).isEmpty(); // no top-level auto-caching either
    }

    @Test
    void cachingMarksTheSystemPrefixAndTheRollingConversationExplicitly() {
        MessageCreateParams params = AnthropicLlmClient.toParams(
                requestWithSystemToolAndMessage(), ModelResolver.IDENTITY, CachePolicy.EPHEMERAL_5M);

        // The system prompt renders as a cache-marked text block (caches tools+system).
        assertThat(params.system().orElseThrow().isTextBlockParams()).isTrue();
        assertThat(params.system().orElseThrow().asTextBlockParams().get(0)
                .cacheControl().orElseThrow().ttl()).contains(CacheControlEphemeral.Ttl.TTL_5M);
        // The rolling breakpoint is an EXPLICIT per-block marker on the last message
        // (not top-level auto-caching, which is unsupported on Bedrock).
        assertThat(lastMessageBreakpoint(params)).isPresent();
        assertThat(params.cacheControl()).isEmpty();
        // The tool needs no breakpoint of its own — the system breakpoint covers it.
        assertThat(params.tools().orElseThrow().get(0).tool().orElseThrow().cacheControl()).isEmpty();
    }

    @Test
    void cachingWithoutASystemPromptMarksTheLastTool() {
        LlmRequest request = LlmRequest.builder("claude-opus-4-8")
                .addMessage(Message.user("hi"))
                .tools(List.of(
                        new ToolSpec("a", "tool a", Map.of("type", "object")),
                        new ToolSpec("b", "tool b", Map.of("type", "object"))))
                .build();

        MessageCreateParams params = AnthropicLlmClient.toParams(
                request, ModelResolver.IDENTITY, CachePolicy.EPHEMERAL_5M);

        assertThat(params.system()).isEmpty();
        assertThat(params.tools().orElseThrow().get(0).tool().orElseThrow().cacheControl()).isEmpty();
        assertThat(params.tools().orElseThrow().get(1).tool().orElseThrow().cacheControl()).isPresent();
        assertThat(lastMessageBreakpoint(params)).isPresent(); // rolling conversation breakpoint
    }

    @Test
    void cachingMarksAToolResultLastBlock() {
        LlmRequest request = LlmRequest.builder("claude-opus-4-8")
                .system("sys")
                .addMessage(Message.user("go"))
                .addMessage(Message.of(Role.ASSISTANT,
                        new dev.agentkit.core.message.ToolUseBlock("t1", "search", Map.of("q", "x"))))
                .addMessage(Message.of(Role.USER, ToolResultBlock.ok("t1", "result")))
                .build();

        MessageCreateParams params = AnthropicLlmClient.toParams(
                request, ModelResolver.IDENTITY, CachePolicy.EPHEMERAL_5M);

        // The last message's tool_result block carries the explicit breakpoint...
        List<ContentBlockParam> lastBlocks = params.messages()
                .get(params.messages().size() - 1).content().asBlockParams();
        assertThat(lastBlocks.get(lastBlocks.size() - 1).toolResult().orElseThrow().cacheControl())
                .isPresent();

        // ...and only the last message: earlier messages carry no breakpoint.
        assertThat(params.messages().get(0).content().asBlockParams().get(0)
                .text().orElseThrow().cacheControl()).isEmpty();
        assertThat(params.messages().get(1).content().asBlockParams().get(0)
                .toolUse().orElseThrow().cacheControl()).isEmpty();
    }

    @Test
    void oneHourPolicyUsesTheOneHourTtl() {
        MessageCreateParams params = AnthropicLlmClient.toParams(
                requestWithSystemToolAndMessage(), ModelResolver.IDENTITY, CachePolicy.EPHEMERAL_1H);

        assertThat(params.system().orElseThrow().asTextBlockParams().get(0)
                .cacheControl().orElseThrow().ttl()).contains(CacheControlEphemeral.Ttl.TTL_1H);
    }

    @Test
    void toolInputExamplesAreRenderedOntoTheWireTool() {
        LlmRequest request = LlmRequest.builder("claude-opus-4-8")
                .addMessage(Message.user("go"))
                .tools(List.of(new ToolSpec("search", "search the web",
                        Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string"))),
                        List.of(Map.of("q", "weather in Paris")))))
                .build();

        MessageCreateParams params = AnthropicLlmClient.toParams(request);

        var tool = params.tools().orElseThrow().get(0).tool().orElseThrow();
        var examples = tool.inputExamples().orElseThrow();
        assertThat(examples).hasSize(1);
        assertThat(examples.get(0)._additionalProperties().get("q").toString()).contains("weather in Paris");
    }

    @Test
    void toolsWithoutExamplesRenderNoInputExamples() {
        LlmRequest request = LlmRequest.builder("claude-opus-4-8")
                .addMessage(Message.user("go"))
                .tools(List.of(new ToolSpec("search", "search the web",
                        Map.of("type", "object"))))
                .build();

        MessageCreateParams params = AnthropicLlmClient.toParams(request);

        var tool = params.tools().orElseThrow().get(0).tool().orElseThrow();
        assertThat(tool.inputExamples()).isEmpty();
    }

    @Test
    void anOutputSchemaBecomesAnOutputConfigOnTheWire() throws Exception {
        LlmRequest request = LlmRequest.builder("claude-opus-4-8")
                .addMessage(Message.user("classify this"))
                .outputSchema(OutputSchema.ofProperties("sentiment",
                        Map.of("label", Map.of("type", "string"))))
                .build();

        MessageCreateParams params = AnthropicLlmClient.toParams(request);

        // Assert the serialized body, not our own map read back out: the schema lands
        // flat under output_config.format.schema, and `type` — the one field this
        // adapter leaves to the SDK's default — must be json_schema.
        JsonNode outputConfig = ObjectMappers.jsonMapper()
                .<JsonNode>valueToTree(params._body())
                .path("output_config");
        assertThat(outputConfig).isEqualTo(ObjectMappers.jsonMapper().readTree("""
                {"format":{"type":"json_schema","schema":{
                  "type":"object",
                  "properties":{"label":{"type":"string"}},
                  "required":["label"],
                  "additionalProperties":false}}}"""));
    }

    @Test
    void nullsInsideTheSchemaSurviveTheWire() throws Exception {
        // The SDK's own mapper is configured NON_ABSENT and strips null map values, so
        // routing the schema through it turned "const": null — a real JSON Schema
        // constraint — into an unconstrained {}. The two adapters must not disagree
        // about what the same OutputSchema means.
        Map<String, Object> constNull = new java.util.LinkedHashMap<>();
        constNull.put("const", null);
        LlmRequest request = LlmRequest.builder("claude-opus-4-8")
                .addMessage(Message.user("go"))
                .outputSchema(OutputSchema.ofProperties("s", Map.of("nothing", constNull)))
                .build();

        JsonNode schema = ObjectMappers.jsonMapper()
                .<JsonNode>valueToTree(AnthropicLlmClient.toParams(request)._body())
                .path("output_config").path("format").path("schema");

        assertThat(schema.path("properties").path("nothing"))
                .isEqualTo(ObjectMappers.jsonMapper().readTree("""
                        {"const":null}"""));
    }

    @Test
    void noOutputSchemaLeavesDecodingUnconstrained() {
        MessageCreateParams params = AnthropicLlmClient.toParams(LlmRequest.builder("claude-opus-4-8")
                .addMessage(Message.user("hi")).build());
        assertThat(params.outputConfig()).isEmpty();
    }

    @Test
    void stopReasonMappingCoversAllProviderReasons() {
        assertThat(AnthropicLlmClient.mapStopReason(StopReason.TOOL_USE)).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(AnthropicLlmClient.mapStopReason(StopReason.END_TURN)).isEqualTo(LlmStopReason.END_TURN);
        assertThat(AnthropicLlmClient.mapStopReason(StopReason.STOP_SEQUENCE)).isEqualTo(LlmStopReason.END_TURN);
        assertThat(AnthropicLlmClient.mapStopReason(StopReason.MAX_TOKENS)).isEqualTo(LlmStopReason.MAX_TOKENS);
        assertThat(AnthropicLlmClient.mapStopReason(StopReason.REFUSAL)).isEqualTo(LlmStopReason.REFUSAL);
        assertThat(AnthropicLlmClient.mapStopReason(StopReason.PAUSE_TURN)).isEqualTo(LlmStopReason.PAUSE);
        assertThat(AnthropicLlmClient.mapStopReason(null)).isEqualTo(LlmStopReason.OTHER);
    }
}
