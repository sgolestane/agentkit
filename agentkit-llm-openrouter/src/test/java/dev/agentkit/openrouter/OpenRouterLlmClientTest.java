package dev.agentkit.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.OutputSchema;
import dev.agentkit.core.llm.StreamHandler;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.message.UnusableToolUseBlock;
import dev.agentkit.core.tool.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenRouterLlmClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A scripted transport: captures what was sent and returns a canned response. */
    private static final class FakeTransport implements OpenRouterTransport {
        String url;
        Map<String, String> headers;
        String body;
        private final HttpResult response;
        private final Integer streamStatus;
        private final List<String> streamLines;

        FakeTransport(HttpResult response) {
            this(response, null, null);
        }

        private FakeTransport(HttpResult response, Integer streamStatus, List<String> streamLines) {
            this.response = response;
            this.streamStatus = streamStatus;
            this.streamLines = streamLines;
        }

        /** A transport that scripts an SSE stream ({@code lines}) for postStreaming. */
        static FakeTransport streaming(int status, List<String> lines) {
            return new FakeTransport(null, status, lines);
        }

        @Override
        public HttpResult post(String url, Map<String, String> headers, String jsonBody) {
            capture(url, headers, jsonBody);
            return response;
        }

        @Override
        public StreamResult postStreaming(String url, Map<String, String> headers, String jsonBody) {
            capture(url, headers, jsonBody);
            if (streamStatus == null) {
                throw new UnsupportedOperationException("no stream scripted");
            }
            return new StreamResult(streamStatus, streamLines.stream());
        }

        private void capture(String url, Map<String, String> headers, String jsonBody) {
            this.url = url;
            this.headers = headers;
            this.body = jsonBody;
        }
    }

    // --- SSE chunk builders (Jackson-built, so no manual JSON escaping) ------

    private static String contentChunk(String content) {
        var chunk = MAPPER.createObjectNode();
        chunk.putArray("choices").addObject().putObject("delta").put("content", content);
        return "data: " + chunk;
    }

    private static String finishChunk(String finishReason) {
        var chunk = MAPPER.createObjectNode();
        var choice = chunk.putArray("choices").addObject();
        choice.putObject("delta");
        choice.put("finish_reason", finishReason);
        return "data: " + chunk;
    }

    private static String usageChunk(int promptTokens, int completionTokens) {
        var chunk = MAPPER.createObjectNode();
        chunk.putArray("choices"); // final usage chunk carries empty choices
        var usage = chunk.putObject("usage");
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        return "data: " + chunk;
    }

    private static String toolCallChunk(int index, String id, String name, String argumentsFragment) {
        var chunk = MAPPER.createObjectNode();
        var call = chunk.putArray("choices").addObject().putObject("delta").putArray("tool_calls").addObject();
        call.put("index", index);
        if (id != null) {
            call.put("id", id);
        }
        call.put("type", "function");
        var function = call.putObject("function");
        if (name != null) {
            function.put("name", name);
        }
        function.put("arguments", argumentsFragment);
        return "data: " + chunk;
    }

    private static JsonNode captureBody(FakeTransport transport) throws Exception {
        return MAPPER.readTree(transport.body);
    }

    // --- request mapping ----------------------------------------------------

    @Test
    void toRequestJsonMapsCoreFields() {
        LlmRequest request = LlmRequest.builder("anthropic/claude-opus-4-8")
                .system("be helpful")
                .maxTokens(2048)
                .addMessage(Message.user("hello"))
                .tools(List.of(new ToolSpec("search", "search the web",
                        Map.of("type", "object",
                                "properties", Map.of("q", Map.of("type", "string")),
                                "required", List.of("q")))))
                .build();

        JsonNode body = OpenRouterLlmClient.toRequestJson(request);

        assertThat(body.path("model").asText()).isEqualTo("anthropic/claude-opus-4-8");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(2048);
        // system prompt is the first message, then the user turn
        assertThat(body.path("messages")).hasSize(2);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("be helpful");
        assertThat(body.path("messages").get(1).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("hello");
        // one function tool with its schema as parameters
        assertThat(body.path("tools")).hasSize(1);
        JsonNode fn = body.path("tools").get(0).path("function");
        assertThat(fn.path("name").asText()).isEqualTo("search");
        assertThat(fn.path("parameters").path("required").get(0).asText()).isEqualTo("q");
    }

    @Test
    void toRequestJsonMapsAssistantToolUseAndUserToolResult() {
        LlmRequest request = LlmRequest.builder("openai/gpt-4o")
                .addMessage(Message.user("go"))
                .addMessage(Message.of(Role.ASSISTANT, new ToolUseBlock("t1", "search", Map.of("q", "x"))))
                .addMessage(Message.of(Role.USER, ToolResultBlock.ok("t1", "result text")))
                .build();

        JsonNode messages = OpenRouterLlmClient.toRequestJson(request).path("messages");

        assertThat(messages).hasSize(3);
        // assistant with a tool_call and null content
        JsonNode assistant = messages.get(1);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        assertThat(assistant.path("content").isNull()).isTrue();
        JsonNode call = assistant.path("tool_calls").get(0);
        assertThat(call.path("id").asText()).isEqualTo("t1");
        assertThat(call.path("type").asText()).isEqualTo("function");
        assertThat(call.path("function").path("name").asText()).isEqualTo("search");
        // arguments are a JSON-encoded STRING, not an object
        assertThat(call.path("function").path("arguments").isTextual()).isTrue();
        assertThat(call.path("function").path("arguments").asText()).isEqualTo("{\"q\":\"x\"}");
        // the tool result is its own tool message keyed by tool_call_id
        JsonNode tool = messages.get(2);
        assertThat(tool.path("role").asText()).isEqualTo("tool");
        assertThat(tool.path("tool_call_id").asText()).isEqualTo("t1");
        assertThat(tool.path("content").asText()).isEqualTo("result text");
    }

    @Test
    void aRefusedCallIsStillEchoedAsAToolCallSoItsResultMessageIsValid(){
        // #246. A call whose arguments this framework will not carry becomes an
        // UnusableToolUseBlock in the assistant turn, and the turn is still echoed back on
        // every later request. OpenAI matches a tool message to a tool_call by id and
        // rejects one with no call, so dropping the block here would make the very request
        // that carries its refusal invalid — a failure no fake client would have noticed,
        // because the fakes never validate the pairing.
        LlmRequest request = LlmRequest.builder("openai/gpt-4o")
                .addMessage(Message.user("go"))
                .addMessage(Message.of(Role.ASSISTANT,
                        new UnusableToolUseBlock("t1", "search", "that call was refused")))
                .addMessage(Message.of(Role.USER,
                        ToolResultBlock.error("t1", "that call was refused")))
                .build();

        JsonNode messages = OpenRouterLlmClient.toRequestJson(request).path("messages");

        JsonNode assistant = messages.get(1);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        JsonNode calls = assistant.path("tool_calls");
        assertThat(calls).as("the refused call vanished, orphaning its tool message")
                .hasSize(1);
        assertThat(calls.get(0).path("id").asText()).isEqualTo("t1");
        assertThat(calls.get(0).path("function").path("name").asText()).isEqualTo("search");
        // Empty arguments, not the model's tree: nothing froze it and the block does not
        // hold it. The refusal travels in the tool message, not in the echo.
        assertThat(calls.get(0).path("function").path("arguments").asText()).isEqualTo("{}");
        assertThat(messages.get(2).path("tool_call_id").asText()).isEqualTo("t1");
    }

    @Test
    void optionsArePassedThroughButFrameworkFieldsWin() {
        LlmRequest request = LlmRequest.builder("openai/gpt-4o")
                .addMessage(Message.user("hi"))
                .option("temperature", 0.2)
                .option("provider", Map.of("order", List.of("Anthropic")))
                .option("model", "should-be-overridden")
                .build();

        JsonNode body = OpenRouterLlmClient.toRequestJson(request);

        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.path("provider").path("order").get(0).asText()).isEqualTo("Anthropic");
        // the framework-controlled model wins over an options value of the same key
        assertThat(body.path("model").asText()).isEqualTo("openai/gpt-4o");
    }

    @Test
    void anOutputSchemaBecomesAnOpenAiResponseFormat() throws Exception {
        LlmRequest request = LlmRequest.builder("openai/gpt-4o")
                .addMessage(Message.user("classify this"))
                .outputSchema(OutputSchema.ofProperties("sentiment",
                        Map.of("label", Map.of("type", "string"))))
                .build();

        JsonNode body = OpenRouterLlmClient.toRequestJson(request);

        assertThat(body.path("response_format")).isEqualTo(MAPPER.readTree("""
                {"type":"json_schema","json_schema":{
                  "name":"sentiment",
                  "strict":true,
                  "schema":{
                    "type":"object",
                    "properties":{"label":{"type":"string"}},
                    "required":["label"],
                    "additionalProperties":false}}}"""));
    }

    @Test
    void anOutputSchemaPinsRoutingToProvidersThatHonourIt() {
        // OpenRouter silently ignores parameters the routed provider does not support,
        // so without require_parameters a constrained call can come back as prose with
        // a 200. This is the difference between failing and being quietly wrong.
        JsonNode body = OpenRouterLlmClient.toRequestJson(LlmRequest.builder("openai/gpt-4o")
                .addMessage(Message.user("classify this"))
                .outputSchema(OutputSchema.ofProperties("s", Map.of("label", Map.of("type", "string"))))
                .build());

        assertThat(body.path("provider").path("require_parameters").asBoolean()).isTrue();
    }

    @Test
    void requireParametersIsMergedIntoProviderRoutingRatherThanReplacingIt() {
        JsonNode body = OpenRouterLlmClient.toRequestJson(LlmRequest.builder("openai/gpt-4o")
                .addMessage(Message.user("classify this"))
                .option("provider", Map.of("order", List.of("Anthropic")))
                .outputSchema(OutputSchema.ofProperties("s", Map.of("label", Map.of("type", "string"))))
                .build());

        assertThat(body.path("provider").path("order").get(0).asText()).isEqualTo("Anthropic");
        assertThat(body.path("provider").path("require_parameters").asBoolean()).isTrue();
    }

    @Test
    void noOutputSchemaMeansNoResponseFormat() {
        JsonNode body = OpenRouterLlmClient.toRequestJson(LlmRequest.builder("openai/gpt-4o")
                .addMessage(Message.user("hi")).build());
        assertThat(body.has("response_format")).isFalse();
        assertThat(body.has("provider")).isFalse();
    }

    @Test
    void aResponseFormatPassedViaOptionsCannotLeakThrough() {
        // response_format is framework-controlled, like tools: an options value must not
        // constrain decoding behind LlmRequest.outputSchema()'s back.
        JsonNode body = OpenRouterLlmClient.toRequestJson(LlmRequest.builder("openai/gpt-4o")
                .addMessage(Message.user("hi"))
                .option("response_format", Map.of("type", "json_object"))
                .build());

        assertThat(body.has("response_format")).isFalse();
    }

    @Test
    void theFrameworkResponseFormatWinsOverAnOptionsValue() {
        JsonNode body = OpenRouterLlmClient.toRequestJson(LlmRequest.builder("openai/gpt-4o")
                .addMessage(Message.user("hi"))
                .option("response_format", Map.of("type", "json_object"))
                .outputSchema(OutputSchema.ofProperties("s", Map.of("label", Map.of("type", "string"))))
                .build());

        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_schema");
    }

    @Test
    void systemRoleInTheMessageListIsRejected() {
        LlmRequest request = LlmRequest.builder("openai/gpt-4o")
                .addMessage(Message.system("no"))
                .build();

        assertThatThrownBy(() -> OpenRouterLlmClient.toRequestJson(request))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("SYSTEM");
    }

    // --- response mapping ---------------------------------------------------

    private static LlmResponse generateWith(OpenRouterTransport.HttpResult response, LlmRequest request) {
        return OpenRouterLlmClient.builder("test-key")
                .transport(new FakeTransport(response))
                .build()
                .generate(request);
    }

    private static LlmRequest simpleRequest() {
        return LlmRequest.builder("openai/gpt-4o").addMessage(Message.user("hi")).build();
    }

    @Test
    void generateParsesATextResponse() {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello there\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";

        LlmResponse response = generateWith(new OpenRouterTransport.HttpResult(200, json), simpleRequest());

        assertThat(response.message().role()).isEqualTo(Role.ASSISTANT);
        assertThat(response.message().text()).isEqualTo("Hello there");
        assertThat(response.stopReason()).isEqualTo(LlmStopReason.END_TURN);
        assertThat(response.usage().inputTokens()).isEqualTo(10);
        assertThat(response.usage().outputTokens()).isEqualTo(5);
        assertThat(response.rawStopReason()).contains("stop");
    }

    @Test
    void generateParsesAToolCallResponse() {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"search\",\"arguments\":\"{\\\"q\\\":\\\"weather\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}";

        LlmResponse response = generateWith(new OpenRouterTransport.HttpResult(200, json), simpleRequest());

        assertThat(response.requestsTools()).isTrue();
        assertThat(response.stopReason()).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(response.message().content()).hasSize(1);
        ToolUseBlock use = (ToolUseBlock) response.message().content().get(0);
        assertThat(use.id()).isEqualTo("call_1");
        assertThat(use.name()).isEqualTo("search");
        assertThat(use.input()).containsEntry("q", "weather");
    }

    @Test
    void aToolCallResponseIsToolUseEvenIfFinishReasonSaysStop() {
        // Some providers label a tool-call turn "stop"; the presence of tool_calls wins.
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"c\",\"type\":\"function\","
                + "\"function\":{\"name\":\"t\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"stop\"}]}";

        LlmResponse response = generateWith(new OpenRouterTransport.HttpResult(200, json), simpleRequest());

        assertThat(response.stopReason()).isEqualTo(LlmStopReason.TOOL_USE);
    }

    @Test
    void argumentsPastTheDepthCapParseIntoARefusalRatherThanThrowingOutOfTheTurn() {
        // #246, driven through the real parse rather than through a fake client. A provider
        // is free to send this and it is legal JSON; before, the ToolUseBlock constructor
        // threw here and took the whole turn with it, so the run ended and the model was
        // never told why. The turn now parses, with the refusal standing in for the call.
        StringBuilder deep = new StringBuilder();
        int levels = dev.agentkit.core.util.Frozen.MAX_DEPTH + 1;
        for (int i = 0; i < levels; i++) {
            deep.append("{\"n\":");
        }
        deep.append("\"x\"");
        deep.append("}".repeat(levels));
        String arguments = MAPPER.valueToTree(deep.toString()).toString();
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"t1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"search\",\"arguments\":" + arguments + "}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}";

        LlmResponse response =
                generateWith(new OpenRouterTransport.HttpResult(200, json), simpleRequest());

        assertThat(response.stopReason()).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(response.message().content())
                .as("the turn no longer carries the call the provider sent")
                .hasSize(1);
        assertThat(response.message().content().get(0))
                .isInstanceOf(UnusableToolUseBlock.class);
        UnusableToolUseBlock refused =
                (UnusableToolUseBlock) response.message().content().get(0);
        assertThat(refused.id()).isEqualTo("t1");
        assertThat(refused.name()).isEqualTo("search");
        assertThat(refused.refusal()).contains("No tool ran, no gate was asked");
    }

    /** A non-streamed turn carrying one tool call whose {@code arguments} string is {@code raw}. */
    private static LlmResponse aTurnWhoseArgumentsAre(String raw) {
        var message = MAPPER.createObjectNode();
        message.put("role", "assistant").putNull("content");
        var call = message.putArray("tool_calls").addObject();
        call.put("id", "c").put("type", "function");
        call.putObject("function").put("name", "t").put("arguments", raw);
        var root = MAPPER.createObjectNode();
        var choice = root.putArray("choices").addObject();
        choice.set("message", message);
        choice.put("finish_reason", "tool_calls");
        return generateWith(new OpenRouterTransport.HttpResult(200, root.toString()),
                simpleRequest());
    }

    @Test
    void malformedToolArgumentsAreRefusedRatherThanRunWithNoArguments() {
        // #271, and this test is the one that used to pin the behaviour the issue
        // questions. It was called malformedToolArgumentsBecomeEmptyRatherThanCrashing and
        // asserted `use.input()).isEmpty()` — a ToolUseBlock a runner will gate and run.
        //
        // Not crashing was the right half. The wrong half was what it did instead: a call
        // whose arguments this client could not read reached the gate as {} and the tool as
        // {}, and neither is what the model sent. Empty is not a no-op for a list, a
        // search, a read with a defaulted path, or a delete whose selector defaults to
        // everything — so the fail-closed direction is to refuse the call and say so, which
        // is the door #246 built for the sibling case one layer down.
        //
        // Four documents, because "malformed" was only ever one of the shapes that reached
        // a tool empty. Each is a whole arguments string a real upstream can send.
        for (String unreadable : List.of(
                "not json",                       // no JSON at all
                "{\"q\": \"weath",                  // a truncated stream's fragment
                "5",                              // a bare scalar: legal JSON, not an object
                "[1, 2]")) {                      // an array where an object is required
            LlmResponse response = aTurnWhoseArgumentsAre(unreadable);

            assertThat(response.message().content())
                    .as("the turn no longer carries a block for the call the provider sent,"
                            + " for arguments %s", unreadable)
                    .hasSize(1);
            assertThat(response.message().content().get(0))
                    .as("arguments %s reached a runner as a call it may gate and run",
                            unreadable)
                    .isInstanceOf(UnusableToolUseBlock.class);
            UnusableToolUseBlock refused =
                    (UnusableToolUseBlock) response.message().content().get(0);
            assertThat(refused.id()).isEqualTo("c");
            assertThat(refused.name()).isEqualTo("t");
            assertThat(refused.refusal())
                    .contains("No tool ran, no gate was asked")
                    .contains("could not be read as a JSON object")
                    .as("the refusal quotes the parser's words, which are the far side's"
                            + " bytes read back, at a model that reads this unfenced")
                    .doesNotContain(unreadable);
        }
    }

    @Test
    void aCallThatGenuinelyTakesNoArgumentsStillArrivesAsACallWithNoArguments() {
        // The denominator, and the reason the refusal above is narrow rather than "anything
        // that is not a populated object". The OpenAI-compatible wire spells a no-argument
        // call three ways and every one of them has exactly one reading; none of them is a
        // document this client failed to read. A change that refused these would break every
        // tool that takes no parameters, which is the failure direction a fail-closed change
        // makes easy.
        for (String noArguments : List.of("", "   ", "{}", "null")) {
            LlmResponse response = aTurnWhoseArgumentsAre(noArguments);

            assertThat(response.message().content().get(0))
                    .as("a no-argument call spelled %s was refused", "'" + noArguments + "'")
                    .isInstanceOf(ToolUseBlock.class);
            assertThat(((ToolUseBlock) response.message().content().get(0)).input()).isEmpty();
        }
    }

    @Test
    void argumentsNestedPastJacksonsOwnStreamLimitAreRefusedRatherThanEmptied() {
        // The third way a tool used to be entered with {} — and the one that is neither
        // malformed nor a wrong shape. Frozen caps an argument tree at 100 levels and #246
        // made that a refusal; but a document deep enough to trip Jackson's stream limit
        // never reaches Frozen at all, because readValue refuses it first. It used to come
        // back as Map.of(), so the deepest structure a provider can send was the one that
        // ran the tool with no arguments while a hundred-level one was refused by name.
        int levels = 2_000;
        String deep = "{\"n\":".repeat(levels) + "\"x\"" + "}".repeat(levels);

        LlmResponse response = aTurnWhoseArgumentsAre(deep);

        assertThat(response.message().content().get(0))
                .isInstanceOf(UnusableToolUseBlock.class);
        assertThat(((UnusableToolUseBlock) response.message().content().get(0)).refusal())
                .contains("could not be read as a JSON object");
    }

    @Test
    void missingUsageBecomesZero() {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},"
                + "\"finish_reason\":\"stop\"}]}";

        LlmResponse response = generateWith(new OpenRouterTransport.HttpResult(200, json), simpleRequest());

        assertThat(response.usage().totalTokens()).isZero();
    }

    @Test
    void finishReasonMapping() {
        assertThat(OpenRouterLlmClient.mapFinishReason("stop")).isEqualTo(LlmStopReason.END_TURN);
        assertThat(OpenRouterLlmClient.mapFinishReason("length")).isEqualTo(LlmStopReason.MAX_TOKENS);
        assertThat(OpenRouterLlmClient.mapFinishReason("tool_calls")).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(OpenRouterLlmClient.mapFinishReason("function_call")).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(OpenRouterLlmClient.mapFinishReason("content_filter")).isEqualTo(LlmStopReason.REFUSAL);
        assertThat(OpenRouterLlmClient.mapFinishReason("something_else")).isEqualTo(LlmStopReason.OTHER);
        assertThat(OpenRouterLlmClient.mapFinishReason(null)).isEqualTo(LlmStopReason.OTHER);
    }

    // --- errors & transport -------------------------------------------------

    @Test
    void aNon2xxStatusBecomesAnLlmExceptionCarryingTheProviderMessage() {
        String json = "{\"error\":{\"message\":\"insufficient credits\",\"code\":402}}";

        assertThatThrownBy(() ->
                generateWith(new OpenRouterTransport.HttpResult(402, json), simpleRequest()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("402")
                .hasMessageContaining("insufficient credits");
    }

    @Test
    void anErrorObjectInA200BodyBecomesAnLlmException() {
        String json = "{\"error\":{\"message\":\"model is down\"}}";

        assertThatThrownBy(() ->
                generateWith(new OpenRouterTransport.HttpResult(200, json), simpleRequest()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("model is down");
    }

    @Test
    void anEmptyChoicesArrayBecomesAnLlmException() {
        assertThatThrownBy(() ->
                generateWith(new OpenRouterTransport.HttpResult(200, "{\"choices\":[]}"), simpleRequest()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("no choices");
    }

    @Test
    void theRequestCarriesTheBearerKeyEndpointAndOptionalAttributionHeaders() {
        FakeTransport transport = new FakeTransport(new OpenRouterTransport.HttpResult(200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"));
        OpenRouterLlmClient client = OpenRouterLlmClient.builder("secret-key")
                .transport(transport)
                .referer("https://example.com")
                .title("My App")
                .build();

        client.generate(simpleRequest());

        assertThat(transport.url).isEqualTo("https://openrouter.ai/api/v1/chat/completions");
        assertThat(transport.headers).containsEntry("Authorization", "Bearer secret-key");
        assertThat(transport.headers).containsEntry("Content-Type", "application/json");
        assertThat(transport.headers).containsEntry("HTTP-Referer", "https://example.com");
        assertThat(transport.headers).containsEntry("X-Title", "My App");
    }

    @Test
    void aCustomBaseUrlHasItsTrailingSlashNormalised() {
        FakeTransport transport = new FakeTransport(new OpenRouterTransport.HttpResult(200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"));
        OpenRouterLlmClient.builder("k")
                .transport(transport)
                .baseUrl("https://proxy.internal/v1/")
                .build()
                .generate(simpleRequest());

        assertThat(transport.url).isEqualTo("https://proxy.internal/v1/chat/completions");
    }

    @Test
    void aBlankApiKeyIsRejected() {
        assertThatThrownBy(() -> OpenRouterLlmClient.builder("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multipleToolResultsInOneUserTurnBecomeSeparateToolMessages() throws Exception {
        FakeTransport transport = new FakeTransport(new OpenRouterTransport.HttpResult(200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"));
        LlmRequest request = LlmRequest.builder("openai/gpt-4o")
                .addMessage(Message.user("go"))
                .addMessage(Message.of(Role.ASSISTANT, List.of(
                        new ToolUseBlock("t1", "a", Map.of()),
                        new ToolUseBlock("t2", "b", Map.of()))))
                .addMessage(Message.of(Role.USER, List.of(
                        ToolResultBlock.ok("t1", "one"),
                        ToolResultBlock.ok("t2", "two"))))
                .build();

        OpenRouterLlmClient.builder("k").transport(transport).build().generate(request);

        JsonNode messages = captureBody(transport).path("messages");
        // user "go", assistant (2 tool_calls), tool t1, tool t2
        assertThat(messages).hasSize(4);
        assertThat(messages.get(1).path("tool_calls")).hasSize(2);
        assertThat(messages.get(2).path("role").asText()).isEqualTo("tool");
        assertThat(messages.get(2).path("tool_call_id").asText()).isEqualTo("t1");
        assertThat(messages.get(3).path("tool_call_id").asText()).isEqualTo("t2");
    }

    // --- tool-name sanitisation ---------------------------------------------

    @Test
    void aToolNameTheProvidersRejectIsRewrittenOnTheWireAndInTheEcho() {
        // Anthropic-backed models reject a tool name outside ^[a-zA-Z0-9_-]{1,128}$ with
        // an HTTP 400 before the model sees the request, and AgentKit callers name tools
        // "ticketing.get_ticket"-style. The dot must be rewritten everywhere the name
        // travels: the tools array, and the echoed assistant tool_calls — an echo under
        // the original name would fail the same validation the tools array just passed.
        LlmRequest request = LlmRequest.builder("anthropic/claude-sonnet-4.5")
                .addMessage(Message.user("go"))
                .addMessage(Message.of(Role.ASSISTANT,
                        new ToolUseBlock("t1", "ticketing.get_ticket", Map.of("id", "INC1"))))
                .addMessage(Message.of(Role.USER, ToolResultBlock.ok("t1", "the ticket")))
                .tools(List.of(new ToolSpec("ticketing.get_ticket", "fetch a ticket",
                        Map.of("type", "object"))))
                .build();

        JsonNode body = OpenRouterLlmClient.toRequestJson(request);

        assertThat(body.path("tools").get(0).path("function").path("name").asText())
                .isEqualTo("ticketing_get_ticket");
        assertThat(body.path("messages").get(1).path("tool_calls").get(0)
                .path("function").path("name").asText())
                .isEqualTo("ticketing_get_ticket");
    }

    @Test
    void aToolCallComesBackUnderTheNameTheCallerRegistered() {
        // The model calls the wire name, because that is the only name it was shown; the
        // ProposedCall the runner gates must carry the registered name, or no tool matches.
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"ticketing_get_ticket\",\"arguments\":\"{\\\"id\\\":\\\"INC1\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}";
        LlmRequest request = LlmRequest.builder("anthropic/claude-sonnet-4.5")
                .addMessage(Message.user("go"))
                .tools(List.of(new ToolSpec("ticketing.get_ticket", "fetch a ticket",
                        Map.of("type", "object"))))
                .build();

        LlmResponse response = generateWith(new OpenRouterTransport.HttpResult(200, json), request);

        ToolUseBlock use = (ToolUseBlock) response.message().content().get(0);
        assertThat(use.name()).isEqualTo("ticketing.get_ticket");
        assertThat(use.input()).containsEntry("id", "INC1");
    }

    @Test
    void twoNamesThatRewriteAlikeStayDistinctAndEachMapsBack() {
        // "a.b" and "a_b" in one request must not collapse into one tool: the second
        // rewrite takes a numeric suffix, and each wire name resolves to its own original.
        OpenRouterLlmClient.ToolNames names = OpenRouterLlmClient.ToolNames.of(List.of(
                new ToolSpec("a.b", "first", Map.of("type", "object")),
                new ToolSpec("a_b", "second", Map.of("type", "object"))));

        assertThat(names.wire("a.b")).isEqualTo("a_b");
        assertThat(names.wire("a_b")).isEqualTo("a_b_2");
        assertThat(names.original("a_b")).isEqualTo("a.b");
        assertThat(names.original("a_b_2")).isEqualTo("a_b");
    }

    @Test
    void longNamesThatTruncateAlikeStillGetDistinctWireNamesWithinTheLimit() {
        // The arithmetic that is easiest to break silently: two names longer than the
        // 64-character wire limit whose difference lives entirely in the truncated tail.
        // The suffix must fit INSIDE the limit, not be appended past it, and both wire
        // names must still resolve to their own original.
        String prefix = "x".repeat(64);
        OpenRouterLlmClient.ToolNames names = OpenRouterLlmClient.ToolNames.of(List.of(
                new ToolSpec(prefix + ".alpha", "first", Map.of("type", "object")),
                new ToolSpec(prefix + ".beta", "second", Map.of("type", "object"))));

        assertThat(names.wire(prefix + ".alpha")).isEqualTo(prefix);
        assertThat(names.wire(prefix + ".beta"))
                .hasSize(64)
                .isEqualTo(prefix.substring(0, 62) + "_2");
        assertThat(names.original(prefix)).isEqualTo(prefix + ".alpha");
        assertThat(names.original(prefix.substring(0, 62) + "_2")).isEqualTo(prefix + ".beta");
    }

    // --- streaming ----------------------------------------------------------

    @Test
    void streamingEmitsTextDeltasLiveAndAssemblesTheFinalMessage() {
        FakeTransport transport = FakeTransport.streaming(200, List.of(
                contentChunk("Hel"),
                contentChunk("lo"),
                finishChunk("stop"),
                usageChunk(3, 2),
                "data: [DONE]"));
        List<String> deltas = new ArrayList<>();

        LlmResponse response = OpenRouterLlmClient.builder("k").transport(transport).build()
                .generate(simpleRequest(), deltas::add);

        assertThat(deltas).containsExactly("Hel", "lo"); // emitted live, in order
        assertThat(response.message().text()).isEqualTo("Hello"); // assembled whole
        assertThat(response.stopReason()).isEqualTo(LlmStopReason.END_TURN);
        assertThat(response.usage().inputTokens()).isEqualTo(3);
        assertThat(response.usage().outputTokens()).isEqualTo(2);
    }

    @Test
    void streamingAssemblesToolCallsFromArgumentFragments() {
        FakeTransport transport = FakeTransport.streaming(200, List.of(
                toolCallChunk(0, "call_1", "search", ""),
                toolCallChunk(0, null, null, "{\"q\":\""),
                toolCallChunk(0, null, null, "weather\"}"),
                finishChunk("tool_calls"),
                "data: [DONE]"));

        LlmResponse response = OpenRouterLlmClient.builder("k").transport(transport).build()
                .generate(simpleRequest(), StreamHandler.NONE);

        assertThat(response.stopReason()).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(response.message().content()).hasSize(1);
        ToolUseBlock use = (ToolUseBlock) response.message().content().get(0);
        assertThat(use.id()).isEqualTo("call_1");
        assertThat(use.name()).isEqualTo("search");
        assertThat(use.input()).containsEntry("q", "weather"); // fragments concatenated then parsed
    }

    @Test
    void aStreamedToolCallAlsoComesBackUnderTheRegisteredName() {
        // The streamed path assembles its calls from fragments; the name it assembled is
        // still the wire name and takes the same trip back through the mapping.
        FakeTransport transport = FakeTransport.streaming(200, List.of(
                toolCallChunk(0, "call_1", "ticketing_get_ticket", "{\"id\":\"INC1\"}"),
                finishChunk("tool_calls"),
                "data: [DONE]"));
        LlmRequest request = LlmRequest.builder("anthropic/claude-sonnet-4.5")
                .addMessage(Message.user("go"))
                .tools(List.of(new ToolSpec("ticketing.get_ticket", "fetch a ticket",
                        Map.of("type", "object"))))
                .build();

        LlmResponse response = OpenRouterLlmClient.builder("k").transport(transport).build()
                .generate(request, StreamHandler.NONE);

        ToolUseBlock use = (ToolUseBlock) response.message().content().get(0);
        assertThat(use.name()).isEqualTo("ticketing.get_ticket");
    }

    @Test
    void streamingRequestOptsIntoStreamAndUsageReporting() throws Exception {
        FakeTransport transport = FakeTransport.streaming(200, List.of(finishChunk("stop"), "data: [DONE]"));

        OpenRouterLlmClient.builder("k").transport(transport).build()
                .generate(simpleRequest(), StreamHandler.NONE);

        JsonNode body = captureBody(transport);
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
    }

    @Test
    void aStreamingErrorStatusBecomesAnLlmException() {
        FakeTransport transport = FakeTransport.streaming(429, List.of("{\"error\":{\"message\":\"rate limited\"}}"));

        assertThatThrownBy(() -> OpenRouterLlmClient.builder("k").transport(transport).build()
                .generate(simpleRequest(), StreamHandler.NONE))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("429")
                .hasMessageContaining("rate limited");
    }

    @Test
    void streamingAssemblesParallelToolCallsByIndex() {
        // Interleaved fragments for two calls (index 0 and 1) must not cross-contaminate.
        FakeTransport transport = FakeTransport.streaming(200, List.of(
                toolCallChunk(0, "call_a", "search", "{\"q\":\""),
                toolCallChunk(1, "call_b", "lookup", "{\"id\":"),
                toolCallChunk(0, null, null, "cats\"}"),
                toolCallChunk(1, null, null, "7}"),
                finishChunk("tool_calls"),
                "data: [DONE]"));

        LlmResponse response = OpenRouterLlmClient.builder("k").transport(transport).build()
                .generate(simpleRequest(), StreamHandler.NONE);

        assertThat(response.stopReason()).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(response.message().content()).hasSize(2); // ordered by index
        ToolUseBlock first = (ToolUseBlock) response.message().content().get(0);
        ToolUseBlock second = (ToolUseBlock) response.message().content().get(1);
        assertThat(first.id()).isEqualTo("call_a");
        assertThat(first.name()).isEqualTo("search");
        assertThat(first.input()).containsEntry("q", "cats");
        assertThat(second.id()).isEqualTo("call_b");
        assertThat(second.name()).isEqualTo("lookup");
        assertThat(second.input()).containsEntry("id", 7);
    }

    @Test
    void aStreamThatStopsMidArgumentsIsRefusedRatherThanRunWithNoArguments() {
        // The streamed half of #271, and the path where an unreadable arguments string is
        // ordinary rather than exotic: arguments arrive as fragments concatenated across SSE
        // chunks, so an upstream that stops sending — or a proxy that drops the last chunk —
        // leaves a document that cannot parse. streamingAssemblesToolCallFragments above is
        // the denominator: the same three chunks with the last one complete.
        //
        // Before, this reached a runner as ToolUseBlock("call_1", "search", {}) — a call the
        // gate judges and the tool runs, on arguments the model did not send.
        FakeTransport transport = FakeTransport.streaming(200, List.of(
                toolCallChunk(0, "call_1", "search", ""),
                toolCallChunk(0, null, null, "{\"q\":\""),
                finishChunk("tool_calls"),
                "data: [DONE]"));

        LlmResponse response = OpenRouterLlmClient.builder("k").transport(transport).build()
                .generate(simpleRequest(), StreamHandler.NONE);

        assertThat(response.stopReason()).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(response.message().content()).hasSize(1);
        assertThat(response.message().content().get(0))
                .as("a half-streamed call reached a runner as one it may gate and run")
                .isInstanceOf(UnusableToolUseBlock.class);
        UnusableToolUseBlock refused =
                (UnusableToolUseBlock) response.message().content().get(0);
        assertThat(refused.id()).isEqualTo("call_1");
        assertThat(refused.name()).isEqualTo("search");
        assertThat(refused.refusal()).contains("No tool ran, no gate was asked");
    }

    @Test
    void streamingClosesTheLineStream() {
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        OpenRouterTransport transport = new OpenRouterTransport() {
            @Override
            public HttpResult post(String url, Map<String, String> headers, String jsonBody) {
                throw new UnsupportedOperationException();
            }

            @Override
            public StreamResult postStreaming(String url, Map<String, String> headers, String jsonBody) {
                return new StreamResult(200,
                        java.util.stream.Stream.of(finishChunk("stop"), "data: [DONE]").onClose(() -> closed.set(true)));
            }
        };

        OpenRouterLlmClient.builder("k").transport(transport).build()
                .generate(simpleRequest(), StreamHandler.NONE);

        assertThat(closed).as("the SSE line stream must be closed to release the connection").isTrue();
    }

    @Test
    void aNon2xxWithANonJsonBodyStillSurfacesTheBody() {
        // A gateway 502 whose body is HTML must not be swallowed as "unparseable".
        assertThatThrownBy(() -> generateWith(
                new OpenRouterTransport.HttpResult(502, "<html>Bad Gateway</html>"), simpleRequest()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("502")
                .hasMessageContaining("Bad Gateway");
    }

    @Test
    void anEmptyAssistantTurnSerializesAsEmptyStringNotNull() throws Exception {
        // content:null with no tool_calls is an invalid OpenAI assistant message.
        FakeTransport transport = new FakeTransport(new OpenRouterTransport.HttpResult(200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"));
        LlmRequest request = LlmRequest.builder("openai/gpt-4o")
                .addMessage(Message.user("hi"))
                .addMessage(Message.assistant("")) // an empty assistant turn
                .addMessage(Message.user("more"))
                .build();

        OpenRouterLlmClient.builder("k").transport(transport).build().generate(request);

        JsonNode assistant = captureBody(transport).path("messages").get(1);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        assertThat(assistant.path("content").isNull()).isFalse();
        assertThat(assistant.path("content").asText()).isEmpty();
        assertThat(assistant.has("tool_calls")).isFalse();
    }

    @Test
    void aToolsOptionDoesNotLeakWhenTheRequestDeclaresNoTools() {
        LlmRequest request = LlmRequest.builder("openai/gpt-4o")
                .addMessage(Message.user("hi"))
                .option("tools", List.of(Map.of("bogus", true)))
                .build();

        JsonNode body = OpenRouterLlmClient.toRequestJson(request);

        assertThat(body.has("tools")).isFalse(); // the framework field wins even when empty
    }

    @Test
    void streamingFallsBackToASingleDeltaWhenTheTransportCannotStream() {
        // A transport implementing only post() inherits the default postStreaming,
        // which throws UnsupportedOperationException; the client must fall back.
        OpenRouterTransport postOnly = (url, headers, body) -> new OpenRouterTransport.HttpResult(200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"whole answer\"},"
                        + "\"finish_reason\":\"stop\"}]}");
        List<String> deltas = new ArrayList<>();

        LlmResponse response = OpenRouterLlmClient.builder("k").transport(postOnly).build()
                .generate(simpleRequest(), deltas::add);

        assertThat(deltas).containsExactly("whole answer"); // one delta: the whole assembled text
        assertThat(response.message().text()).isEqualTo("whole answer");
        assertThat(response.stopReason()).isEqualTo(LlmStopReason.END_TURN);
    }
}
