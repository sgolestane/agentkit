package dev.agentkit.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.StreamHandler;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.ImageBlock;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ThinkingBlock;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.message.UnusableToolUseBlock;
import dev.agentkit.core.tool.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An {@link LlmClient} backed by the official Anthropic Java SDK.
 *
 * <p>Translates AgentKit's provider-agnostic {@link LlmRequest} into a
 * {@code MessageCreateParams}, invokes the Messages API, and normalises the
 * response back into an {@link LlmResponse}. The default model is
 * {@value #DEFAULT_MODEL}.
 *
 * <p>Only the content-block types AgentKit uses — text, thinking, tool-use, and
 * tool-result — are mapped; other provider block types in a response are ignored.
 */
public final class AnthropicLlmClient implements LlmClient {

    /** The recommended default model. */
    public static final String DEFAULT_MODEL = "claude-opus-4-8";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** A stock mapper for output schemas; see the note in {@link #toParams}. */
    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();

    private final AnthropicClient client;
    private final ModelResolver modelResolver;
    private final CachePolicy cachePolicy;

    public AnthropicLlmClient(AnthropicClient client) {
        this(client, ModelResolver.IDENTITY, CachePolicy.NONE);
    }

    /**
     * Builds a client that translates each request's logical model id through
     * {@code modelResolver} before calling the API. Use this for backends where
     * the wire model id differs from the logical one (e.g. Amazon Bedrock
     * application inference profiles); the first-party API uses
     * {@link ModelResolver#IDENTITY}.
     */
    public AnthropicLlmClient(AnthropicClient client, ModelResolver modelResolver) {
        this(client, modelResolver, CachePolicy.NONE);
    }

    /**
     * As {@link #AnthropicLlmClient(AnthropicClient, ModelResolver)}, plus a
     * {@link CachePolicy} that adds prompt-cache breakpoints to each request.
     * Enabling caching is recommended for agent loops, which re-send a large stable
     * prefix every turn.
     */
    public AnthropicLlmClient(AnthropicClient client, ModelResolver modelResolver, CachePolicy cachePolicy) {
        this.client = Objects.requireNonNull(client, "client");
        this.modelResolver = Objects.requireNonNull(modelResolver, "modelResolver");
        this.cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy");
    }

    /** Builds a client from the {@code ANTHROPIC_API_KEY} environment variable (no caching). */
    public static AnthropicLlmClient fromEnv() {
        return fromEnv(CachePolicy.NONE);
    }

    /** As {@link #fromEnv()}, with the given prompt-caching {@code cachePolicy}. */
    public static AnthropicLlmClient fromEnv(CachePolicy cachePolicy) {
        return new AnthropicLlmClient(AnthropicOkHttpClient.fromEnv(), ModelResolver.IDENTITY, cachePolicy);
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            MessageCreateParams params = toParams(request, modelResolver, cachePolicy);
            com.anthropic.models.messages.Message response = client.messages().create(params);
            return toLlmResponse(response);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException("Anthropic message call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public LlmResponse generate(LlmRequest request, StreamHandler handler) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        try {
            MessageCreateParams params = toParams(request, modelResolver, cachePolicy);
            MessageAccumulator accumulator = MessageAccumulator.create();
            // The accumulator assembles the final Message from the raw events; we also
            // forward text deltas live. The stream is closed even on a mid-stream error.
            try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
                stream.stream().forEach(event -> {
                    accumulator.accumulate(event);
                    textDelta(event).ifPresent(handler::onTextDelta);
                });
            }
            return toLlmResponse(accumulator.message());
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException("Anthropic streaming message call failed: " + e.getMessage(), e);
        }
    }

    /** The text fragment carried by a content-block delta event, if this event is one. */
    static Optional<String> textDelta(RawMessageStreamEvent event) {
        if (!event.isContentBlockDelta()) {
            return Optional.empty();
        }
        RawContentBlockDelta delta = event.asContentBlockDelta().delta();
        return delta.isText() ? Optional.of(delta.asText().text()) : Optional.empty();
    }

    // --- request mapping ----------------------------------------------------

    static MessageCreateParams toParams(LlmRequest request) {
        return toParams(request, ModelResolver.IDENTITY, CachePolicy.NONE);
    }

    static MessageCreateParams toParams(LlmRequest request, ModelResolver modelResolver) {
        return toParams(request, modelResolver, CachePolicy.NONE);
    }

    /**
     * Maps the request, optionally adding prompt-cache breakpoints. The render
     * order is tools &rarr; system &rarr; messages, and a breakpoint caches
     * everything up to and including it, so the placement is:
     * <ul>
     *   <li>the <b>stable prefix</b> (tools + system) is cached by marking the
     *       system prompt (which renders after the tools); with no system prompt,
     *       the last tool is marked instead, so the tool definitions still cache;</li>
     *   <li>the <b>growing conversation</b> is cached by an explicit breakpoint on
     *       the last content block of the last message — so each turn's history is
     *       a cache read on the next turn.</li>
     * </ul>
     * That is at most two breakpoints (well under the API's limit of four). Both are
     * <em>explicit</em> per-block breakpoints (not top-level automatic caching), so
     * they work on Amazon Bedrock as well as the first-party API.
     */
    static MessageCreateParams toParams(LlmRequest request, ModelResolver modelResolver, CachePolicy cachePolicy) {
        CacheControlEphemeral cc = cachePolicy.enabled() ? cachePolicy.cacheControl() : null;

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(modelResolver.resolve(request.model()))
                .maxTokens(request.maxTokens());

        // System prompt. With caching, render it as a cache-marked text block so
        // the tools+system prefix is cached; without, the plain string form.
        request.system().ifPresent(system -> {
            if (cc != null) {
                builder.systemOfTextBlockParams(List.of(
                        TextBlockParam.builder().text(system).cacheControl(cc).build()));
            } else {
                builder.system(system);
            }
        });

        // Rolling conversation breakpoint: mark the last content block of the last
        // message (LlmRequest guarantees at least one message).
        List<Message> messages = request.messages();
        for (int i = 0; i < messages.size(); i++) {
            CacheControlEphemeral messageCc = i == messages.size() - 1 ? cc : null;
            builder.addMessage(toMessageParam(messages.get(i), messageCc));
        }

        // Structured output: constrain decoding to the requested JSON Schema. The schema
        // map is forwarded verbatim as the format's additional properties, so richer
        // schemas ($defs, anyOf, …) survive rather than being flattened. The format's
        // `type` defaults to json_schema. OutputSchema.name() has no home in this wire
        // format and is dropped; it is only meaningful on OpenAI-shaped providers.
        request.outputSchema().ifPresent(outputSchema -> {
            JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
            // Via a JsonNode rather than JsonValue.from(map): the SDK's mapper is
            // configured NON_ABSENT, which silently drops null values nested inside
            // maps — turning `"const": null` into an unconstrained `{}`.
            outputSchema.schema().forEach(
                    (k, v) -> schema.putAdditionalProperty(k, JsonValue.from(SCHEMA_MAPPER.valueToTree(v))));
            builder.outputConfig(OutputConfig.builder()
                    .format(JsonOutputFormat.builder().schema(schema.build()).build())
                    .build());
        });

        // Tools. When caching without a system prompt, mark the last tool so the
        // tool definitions are still cached (no later prefix breakpoint covers them).
        List<ToolSpec> tools = request.tools();
        boolean markLastTool = cc != null && request.system().isEmpty() && !tools.isEmpty();
        for (int i = 0; i < tools.size(); i++) {
            boolean isLast = i == tools.size() - 1;
            builder.addTool(toTool(tools.get(i), markLastTool && isLast ? cc : null));
        }

        return builder.build();
    }

    /**
     * Maps a message. When {@code cacheControl} is non-null it is applied to the
     * <em>last</em> content block, placing an explicit cache breakpoint at the end
     * of the conversation. (Thinking blocks carry no breakpoint — an unsigned one is
     * dropped entirely — but a thinking block never terminates a request's last
     * message, which is a user turn: text or tool results.)
     */
    private static MessageParam toMessageParam(Message message, CacheControlEphemeral cacheControl) {
        MessageParam.Role role = switch (message.role()) {
            case USER -> MessageParam.Role.USER;
            case ASSISTANT -> MessageParam.Role.ASSISTANT;
            case SYSTEM -> throw new LlmException(
                    "SYSTEM messages must be provided via LlmRequest.system(), not in the message list");
        };

        List<ContentBlockParam> blocks = new ArrayList<>();
        List<ContentBlock> content = message.content();
        for (int i = 0; i < content.size(); i++) {
            CacheControlEphemeral blockCc = i == content.size() - 1 ? cacheControl : null;
            toContentBlockParam(content.get(i), blockCc).ifPresent(blocks::add);
        }
        if (blocks.isEmpty()) {
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text("(no content)").build()));
        }
        return MessageParam.builder().role(role).contentOfBlockParams(blocks).build();
    }

    private static java.util.Optional<ContentBlockParam> toContentBlockParam(
            ContentBlock block, CacheControlEphemeral cc) {
        return switch (block) {
            case TextBlock t -> {
                TextBlockParam.Builder b = TextBlockParam.builder().text(t.text());
                if (cc != null) {
                    b.cacheControl(cc);
                }
                yield java.util.Optional.of(ContentBlockParam.ofText(b.build()));
            }
            // The one block whose absence was a correctness problem rather than a missing
            // feature (#374): without it an uploaded screenshot reached the model as a
            // filename, and the agent answered about the filename while a person believed it
            // was answering about the picture.
            //
            // No cache control on it, deliberately. An image is the most expensive block in a
            // request and therefore the most tempting to cache — and a breakpoint here would
            // sit at whatever position the image happened to occupy, which is the operator's
            // upload rather than a decision this adapter made. The existing rule is that the
            // breakpoint goes on the LAST block of the message; an image that happens to be
            // last still gets it through that path.
            case ImageBlock i -> java.util.Optional.of(ContentBlockParam.ofImage(
                    ImageBlockParam.builder()
                            .source(Base64ImageSource.builder()
                                    .mediaType(Base64ImageSource.MediaType.Companion
                                            .of(i.mediaType()))
                                    .data(i.base64())
                                    .build())
                            .build()));
            case ToolUseBlock u -> java.util.Optional.of(ContentBlockParam.ofToolUse(
                    toToolUseParam(u.id(), u.name(), u.input(), cc)));
            // Echoed with empty arguments, and it has to be echoed (#246). The API matches
            // a tool_result to a tool_use by id and rejects a result with no call, so
            // dropping this block would make the very turn that carries the refusal
            // invalid. What goes out is not a call anybody may act on: by the time this
            // message is replayed the call has already been refused and answered in the
            // user turn immediately after it, and the framework itself never holds a
            // ToolUseBlock for it — which is why UnusableToolUseBlock has no input() of its
            // own to be tempted into sending.
            case UnusableToolUseBlock u -> java.util.Optional.of(ContentBlockParam.ofToolUse(
                    toToolUseParam(u.id(), u.name(), java.util.Map.of(), cc)));
            case ToolResultBlock r -> {
                ToolResultBlockParam.Builder b = ToolResultBlockParam.builder()
                        .toolUseId(r.toolUseId())
                        .content(r.content())
                        .isError(r.isError());
                if (cc != null) {
                    b.cacheControl(cc);
                }
                yield java.util.Optional.of(ContentBlockParam.ofToolResult(b.build()));
            }
            case ThinkingBlock th -> th.signature().isBlank()
                    ? java.util.Optional.empty() // cannot replay an unsigned thinking block
                    : java.util.Optional.of(ContentBlockParam.ofThinking(
                            ThinkingBlockParam.builder()
                                    .thinking(th.thinking())
                                    .signature(th.signature())
                                    .build()));
        };
    }

    /**
     * The wire form of one proposed call.
     *
     * <p>Takes the three fields rather than a {@link ToolUseBlock}, so the refused case can
     * be written without fabricating one (#246). A {@code ToolUseBlock} is the type that
     * promises its arguments were frozen and bounded, and building one here with
     * {@code Map.of()} to satisfy a signature would be a call the framework's own rule says
     * cannot exist — living, briefly, inside the adapter that was meant never to produce it.
     */
    private static ToolUseBlockParam toToolUseParam(String id, String name,
                                                    java.util.Map<String, Object> arguments,
                                                    CacheControlEphemeral cc) {
        ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
        arguments.forEach((k, v) -> input.putAdditionalProperty(k, JsonValue.from(v)));
        ToolUseBlockParam.Builder builder = ToolUseBlockParam.builder()
                .id(id)
                .name(name)
                .input(input.build());
        if (cc != null) {
            builder.cacheControl(cc);
        }
        return builder.build();
    }

    private static Tool toTool(ToolSpec spec, CacheControlEphemeral cacheControl) {
        Map<String, Object> schema = spec.inputSchema();

        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        if (schema.get("properties") instanceof Map<?, ?> props) {
            props.forEach((k, v) -> properties.putAdditionalProperty(String.valueOf(k), JsonValue.from(v)));
        }

        List<String> required = new ArrayList<>();
        if (schema.get("required") instanceof List<?> req) {
            for (Object item : req) {
                if (item != null) {
                    required.add(item.toString());
                }
            }
        }

        Tool.InputSchema.Builder inputSchemaBuilder = Tool.InputSchema.builder()
                .properties(properties.build())
                .required(required);

        // Forward any additional top-level schema keys (e.g. additionalProperties,
        // $defs) so richer schemas are not silently weakened.
        java.util.Map<String, JsonValue> extras = new java.util.LinkedHashMap<>();
        schema.forEach((k, v) -> {
            if (!"type".equals(k) && !"properties".equals(k) && !"required".equals(k)) {
                extras.put(k, JsonValue.from(v));
            }
        });
        if (!extras.isEmpty()) {
            inputSchemaBuilder.putAllAdditionalProperties(extras);
        }
        Tool.InputSchema inputSchema = inputSchemaBuilder.build();

        Tool.Builder toolBuilder = Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(inputSchema);
        for (Map<String, Object> example : spec.examples()) {
            Tool.InputExample.Builder exampleBuilder = Tool.InputExample.builder();
            example.forEach((k, v) -> exampleBuilder.putAdditionalProperty(k, JsonValue.from(v)));
            toolBuilder.addInputExample(exampleBuilder.build());
        }
        if (cacheControl != null) {
            toolBuilder.cacheControl(cacheControl);
        }
        return toolBuilder.build();
    }

    // --- response mapping ---------------------------------------------------

    /**
     * The provider's turn as this framework's.
     *
     * <p>Package-private and static so a test can drive the real mapping over a real SDK
     * {@code Message}, which is the idiom {@link #mapStopReason} and {@link #toParams}
     * already follow here. It reads nothing off the client.
     */
    static LlmResponse toLlmResponse(com.anthropic.models.messages.Message response) {
        // NOTE: extended thinking is not enabled on requests yet, so responses do
        // not carry redacted_thinking blocks. Before enabling thinking, add a core
        // type to preserve redacted blocks for replay (otherwise the API rejects a
        // replayed assistant turn that dropped them).
        List<ContentBlock> blocks = new ArrayList<>();
        for (com.anthropic.models.messages.ContentBlock block : response.content()) {
            block.text().ifPresent(t -> blocks.add(TextBlock.of(t.text())));
            block.thinking().ifPresent(t -> blocks.add(new ThinkingBlock(t.thinking(), t.signature())));
            // Through the one door (#246), and through both of them (#271). A tool call
            // whose arguments this framework will not carry — nested past Frozen.MAX_DEPTH,
            // past the node budget, or holding a value no JSON document could hold — used
            // to throw out of here and take the whole turn with it, so the run ended with
            // the model never learning why. A call whose arguments could not be converted
            // at all used to run with Map.of(), which is worse: it ran. Both now become an
            // UnusableToolUseBlock the runner answers with a refusal.
            block.toolUse().ifPresent(u -> blocks.add(toProposedCall(u)));
        }
        if (blocks.isEmpty()) {
            blocks.add(TextBlock.of(""));
        }

        Message assistant = Message.of(Role.ASSISTANT, blocks);
        StopReason rawReason = response.stopReason().orElse(null);
        LlmStopReason stopReason = mapStopReason(rawReason);
        TokenUsage usage = new TokenUsage(response.usage().inputTokens(), response.usage().outputTokens());
        return new LlmResponse(assistant, stopReason, usage,
                java.util.Optional.ofNullable(rawReason).map(Object::toString));
    }

    /**
     * The turn's block for one {@code tool_use}, through {@link ProposedCall}'s two doors.
     *
     * <h4>What a failed conversion used to do, and why it was fail-open (#271)</h4>
     *
     * <p>This method used to be {@code toArgumentMap}, and it answered a failed conversion
     * with {@code Map.of()}. So a call whose arguments could not be read <strong>ran with no
     * arguments rather than the model's</strong>: the gate judged {@code {}}, which is not
     * what the model sent; the tool received {@code {}}, which is not what the model asked
     * for; and nobody was told. That is not a no-op for a {@code list}, a {@code search}, a
     * {@code read} with a defaulted path, or a delete whose selector defaults to everything.
     *
     * <p><strong>The catch is not dead, which is what decided this.</strong> {@code _input()}
     * is a raw {@code JsonValue} — the SDK's {@code validate()} does not require it to be an
     * object — so whatever the wire carried arrives here unchecked. Measured against
     * {@code JsonValue.convert}, this SDK version:
     *
     * <pre>
     * input: {"q": "x"}   -&gt; a map
     * input: null          -&gt; null
     * input: "not json"    -&gt; IllegalArgumentException (no String-argument creator)
     * input: 5             -&gt; IllegalArgumentException (from Integer value)
     * input: true          -&gt; IllegalArgumentException (from Boolean value)
     * input: [1, 2]        -&gt; IllegalArgumentException (from Array value)
     * </pre>
     *
     * <p>Four of six shapes throw. The Messages API documents {@code input} as an object, so
     * this is not the ordinary path — but this client is also pointed at Bedrock and at
     * whatever {@code baseUrl} a deployment configures, and nothing between the socket and
     * this line requires the shape. "The documentation says it cannot happen" is not a
     * measurement, and the cost of being wrong about it is an unauthorised call.
     *
     * <p><strong>Absent and null stay empty, and they are the two shapes where that is
     * honest.</strong> An absent or null {@code input} has exactly one reading — the model
     * called a tool that takes no arguments — where a string, a number, a boolean or an
     * array each say the model sent something this framework could not read. Emptying those
     * is the guess; emptying these two is the meaning.
     *
     * <p>They are tested for by <em>type</em> rather than left to the conversion, and that
     * is not tidiness. {@code convert} answers Java {@code null} for {@code JsonNull} and
     * <strong>throws</strong> for {@code JsonMissing}, so leaving the absent case to the
     * catch below would refuse a no-argument call from any upstream that omits the field —
     * which the first version of this method did, and which the Messages API's own schema
     * hides, because it always sends {@code input}. This client is also pointed at Bedrock
     * and at whatever {@code baseUrl} a deployment configures; a gateway that drops an
     * empty object is exactly the deployment this would have broken, and it would have
     * broken it on every call to every no-argument tool.
     *
     * <p>The catch stays at {@link RuntimeException}, wider than the
     * {@link IllegalArgumentException} measured above, because the alternative to catching
     * something unforeseen here is that it takes the whole turn with it — which is the
     * defect #246 closed one layer down. Wider and now reported, rather than wider and
     * silent.
     */
    private static ProposedCall toProposedCall(com.anthropic.models.messages.ToolUseBlock use) {
        com.anthropic.core.JsonValue input = use._input();
        if (input instanceof com.anthropic.core.JsonMissing
                || input instanceof com.anthropic.core.JsonNull) {
            return ProposedCall.of(use.id(), use.name(), Map.of());
        }
        Map<String, Object> arguments;
        try {
            arguments = input.convert(MAP_TYPE);
        } catch (RuntimeException unreadable) {
            return ProposedCall.unreadable(use.id(), use.name(), unreadable);
        }
        // Belt and braces: convert answers Java null for a JSON null, and the type check
        // above is what actually catches it. Kept because the SDK owns which of the two it
        // uses and the cost of being wrong is a refused no-argument call.
        return ProposedCall.of(use.id(), use.name(),
                arguments == null ? Map.of() : arguments);
    }

    static LlmStopReason mapStopReason(StopReason reason) {
        if (reason == null) {
            return LlmStopReason.OTHER;
        }
        if (reason.equals(StopReason.TOOL_USE)) {
            return LlmStopReason.TOOL_USE;
        }
        if (reason.equals(StopReason.END_TURN) || reason.equals(StopReason.STOP_SEQUENCE)) {
            return LlmStopReason.END_TURN;
        }
        if (reason.equals(StopReason.MAX_TOKENS)) {
            return LlmStopReason.MAX_TOKENS;
        }
        if (reason.equals(StopReason.REFUSAL)) {
            return LlmStopReason.REFUSAL;
        }
        if (reason.equals(StopReason.PAUSE_TURN)) {
            return LlmStopReason.PAUSE;
        }
        return LlmStopReason.OTHER;
    }
}
