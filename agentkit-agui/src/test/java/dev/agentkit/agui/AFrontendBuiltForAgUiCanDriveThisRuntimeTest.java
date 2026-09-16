package dev.agentkit.agui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.ChatUnavailable;
import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.View;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The protocol as it actually goes over the wire.
 *
 * <h2>Read as bytes, not as objects</h2>
 *
 * <p>The thing on the other end of this is somebody else's client, written to a published
 * schema, and the only claim worth making is about what it receives. A test that called
 * {@code translate} and compared maps would pass against a server that never framed an SSE
 * message correctly, never set the content type, or spelled the event type in the PascalCase
 * the prose uses rather than the SCREAMING_SNAKE_CASE the SDK's enum declares.
 *
 * <p>So this posts a real {@code RunAgentInput} to a real socket and parses the
 * {@code data:} frames back.
 *
 * <h2>What is not proved here</h2>
 *
 * <p>That CopilotKit or assistant-ui actually render it. Standing one of those up is a Node
 * application and a browser, and this checks the contract they are written against instead —
 * the event names, the required fields, and the ordering rules the protocol states. That is a
 * real gap and it is #389 rather than a claim quietly made here: a schema can be right while a
 * client still chokes on something the spec left implicit.
 */
class AFrontendBuiltForAgUiCanDriveThisRuntimeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatStore store = new InMemoryChatStore();
    private final ChatEvents events = new ChatEvents();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private ChatRuntime runtime;
    private AgUiServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        if (runtime != null) {
            runtime.close();
        }
    }

    private void start(ChatRuntime.Agents agents) throws IOException {
        runtime = new ChatRuntime(store, events, agents);
        server = new AgUiServer(0, runtime, "acme");
        server.start();
    }

    /** A model that streams three fragments, calls a tool, then answers. */
    private static LlmClient talking() {
        return new LlmClient() {
            private int calls;

            @Override
            public LlmResponse generate(LlmRequest request) {
                if (calls++ == 0) {
                    return LlmResponse.of(Message.of(Role.ASSISTANT,
                                    ProposedCall.of("c1", "stats.open", Map.of("limit", 5))),
                            LlmStopReason.TOOL_USE, TokenUsage.ZERO);
                }
                return LlmResponse.of(Message.assistant("Twelve are open."),
                        LlmStopReason.END_TURN, new TokenUsage(10, 4));
            }

            @Override
            public LlmResponse generate(LlmRequest request,
                    dev.agentkit.core.llm.StreamHandler handler) {
                if (calls == 0) {
                    return generate(request);
                }
                for (String piece : List.of("Twelve ", "are ", "open.")) {
                    handler.onTextDelta(piece);
                }
                calls++;
                return LlmResponse.of(Message.assistant("Twelve are open."),
                        LlmStopReason.END_TURN, new TokenUsage(10, 4));
            }
        };
    }

    private static SimpleToolRegistry counting() {
        SimpleToolRegistry registry = new SimpleToolRegistry();
        registry.register(FunctionTool.builder("stats.open", "How many are open.")
                .schema(Map.of("type", "object", "properties",
                        Map.of("limit", Map.of("type", "integer"))))
                .readOnly()
                .handler(invocation -> ToolResult.ok("12 are open.")
                        .withView(View.stat("open", 12, "up 3")))
                .build());
        return registry;
    }

    @Test
    void aRunIsAStreamOfAgUiEventsInTheOrderTheProtocolRequires() throws Exception {
        start(session -> session.agent(talking(), counting(),
                AgentConfig.builder("m").maxSteps(4).build()).build());

        List<Map<String, Object>> stream = run("thread-1", "how many are open?");
        List<String> types = stream.stream().map(e -> String.valueOf(e.get("type"))).toList();

        // SCREAMING_SNAKE_CASE, which is what the SDK's EventType enum declares — the docs'
        // prose calls them TextMessageContent and a client validating against the schema
        // would refuse that spelling.
        assertThat(types).contains("RUN_STARTED", "TOOL_CALL_START", "TOOL_CALL_ARGS",
                "TOOL_CALL_END", "TOOL_CALL_RESULT", "TEXT_MESSAGE_START",
                "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "RUN_FINISHED");
        assertThat(types.getFirst()).isEqualTo("RUN_STARTED");
        assertThat(types.getLast()).isEqualTo("RUN_FINISHED");

        // The ordering rules that make a client's state machine work. A CONTENT before its
        // START, or a START never closed, is the failure mode a frontend shows as an answer
        // that never stops loading.
        assertThat(types.indexOf("TEXT_MESSAGE_START"))
                .isLessThan(types.indexOf("TEXT_MESSAGE_CONTENT"));
        assertThat(types.lastIndexOf("TEXT_MESSAGE_CONTENT"))
                .isLessThan(types.indexOf("TEXT_MESSAGE_END"));
        assertThat(types.indexOf("TOOL_CALL_START"))
                .isLessThan(types.indexOf("TOOL_CALL_END"));
    }

    @Test
    void everyEventCarriesTheFieldsItsSchemaRequires() throws Exception {
        start(session -> session.agent(talking(), counting(),
                AgentConfig.builder("m").maxSteps(4).build()).build());

        List<Map<String, Object>> stream = run("thread-1", "how many?");

        Map<String, Object> started = only(stream, "RUN_STARTED");
        assertThat(started).containsKeys("threadId", "runId");
        Map<String, Object> textStart = only(stream, "TEXT_MESSAGE_START");
        assertThat(textStart).containsKeys("messageId").containsEntry("role", "assistant");
        // Every CONTENT carries the id its START opened, or a client cannot attach the delta
        // to anything.
        for (Map<String, Object> content : all(stream, "TEXT_MESSAGE_CONTENT")) {
            assertThat(content.get("messageId")).isEqualTo(textStart.get("messageId"));
            assertThat(String.valueOf(content.get("delta"))).isNotEmpty();
        }
        assertThat(only(stream, "TEXT_MESSAGE_END").get("messageId"))
                .isEqualTo(textStart.get("messageId"));

        Map<String, Object> toolStart = only(stream, "TOOL_CALL_START");
        assertThat(toolStart).containsKeys("toolCallId")
                .containsEntry("toolCallName", "stats.open");
        // TOOL_CALL_ARGS' delta is a fragment of the arguments AS JSON TEXT, not an object.
        // A client concatenates deltas and parses the result; handing it a map would give it
        // "{limit=5}" to parse.
        Object delta = only(stream, "TOOL_CALL_ARGS").get("delta");
        assertThat(delta).isInstanceOf(String.class);
        assertThat(JSON.readTree(String.valueOf(delta)).get("limit").asInt()).isEqualTo(5);
        assertThat(only(stream, "TOOL_CALL_END").get("toolCallId"))
                .isEqualTo(toolStart.get("toolCallId"));
        Map<String, Object> result = only(stream, "TOOL_CALL_RESULT");
        assertThat(result).containsKeys("messageId", "toolCallId", "content");
        // What the MODEL was handed, which is what a tool result means. Not the view.
        assertThat(String.valueOf(result.get("content"))).contains("12 are open.");

        assertThat(stream).allSatisfy(event ->
                assertThat(event).containsKey("timestamp"));
    }

    @Test
    void theThingsAgUiHasNoWordForGoAcrossAsCustomRatherThanAsALie() throws Exception {
        start(session -> session.agent(talking(), counting(),
                AgentConfig.builder("m").maxSteps(4).build()).build());

        List<Map<String, Object>> stream = run("thread-1", "how many?");
        List<Map<String, Object>> custom = all(stream, "CUSTOM");

        // A view is not state and it is not a message. CUSTOM is the protocol's own escape
        // hatch, a frontend that does not know the name ignores it, and that is the honest
        // outcome — dressing it as a STATE_SNAPSHOT would make every later snapshot either
        // repeat it or appear to delete it.
        assertThat(custom).anySatisfy(event -> {
            assertThat(event.get("name")).isEqualTo("agentkit.view");
            assertThat(event.get("value")).isInstanceOf(Map.class);
        });
    }

    @Test
    void anApprovalGoesAcrossAsCustomRatherThanAsATheProtocolHasNoWordFor() throws Exception {
        // AG-UI's answer to human-in-the-loop is a frontend-declared tool the agent calls.
        // There is no APPROVAL_REQUESTED, and inventing a TOOL_CALL_START for a decision
        // nobody on the far side can answer would put a spinner on somebody's screen forever.
        start(session -> session.agent(talking(), counting(),
                        AgentConfig.builder("m").maxSteps(4).build())
                .toolGate(dev.agentkit.core.reliability.ToolGates.requireApproval(
                        invocation -> true, session.approver()))
                .build());

        List<Map<String, Object>> stream = runUntilAsked("thread-1", "how many?");

        assertThat(all(stream, "CUSTOM")).anySatisfy(event -> {
            assertThat(event.get("name")).isEqualTo("agentkit.approval");
            @SuppressWarnings("unchecked")
            Map<String, Object> value = (Map<String, Object>) event.get("value");
            // Everything a card needs, so a frontend that DOES know the name can draw one.
            assertThat(value).containsKeys("approvalId", "tool");
        });
        // And not dressed as something it is not.
        assertThat(stream.stream()
                .filter(e -> "TOOL_CALL_START".equals(e.get("type")))
                .map(e -> String.valueOf(e.get("toolCallName"))))
                .doesNotContain("approval");
    }

    @Test
    void oneRunsStreamCarriesOnlyThatRun() throws Exception {
        start(session -> session.agent(talking(), counting(),
                AgentConfig.builder("m").maxSteps(4).build()).build());

        // Two runs on one thread. AG-UI's client posts the whole conversation every time, so
        // this is the ordinary case rather than a contrived one — and a second run that
        // replayed the first would open with a RUN_FINISHED and end before it began.
        List<Map<String, Object>> first = run("thread-1", "how many are open?");
        List<Map<String, Object>> second = run("thread-1", "and now?");

        // One conversation, two turns — which is the precondition, and which the first draft
        // of this adapter did not meet: it looked the thread id up as a conversation id, never
        // found one, and made a NEW conversation per request. Assert it before asserting
        // anything about the streams, or this measures two unrelated conversations agreeing.
        assertThat(store.conversations("acme")).hasSize(1);
        assertThat(store.turns("acme", store.conversations("acme").getFirst().id())).hasSize(2);

        assertThat(first).isNotEmpty();
        assertThat(second).isNotEmpty();
        assertThat(second.getFirst().get("type")).isEqualTo("RUN_STARTED");
        assertThat(second.getLast().get("type")).isEqualTo("RUN_FINISHED");
        // Exactly one of each, which is the claim: no event of the first run is in the second.
        assertThat(all(second, "RUN_STARTED")).hasSize(1);
        assertThat(all(second, "RUN_FINISHED")).hasSize(1);
        assertThat(String.valueOf(second.getFirst().get("runId")))
                .isNotEqualTo(String.valueOf(first.getFirst().get("runId")));
    }

    @Test
    void aTurnThatFailsIsARunErrorRatherThanASilentEnd() throws Exception {
        start(session -> {
            throw new ChatUnavailable("No model is configured. Set CHAT_LLM, then restart.");
        });

        List<String> types = run("thread-1", "hello").stream()
                .map(e -> String.valueOf(e.get("type"))).toList();

        // The turn failed before a delta ever arrived, so there is no message to close — and
        // closing one that was never opened is the mirror of the error the bookkeeping exists
        // to prevent.
        assertThat(types).doesNotContain("TEXT_MESSAGE_END");
        assertThat(types).contains("RUN_FINISHED");
        assertThat(run("thread-2", "hello")).anySatisfy(event -> {
            if ("RUN_FINISHED".equals(event.get("type"))) {
                assertThat(String.valueOf(event.get("result")))
                        .contains("No model is configured");
            }
        });
    }

    @Test
    void aRequestThatIsNotARunIsRefusedWithASentence() throws Exception {
        start(session -> session.agent(talking(), counting(),
                AgentConfig.builder("m").maxSteps(4).build()).build());

        HttpResponse<String> noMessages = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/agent"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"threadId\":\"t\",\"messages\":[]}")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(noMessages.statusCode()).isEqualTo(400);
        assertThat(noMessages.body()).contains("user message");

        HttpResponse<String> wrongMethod = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/agent"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(wrongMethod.statusCode()).isEqualTo(405);
    }

    @Test
    void aFrontendsOwnToolsAreNotSilentlyAdoptedIntoTheAgent() {
        // AG-UI's answer to human-in-the-loop is that the frontend declares a tool and the
        // agent calls it. Honouring that here would let whoever can reach this port widen
        // what the agent may do, which is the one thing the gate exists to prevent — so they
        // are named rather than either used or silently dropped.
        assertThat(AgUiServer.ignoredFrontendTools(Map.of("tools",
                List.of(Map.of("name", "confirm"), Map.of("name", "pickADate")))))
                .containsExactly("confirm", "pickADate");
        assertThat(AgUiServer.ignoredFrontendTools(Map.of())).isEmpty();
    }

    // --- driving it the way somebody else's client would --------------------------------

    /** Posts a RunAgentInput and reads the SSE frames until the run finishes. */
    private List<Map<String, Object>> run(String threadId, String said) throws Exception {
        String body = JSON.writeValueAsString(Map.of(
                "threadId", threadId,
                "runId", "run-" + threadId,
                "state", Map.of(),
                "messages", List.of(Map.of("id", "m1", "role", "user", "content", said)),
                "tools", List.of(),
                "context", List.of(),
                "forwardedProps", Map.of()));

        HttpResponse<java.io.InputStream> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/agent"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofInputStream());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .contains("text/event-stream; charset=utf-8");

        List<Map<String, Object>> stream = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> event = JSON.readValue(line.substring(6), Map.class);
                stream.add(event);
                if ("RUN_FINISHED".equals(event.get("type"))
                        || "RUN_ERROR".equals(event.get("type"))) {
                    break;
                }
            }
        }
        assertThat(stream).as("the stream said nothing at all").isNotEmpty();
        return stream;
    }

    /** As {@link #run}, but stops at the approval rather than waiting for a run that parked. */
    private List<Map<String, Object>> runUntilAsked(String threadId, String said)
            throws Exception {
        String body = JSON.writeValueAsString(Map.of(
                "threadId", threadId, "runId", "run-" + threadId, "state", Map.of(),
                "messages", List.of(Map.of("id", "m1", "role", "user", "content", said)),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of()));
        HttpResponse<java.io.InputStream> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/agent"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofInputStream());

        List<Map<String, Object>> stream = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> event = JSON.readValue(line.substring(6), Map.class);
                stream.add(event);
                if ("agentkit.approval".equals(event.get("name"))) {
                    break;
                }
            }
        }
        return stream;
    }

    private static List<Map<String, Object>> all(List<Map<String, Object>> stream, String type) {
        return stream.stream().filter(e -> type.equals(e.get("type"))).toList();
    }

    private static Map<String, Object> only(List<Map<String, Object>> stream, String type) {
        List<Map<String, Object>> found = all(stream, type);
        assertThat(found).as("expected exactly one %s", type).hasSize(1);
        return found.getFirst();
    }
}
