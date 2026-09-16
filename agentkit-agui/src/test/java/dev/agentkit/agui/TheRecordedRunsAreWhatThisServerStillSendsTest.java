package dev.agentkit.agui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What this server actually sends, written down where a real AG-UI client can read it.
 *
 * <h2>Why a recording, and not another assertion</h2>
 *
 * <p>{@code AFrontendBuiltForAgUiCanDriveThisRuntimeTest} checks the fields and the ordering
 * rules — and it checks the rules <em>this repository wrote down</em>. That is the gap #389 was
 * filed for, and the gap turned out to be real: nobody had written down that a
 * {@code STEP_FINISHED} needs a {@code STEP_STARTED}, so the adapter sent one without the
 * other and every test here agreed with it. A conforming client does not: it rejects the
 * stream and stops reading, so the answer that arrives afterwards never reaches the screen.
 *
 * <p>What found it was {@code @ag-ui/client}'s own {@code verifyEvents} — the protocol's state
 * machine, written by the people who defined the protocol. This test exists so that keeps
 * happening: it records the bytes a real run produces, and {@code aguiRecording.test.ts} in
 * {@code agentkit-chat-ui} feeds them through that SDK on every build.
 *
 * <h2>A golden file, and why the drift check lives here</h2>
 *
 * <p>The recording is only worth anything if it is what the server sends. A fixture somebody
 * hand-edited to make a test pass measures nothing, and a fixture that silently goes stale
 * measures last year's server. So this test regenerates the stream and compares — a change to
 * the translation fails here, in the module that owns it, with the diff in the message.
 *
 * <p>Re-record with {@code -Dagui.record=true} after deciding the change is intended.
 */
class TheRecordedRunsAreWhatThisServerStillSendsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The mapper the recording is written with: indented, and with keys in a fixed order.
     *
     * <p>Sorted because the server's own key order is not stable. {@code AgUiEvents} builds
     * each frame from {@code Map.of(...)}, whose iteration order is deliberately randomised
     * per JVM, so two identical runs produce byte-different frames. That is not a defect on
     * the wire — a JSON object has no order and no client can tell — but a recording compared
     * byte for byte would fail every second run and teach everyone to re-record without
     * reading, which is exactly how a golden file stops meaning anything.
     */
    private static final ObjectMapper RECORDING = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** Where the recordings live, read from here and by the console's own test suite. */
    private static final Path RECORDED =
            Path.of("src", "test", "resources", "recorded");

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

    @Test
    void aRunThatCallsAToolAndAnswers() throws Exception {
        start(session -> session
                .agent(talking(), counting(), AgentConfig.builder("m").maxSteps(4).build())
                .build());

        assertRecorded("answers-after-a-tool", run("c1", "how many are open?"));
    }

    @Test
    void aRunWithNoModelBehindIt() throws Exception {
        // The degradation path, which a frontend has to render too — and the one that goes
        // from started to finished without ever running, so nothing opens the step that
        // closeStep would otherwise close.
        start(session -> {
            throw new ChatUnavailable("No model is configured. Set CHAT_LLM, then restart.");
        });

        assertRecorded("no-model-configured", run("c2", "how many are open?"));
    }

    /**
     * Asserts the stream equals the recording, or writes it when asked to re-record.
     *
     * @param name  the recording's file name, without an extension
     * @param frames every {@code data:} frame of one run, in order
     */
    private void assertRecorded(String name, List<Map<String, Object>> frames) throws IOException {
        Path file = RECORDED.resolve(name + ".json");
        String now = RECORDING.writeValueAsString(normalise(frames)) + "\n";

        if (Boolean.getBoolean("agui.record")) {
            Files.createDirectories(RECORDED);
            Files.writeString(file, now, StandardCharsets.UTF_8);
            return;
        }

        assertThat(file)
                .as("no recording named '%s'; produce it with -Dagui.record=true", name)
                .exists();
        assertThat(now)
                .as("this server no longer sends what '%s' records. If the translation change"
                        + " is intended, re-record with -Dagui.record=true — and read what"
                        + " changed first, because the console's own suite runs these through"
                        + " @ag-ui/client and a stream that SDK rejects is one no frontend"
                        + " built on it can render.", name)
                .isEqualTo(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * The same stream with the parts that differ per run replaced.
     *
     * <p>A turn id is minted per run and a timestamp is the wall clock, so comparing raw bytes
     * would fail every time and prove nothing. Both are replaced rather than deleted: an id
     * that vanished would take the {@code messageId}/{@code runId} correspondence with it, and
     * that correspondence is exactly what a client's state machine is checking.
     */
    private static List<Map<String, Object>> normalise(List<Map<String, Object>> frames) {
        String turnId = String.valueOf(frames.getFirst().get("runId"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> frame : frames) {
            Map<String, Object> copy = new LinkedHashMap<>();
            frame.forEach((key, value) -> copy.put(key,
                    "timestamp".equals(key) ? 1
                            : value instanceof String text ? text.replace(turnId, "run-1")
                            : value));
            out.add(copy);
        }
        return out;
    }

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

    /** A model that calls a tool, then streams its answer in three fragments. */
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
}
