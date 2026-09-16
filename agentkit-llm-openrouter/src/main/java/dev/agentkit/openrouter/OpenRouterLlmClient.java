package dev.agentkit.openrouter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.OutputSchema;
import dev.agentkit.core.llm.StreamHandler;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.ImageBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.tool.ToolSpec;
import dev.agentkit.core.util.Cut;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * An {@link LlmClient} backed by <a href="https://openrouter.ai">OpenRouter</a>'s
 * OpenAI-compatible chat completions API. OpenRouter is a gateway that routes a
 * single API to many providers' models (Anthropic, OpenAI, Google, Meta, and more),
 * so one adapter unlocks all of them.
 *
 * <p>Because OpenRouter speaks the OpenAI wire format rather than Anthropic's
 * Messages API, this adapter is a small hand-rolled HTTP client (Jackson + the JDK
 * {@link java.net.http.HttpClient}) rather than a vendor SDK. It translates
 * AgentKit's provider-agnostic {@link LlmRequest} into a {@code chat/completions}
 * request, POSTs it, and normalises the response into an {@link LlmResponse}.
 *
 * <p><strong>Models are namespaced.</strong> Pass an OpenRouter model id as the
 * request model — for example {@code "anthropic/claude-opus-4-8"},
 * {@code "openai/gpt-4o"}, or {@code "google/gemini-2.0-flash-001"}. Per-call knobs
 * (temperature, top_p, OpenRouter {@code provider} routing preferences, and so on)
 * can be set with {@link LlmRequest.Builder#option(String, Object)}; each option is
 * copied verbatim into the request body, while the framework-controlled fields
 * (model, messages, max_tokens, tools) always take precedence.
 *
 * <p>Only the content-block types AgentKit uses — text, tool-use, and tool-result —
 * are mapped. Thinking blocks are dropped on the way out (the OpenAI chat format has
 * no portable way to replay provider reasoning), and tool input {@code examples} are
 * not forwarded (the OpenAI function schema has no field for them). Tool names are
 * rewritten into the character set the routed providers accept and mapped back on the
 * response — see {@link ToolNames} — so a caller's dotted name works unchanged. Real server-sent-event
 * streaming <em>is</em> supported:
 * {@link #generate(LlmRequest, dev.agentkit.core.llm.StreamHandler)} emits text deltas
 * live and falls back to a single delta when the transport cannot stream.
 *
 * <p><strong>Deadlines.</strong> With the default transport every call is bounded, and
 * turning streaming on does not remove the bound. A non-streaming call has 120 seconds
 * for the whole exchange. A streaming call has 120 seconds to response headers and then
 * 120 seconds of <em>silence</em> — the clock restarts on every line — so a long
 * generation runs as long as it needs to, while a provider that sends headers and then
 * goes quiet fails with an {@link LlmException} a
 * {@link dev.agentkit.core.reliability.RetryingLlmClient} can retry. OpenRouter routes to
 * many upstream providers, so a stall behind the gateway is an ordinary operational
 * event rather than an exotic one. Use {@link OpenRouterTransport#jdk(java.time.Duration)}
 * for a different idle deadline; a custom transport owns its own, and one that never
 * gives up will park the calling thread, because this class consumes the stream
 * internally and hands no handle back for another thread to close.
 */
public final class OpenRouterLlmClient implements LlmClient {

    /** The default OpenRouter API base URL. */
    public static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";

    /** The environment variable {@link #fromEnv()} reads the API key from. */
    public static final String API_KEY_ENV = "OPENROUTER_API_KEY";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final int MAX_ERROR_BODY_CHARS = 500;

    private final OpenRouterTransport transport;
    private final String apiKey;
    private final String baseUrl;
    private final String referer;
    private final String title;

    private OpenRouterLlmClient(Builder b) {
        this.transport = b.transport;
        this.apiKey = b.apiKey;
        this.baseUrl = b.baseUrl;
        this.referer = b.referer;
        this.title = b.title;
    }

    /** A builder for a client using the given OpenRouter API key. */
    public static Builder builder(String apiKey) {
        return new Builder(apiKey);
    }

    /**
     * Builds a client from the {@code OPENROUTER_API_KEY} environment variable.
     *
     * @throws LlmException if the variable is unset or blank
     */
    public static OpenRouterLlmClient fromEnv() {
        String key = System.getenv(API_KEY_ENV);
        if (key == null || key.isBlank()) {
            throw new LlmException("Environment variable " + API_KEY_ENV + " is not set");
        }
        return builder(key).build();
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            ToolNames names = ToolNames.of(request.tools());
            String body = toRequestJson(request, names).toString();
            OpenRouterTransport.HttpResult result =
                    transport.post(baseUrl + "/chat/completions", headers(), body);
            return parseResponse(result, names);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException("OpenRouter call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public LlmResponse generate(LlmRequest request, StreamHandler handler) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        ToolNames names = ToolNames.of(request.tools());
        ObjectNode body = toRequestJson(request, names);
        body.put("stream", true);
        // Ask OpenRouter to include token usage in the final SSE chunk.
        body.putObject("stream_options").put("include_usage", true);

        OpenRouterTransport.StreamResult stream;
        try {
            stream = transport.postStreaming(baseUrl + "/chat/completions", headers(), body.toString());
        } catch (UnsupportedOperationException e) {
            // The transport cannot stream; fall back to the single-delta default.
            return LlmClient.super.generate(request, handler);
        }
        try (stream) {
            return parseStream(stream, handler, names);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException("OpenRouter streaming call failed: " + e.getMessage(), e);
        }
    }

    private Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + apiKey);
        h.put("Content-Type", "application/json");
        // Optional attribution headers OpenRouter uses to rank apps on its leaderboards.
        if (referer != null) {
            h.put("HTTP-Referer", referer);
        }
        if (title != null) {
            h.put("X-Title", title);
        }
        return h;
    }

    // --- request mapping ----------------------------------------------------

    /** Maps a request to the OpenAI {@code chat/completions} JSON body. */
    static ObjectNode toRequestJson(LlmRequest request) {
        return toRequestJson(request, ToolNames.of(request.tools()));
    }

    /** Maps a request to the JSON body, with tool names put on the wire via {@code names}. */
    static ObjectNode toRequestJson(LlmRequest request, ToolNames names) {
        ObjectNode root = MAPPER.createObjectNode();

        // Provider-specific options first (temperature, top_p, provider routing, …),
        // then the framework-controlled fields, so the latter always win.
        request.options().forEach((k, v) -> root.set(k, MAPPER.valueToTree(v)));
        root.put("model", request.model());
        root.put("max_tokens", request.maxTokens());

        ArrayNode messages = root.putArray("messages");
        request.system().ifPresent(system -> {
            ObjectNode m = messages.addObject();
            m.put("role", "system");
            m.put("content", system);
        });
        for (Message message : request.messages()) {
            appendMessage(messages, message, names);
        }

        // Structured output, in the OpenAI shape OpenRouter forwards to providers.
        // `strict` asks for schema-constrained decoding rather than a best effort.
        if (request.outputSchema().isPresent()) {
            OutputSchema outputSchema = request.outputSchema().orElseThrow();
            ObjectNode format = root.putObject("response_format");
            format.put("type", "json_schema");
            ObjectNode jsonSchema = format.putObject("json_schema");
            jsonSchema.put("name", outputSchema.name());
            jsonSchema.put("strict", true);
            jsonSchema.set("schema", MAPPER.valueToTree(outputSchema.schema()));

            // OpenRouter silently drops parameters the routed provider does not support,
            // which would turn a constrained call into unconstrained prose with a 200.
            // require_parameters restricts routing to providers that honour the schema,
            // so an unsupported model is an error rather than a wrong answer.
            JsonNode existing = root.get("provider");
            ObjectNode provider = existing instanceof ObjectNode node ? node : root.putObject("provider");
            provider.put("require_parameters", true);
        } else {
            // response_format is a framework-controlled field; drop any passed via
            // options so it can't leak through when the request declares no schema.
            root.remove("response_format");
        }

        List<ToolSpec> tools = request.tools();
        if (tools.isEmpty()) {
            // tools is a framework-controlled field; drop any passed via options so it
            // can't leak through when the request declares no tools.
            root.remove("tools");
        } else {
            ArrayNode toolsArray = root.putArray("tools");
            for (ToolSpec spec : tools) {
                ObjectNode tool = toolsArray.addObject();
                tool.put("type", "function");
                ObjectNode function = tool.putObject("function");
                function.put("name", names.wire(spec.name()));
                function.put("description", spec.description());
                function.set("parameters", MAPPER.valueToTree(spec.inputSchema()));
            }
        }
        return root;
    }

    private static void appendMessage(ArrayNode messages, Message message, ToolNames names) {
        switch (message.role()) {
            case SYSTEM -> throw new LlmException(
                    "SYSTEM messages must be provided via LlmRequest.system(), not in the message list");
            case USER -> appendUserMessage(messages, message);
            case ASSISTANT -> appendAssistantMessage(messages, message, names);
        }
    }

    /**
     * A user turn. Tool results become their own {@code role: "tool"} messages keyed
     * by {@code tool_call_id} (OpenAI's shape), and any plain text becomes a
     * {@code role: "user"} message.
     */
    private static void appendUserMessage(ArrayNode messages, Message message) {
        StringBuilder text = new StringBuilder();
        List<ToolResultBlock> toolResults = new ArrayList<>();
        List<ImageBlock> images = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (block instanceof TextBlock t) {
                appendLine(text, t.text());
            } else if (block instanceof ToolResultBlock r) {
                toolResults.add(r);
            } else if (block instanceof ImageBlock i) {
                images.add(i);
            }
            // A user turn carries no tool-use or thinking blocks; ignore if present.
        }
        for (ToolResultBlock result : toolResults) {
            ObjectNode m = messages.addObject();
            m.put("role", "tool");
            m.put("tool_call_id", result.toolUseId());
            // OpenAI tool messages have no error flag; a failed result is conveyed in
            // the content, which the model reads as the tool's output.
            m.put("content", result.content());
        }
        // Emit a user text message when there is text, or when the turn had no tool
        // results at all (a plain user turn — never an empty message alongside results).
        if (text.length() > 0 || toolResults.isEmpty() || !images.isEmpty()) {
            ObjectNode m = messages.addObject();
            m.put("role", "user");
            if (images.isEmpty()) {
                m.put("content", text.toString());
            } else {
                // OpenAI's multimodal shape, which OpenRouter passes through: content becomes
                // an ARRAY of parts rather than a string, and an image is a data URI under
                // image_url. Only when there is an image — a plain turn keeps the string form,
                // because a provider behind OpenRouter that has never seen the array form is
                // a provider this adapter should not break for the sake of uniformity.
                ArrayNode parts = m.putArray("content");
                if (text.length() > 0) {
                    ObjectNode part = parts.addObject();
                    part.put("type", "text");
                    part.put("text", text.toString());
                }
                for (ImageBlock image : images) {
                    ObjectNode part = parts.addObject();
                    part.put("type", "image_url");
                    part.putObject("image_url")
                            .put("url", "data:" + image.mediaType() + ";base64,"
                                    + image.base64());
                }
            }
        }
    }

    /**
     * An assistant turn. Text becomes {@code content}; tool-use blocks become
     * {@code tool_calls}. When the turn is only tool calls, {@code content} is null,
     * as OpenAI expects.
     */
    private static void appendAssistantMessage(ArrayNode messages, Message message, ToolNames names) {
        StringBuilder text = new StringBuilder();
        List<ProposedCall> toolUses = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (block instanceof TextBlock t) {
                appendLine(text, t.text());
            } else if (block instanceof ImageBlock) {
                // An assistant turn does not carry images in this framework — a model
                // produces text and tool calls, not pictures — and OpenAI's assistant message
                // has nowhere to put one. Named rather than swept into the else, so a future
                // block type does not inherit an "ignore" that was written about this one.
                appendLine(text, "(an image, which an assistant turn cannot replay)");
            } else if (block instanceof ProposedCall u) {
                // Both shapes, because both have to be echoed (#246). OpenAI matches a
                // tool message to a tool_call by id and rejects one with no call, so
                // dropping the refused call here would invalidate the very turn that
                // carries its refusal. A refused call goes out with "{}" for arguments —
                // the framework never held the model's tree and this block does not either.
                toolUses.add(u);
            }
            // A ThinkingBlock cannot be replayed through the OpenAI chat format; drop it.
        }
        ObjectNode m = messages.addObject();
        m.put("role", "assistant");
        if (text.length() > 0) {
            m.put("content", text.toString());
        } else if (toolUses.isEmpty()) {
            // An OpenAI assistant message must carry content or tool_calls; a bare
            // content:null is rejected. An empty model turn replays as empty text.
            m.put("content", "");
        } else {
            m.putNull("content"); // only tool calls: content is null, as OpenAI expects
        }
        if (!toolUses.isEmpty()) {
            ArrayNode calls = m.putArray("tool_calls");
            for (ProposedCall use : toolUses) {
                ObjectNode call = calls.addObject();
                call.put("id", use.id());
                call.put("type", "function");
                ObjectNode function = call.putObject("function");
                function.put("name", names.wire(use.name()));
                // OpenAI carries the arguments as a JSON-encoded string, not an object.
                function.put("arguments", writeArguments(
                        use instanceof ToolUseBlock u ? u.input() : Map.of()));
            }
        }
    }

    private static void appendLine(StringBuilder text, String line) {
        if (text.length() > 0) {
            text.append('\n');
        }
        text.append(line);
    }

    private static String writeArguments(Map<String, Object> input) {
        try {
            return MAPPER.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /**
     * A per-request bijection between AgentKit tool names and the names put on the wire.
     *
     * <p>AgentKit places no character constraint on a tool name, and the itops example
     * names its tools {@code ticketing.get_ticket}-style. The providers behind OpenRouter
     * do constrain them: Anthropic rejects a name outside {@code ^[a-zA-Z0-9_-]{1,128}$}
     * with an HTTP 400, and OpenAI documents {@code ^[a-zA-Z0-9_-]{1,64}$} for function
     * names. OpenRouter forwards the tools array as-is, so a dotted name made every call
     * routed to an Anthropic model fail before the model saw the request.
     *
     * <p>So each request builds this mapping from its tool list: every name is rewritten
     * into the safe alphabet (each disallowed character becomes {@code _}, bounded to the
     * stricter 64-character limit), and a rewrite that collides with another tool's takes
     * a numeric suffix — {@code a.b} and {@code a_b} in one request go out as {@code a_b}
     * and {@code a_b_2}, not as one tool. The response's tool calls come back through
     * {@link #original}, so the {@link ProposedCall} the runner gates and dispatches names
     * the tool the caller registered, and the caller never learns the wire spelling.
     *
     * <p>{@link #wire} also covers a name with no entry — an echoed assistant turn can
     * carry a call to a tool the current request no longer offers — by rewriting it on the
     * fly. That fallback cannot consult this request's collision suffixes, which is
     * accepted: providers match an echoed call to its result by id, not by name.
     */
    static final class ToolNames {

        /** OpenAI's documented bound, the stricter of the providers'. */
        private static final int MAX_WIRE_LENGTH = 64;

        private final Map<String, String> wireByOriginal = new LinkedHashMap<>();
        private final Map<String, String> originalByWire = new LinkedHashMap<>();

        private ToolNames() {
        }

        /** The mapping for one request's tool list. */
        static ToolNames of(List<ToolSpec> tools) {
            ToolNames names = new ToolNames();
            for (ToolSpec spec : tools) {
                if (names.wireByOriginal.containsKey(spec.name())) {
                    continue; // the same name maps the same way, however often it appears
                }
                String base = sanitize(spec.name());
                String wire = base;
                for (int n = 2; names.originalByWire.containsKey(wire); n++) {
                    String suffix = "_" + n;
                    wire = base.substring(0, Math.min(base.length(), MAX_WIRE_LENGTH - suffix.length()))
                            + suffix;
                }
                names.wireByOriginal.put(spec.name(), wire);
                names.originalByWire.put(wire, spec.name());
            }
            return names;
        }

        /** The name to put on the wire for {@code original}. */
        String wire(String original) {
            String mapped = wireByOriginal.get(original);
            return mapped != null ? mapped : sanitize(original);
        }

        /** The registered name behind a wire name, or the wire name itself if unmapped. */
        String original(String wire) {
            return originalByWire.getOrDefault(wire, wire);
        }

        private static String sanitize(String name) {
            StringBuilder wire = new StringBuilder(name.length());
            for (int i = 0; i < name.length() && wire.length() < MAX_WIRE_LENGTH; i++) {
                char c = name.charAt(i);
                boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '_' || c == '-';
                wire.append(safe ? c : '_');
            }
            // The providers' patterns also reject the empty string.
            return wire.length() == 0 ? "tool" : wire.toString();
        }
    }

    // --- response mapping ---------------------------------------------------

    private LlmResponse parseResponse(OpenRouterTransport.HttpResult result, ToolNames names) {
        // Status first, using a parse-or-slice helper: a non-2xx body may be non-JSON
        // (a gateway's HTML 502), and its text is the most useful diagnostic.
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw new LlmException("OpenRouter HTTP " + result.statusCode() + ": " + errorBody(result.body()));
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(result.body());
        } catch (JsonProcessingException e) {
            throw new LlmException(
                    "OpenRouter returned an unparseable body (HTTP " + result.statusCode() + ")", e);
        }
        // OpenRouter can return a 200 whose body carries an error object.
        if (root.hasNonNull("error")) {
            throw new LlmException("OpenRouter error: " + errorMessage(root, result.body()));
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LlmException("OpenRouter response contained no choices");
        }
        JsonNode choice = choices.get(0);
        JsonNode messageNode = choice.path("message");

        List<ContentBlock> blocks = new ArrayList<>();
        if (messageNode.hasNonNull("content")) {
            String content = messageNode.path("content").asText("");
            if (!content.isEmpty()) {
                blocks.add(TextBlock.of(content));
            }
        }
        boolean hasToolCalls = false;
        JsonNode toolCalls = messageNode.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                hasToolCalls = true;
                // Distinct when absent, not "". OpenRouter fronts hundreds of upstreams and
                // a missing tool-call id is a well-documented condition on OpenAI-compatible
                // streaming, so defaulting to "" made two id-less calls in one turn collide
                // — a collision this client manufactured, which the model could not fix by
                // reissuing because the id was never its to choose (#119).
                String id = call.path("id").asText("");
                if (id.isEmpty()) {
                    id = "synth_" + blocks.size();
                }
                JsonNode function = call.path("function");
                // Back through the same mapping the request went out under, so the call
                // the runner sees names the tool the caller registered.
                String name = names.original(function.path("name").asText(""));
                // Through the one door (#246, #271), like the streamed path below and like
                // the Anthropic client: arguments this framework will not carry, and
                // arguments it could not read at all, both become an UnusableToolUseBlock
                // the runner refuses with a reason — instead of an exception that takes the
                // turn, and instead of an empty-argument call nobody is told about.
                blocks.add(toProposedCall(id, name, function.path("arguments").asText("")));
            }
        }
        if (blocks.isEmpty()) {
            blocks.add(TextBlock.of(""));
        }

        String finishReason = choice.path("finish_reason").asText("");
        // A tool-call response is TOOL_USE even if a provider labels finish_reason "stop".
        LlmStopReason stopReason = hasToolCalls ? LlmStopReason.TOOL_USE : mapFinishReason(finishReason);

        Message assistant = Message.of(Role.ASSISTANT, blocks);
        return new LlmResponse(assistant, stopReason, parseUsage(root.path("usage")),
                finishReason.isEmpty() ? Optional.empty() : Optional.of(finishReason));
    }

    /**
     * Consumes an SSE {@code chat/completions} stream: emits each text delta live via
     * {@code handler}, accumulates tool-call fragments by index, and assembles the
     * same {@link LlmResponse} the non-streaming path would return.
     */
    private LlmResponse parseStream(OpenRouterTransport.StreamResult stream, StreamHandler handler,
            ToolNames names) {
        if (stream.statusCode() < 200 || stream.statusCode() >= 300) {
            String body = stream.lines().collect(Collectors.joining("\n"));
            throw new LlmException("OpenRouter HTTP " + stream.statusCode() + ": " + errorBody(body));
        }

        StringBuilder text = new StringBuilder();
        Map<Integer, ToolCallAccumulator> toolCalls = new TreeMap<>();
        String finishReason = "";
        TokenUsage usage = TokenUsage.ZERO;

        Iterator<String> lines = stream.lines().iterator();
        while (lines.hasNext()) {
            String line = lines.next();
            // SSE: only "data:" lines carry payload; ": ..." keep-alive comments and
            // blank event separators are skipped.
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring("data:".length()).strip();
            if (data.equals("[DONE]")) {
                break;
            }
            JsonNode chunk;
            try {
                chunk = MAPPER.readTree(data);
            } catch (JsonProcessingException e) {
                throw new LlmException("OpenRouter sent an unparseable stream chunk", e);
            }
            if (chunk.hasNonNull("error")) {
                throw new LlmException("OpenRouter stream error: " + errorMessage(chunk, data));
            }

            JsonNode choices = chunk.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode choice = choices.get(0);
                JsonNode delta = choice.path("delta");
                if (delta.hasNonNull("content")) {
                    String piece = delta.path("content").asText("");
                    if (!piece.isEmpty()) {
                        text.append(piece);
                        handler.onTextDelta(piece);
                    }
                }
                JsonNode deltaToolCalls = delta.path("tool_calls");
                if (deltaToolCalls.isArray()) {
                    for (JsonNode call : deltaToolCalls) {
                        ToolCallAccumulator acc = toolCalls.computeIfAbsent(
                                call.path("index").asInt(0), i -> new ToolCallAccumulator());
                        if (call.hasNonNull("id")) {
                            acc.id = call.path("id").asText();
                        }
                        JsonNode function = call.path("function");
                        if (function.hasNonNull("name")) {
                            acc.name = function.path("name").asText();
                        }
                        if (function.hasNonNull("arguments")) {
                            acc.arguments.append(function.path("arguments").asText());
                        }
                    }
                }
                String reason = choice.path("finish_reason").asText("");
                if (!reason.isEmpty()) {
                    finishReason = reason;
                }
            }
            if (chunk.path("usage").isObject()) {
                usage = parseUsage(chunk.path("usage"));
            }
        }

        List<ContentBlock> blocks = new ArrayList<>();
        if (text.length() > 0) {
            blocks.add(TextBlock.of(text.toString()));
        }
        boolean hasToolCalls = !toolCalls.isEmpty();
        for (ToolCallAccumulator acc : toolCalls.values()) {
            // Same on the streamed path, where an id is likelier to be missing still.
            blocks.add(toProposedCall(
                    acc.id == null || acc.id.isEmpty() ? "synth_" + blocks.size() : acc.id,
                    names.original(acc.name == null ? "" : acc.name),
                    acc.arguments.toString()));
        }
        if (blocks.isEmpty()) {
            blocks.add(TextBlock.of(""));
        }
        LlmStopReason stopReason = hasToolCalls ? LlmStopReason.TOOL_USE : mapFinishReason(finishReason);
        Message assistant = Message.of(Role.ASSISTANT, blocks);
        return new LlmResponse(assistant, stopReason, usage,
                finishReason.isEmpty() ? Optional.empty() : Optional.of(finishReason));
    }

    /** The provider error message from an error body (JSON or not), bounded in length. */
    private static String errorBody(String body) {
        try {
            return errorMessage(MAPPER.readTree(body), body);
        } catch (JsonProcessingException e) {
            return bounded(body);
        }
    }

    /** Assembles the fragments of one streamed tool call across SSE chunks. */
    private static final class ToolCallAccumulator {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }

    static LlmStopReason mapFinishReason(String finishReason) {
        if (finishReason == null) {
            return LlmStopReason.OTHER;
        }
        return switch (finishReason) {
            case "stop" -> LlmStopReason.END_TURN;
            case "length" -> LlmStopReason.MAX_TOKENS;
            case "tool_calls", "function_call" -> LlmStopReason.TOOL_USE;
            case "content_filter" -> LlmStopReason.REFUSAL;
            default -> LlmStopReason.OTHER;
        };
    }

    private static TokenUsage parseUsage(JsonNode usage) {
        if (!usage.isObject()) {
            return TokenUsage.ZERO;
        }
        long input = Math.max(0, usage.path("prompt_tokens").asLong(0));
        long output = Math.max(0, usage.path("completion_tokens").asLong(0));
        return new TokenUsage(input, output);
    }

    /**
     * The turn's block for one tool call, through {@link ProposedCall}'s two doors.
     *
     * <h4>What a malformed arguments string used to do (#271)</h4>
     *
     * <p>{@link #parseArguments} used to swallow its own failure and return {@code Map.of()},
     * under a test named {@code malformedToolArgumentsBecomeEmptyRatherThanCrashing}. So a
     * call the provider sent with arguments this client could not read <strong>ran with no
     * arguments rather than the model's</strong>: the gate judged {@code {}}, the tool
     * received {@code {}}, and nobody was told. Not crashing was the right half of that; the
     * wrong half was choosing to run something instead of saying so.
     *
     * <p>This is the adapter where the failure is <em>ordinary</em> rather than exotic.
     * OpenRouter fronts hundreds of upstreams, {@code arguments} is a JSON document carried
     * inside a JSON string, and it is assembled across SSE chunks on the streamed path — so
     * a truncated stream leaves a fragment that cannot parse. A bare scalar and an array are
     * refused by Jackson for the same reason, and nesting past Jackson's own stream limit is
     * a third: all of them used to reach a tool as {@code {}}.
     *
     * <p>Empty arguments still mean empty arguments. The OpenAI-compatible wire spells a
     * no-argument call {@code "arguments": ""} and some upstreams spell it {@code "null"};
     * both keep their old reading, because each has exactly one, and neither is a document
     * this client failed to read.
     *
     * <p>{@link RuntimeException} is caught beside Jackson's checked failure, which is wider
     * than the old catch rather than narrower: anything else out of {@code readValue} used
     * to escape into the parse and take the whole turn with it, which is #246's defect.
     */
    private static ProposedCall toProposedCall(String id, String name, String arguments) {
        Map<String, Object> parsed;
        try {
            parsed = parseArguments(arguments);
        } catch (JsonProcessingException | RuntimeException unreadable) {
            return ProposedCall.unreadable(id, name, unreadable);
        }
        return ProposedCall.of(id, name, parsed);
    }

    /**
     * The arguments as a map, or a throw naming what could not be read.
     *
     * <p>Blank and JSON {@code null} are the two spellings of "this call takes no
     * arguments" and stay empty; every other document this cannot read now throws to
     * {@link #toProposedCall}, which refuses the call rather than running it empty (#271).
     */
    private static Map<String, Object> parseArguments(String arguments)
            throws JsonProcessingException {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        Map<String, Object> map = MAPPER.readValue(arguments, MAP_TYPE);
        return map == null ? Map.of() : map;
    }

    /** The provider's error message, else a bounded slice of the raw body. */
    private static String errorMessage(JsonNode root, String rawBody) {
        JsonNode error = root.path("error");
        String message = error.path("message").asText("");
        if (!message.isEmpty()) {
            return bounded(message);
        }
        return bounded(rawBody == null ? "" : rawBody.strip());
    }

    /**
     * Remote text, bounded, for an {@link LlmException} message.
     *
     * <p>Bounded here and <em>not</em> escaped here, which was the first attempt and was
     * wrong twice over. Escaping at this source and again at each log sink is not
     * idempotent: {@code Quoted} escapes {@code \} so that its output can be read back, so
     * a body carrying a real newline arrived at the operator as {@code \\u000A} — which by
     * that class's own rule means the remote sent six literal characters, and it did not.
     * The log channel is closed at the sink, by {@code Quoted.failure}, where it is closed
     * once.
     *
     * <p>And escaping is the wrong strategy for the half that reaches the model. This is a
     * body a model reads, not a name it must be able to repeat back, so every backslash in
     * a provider's diagnostic doubling and every newline in a JSON snippet becoming six
     * characters is cost paid against the common case — a real error the model should act
     * on. Fencing is the strategy for a body; see #113.
     *
     * <p>Cut through {@link Cut} rather than {@code substring}, which landed between the
     * halves of a surrogate pair and handed the model a string that does not survive a
     * UTF-8 round trip.
     */
    private static String bounded(String raw) {
        return Cut.to(raw, MAX_ERROR_BODY_CHARS);
    }

    /** Builder for {@link OpenRouterLlmClient}. */
    public static final class Builder {
        private final String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        // Resolved lazily in build(): a caller that injects a transport (e.g. a test
        // fake) never constructs the default JDK client.
        private OpenRouterTransport transport;
        private String referer;
        private String title;

        private Builder(String apiKey) {
            this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
            if (apiKey.isBlank()) {
                throw new IllegalArgumentException("apiKey must not be blank");
            }
        }

        /** Overrides the API base URL (default {@value #DEFAULT_BASE_URL}). */
        public Builder baseUrl(String baseUrl) {
            Objects.requireNonNull(baseUrl, "baseUrl");
            // Normalise so baseUrl + "/chat/completions" is always well-formed.
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return this;
        }

        /** Overrides the HTTP transport (default: the JDK {@link java.net.http.HttpClient}). */
        public Builder transport(OpenRouterTransport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            return this;
        }

        /** Sets the optional {@code HTTP-Referer} header OpenRouter uses for app attribution. */
        public Builder referer(String referer) {
            this.referer = referer;
            return this;
        }

        /** Sets the optional {@code X-Title} header OpenRouter uses for app attribution. */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public OpenRouterLlmClient build() {
            if (transport == null) {
                transport = OpenRouterTransport.jdk();
            }
            return new OpenRouterLlmClient(this);
        }
    }
}
