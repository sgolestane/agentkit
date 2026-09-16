package dev.agentkit.openrouter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SideEffects;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A tool call whose arguments this client could not read, driven end to end (#271).
 *
 * <h2>Why this is not asserted on the parsed turn alone</h2>
 *
 * <p>{@code OpenRouterLlmClientTest} asserts that the parse hands back an
 * {@code UnusableToolUseBlock}, which is the fact the adapter owns. It cannot show the two
 * things the issue is actually about: that <em>nothing ran</em>, and that <em>the model was
 * told</em>. Both are properties of the next request, and this suite's own history says why
 * that distinction is worth the extra machinery — a stand-in that stops modelling the thing
 * it stands in for keeps its assertions green while the property dies (see
 * {@code PersuadedModel} and the note in the pentest suite's {@code UnusualArgumentTest}).
 *
 * <p>So the transport is scripted with two turns, the real client parses both, the real
 * agent loop runs between them, and what is asserted is the JSON body the client is handed
 * on the second call — not a value this test computed.
 *
 * <h2>Before and after</h2>
 *
 * <pre>
 * before -&gt; parseArguments returned Map.of(), the gate judged {}, the tool ran with {},
 *           and the model was told the call had succeeded
 * after  -&gt; no gate consulted, no tool entered, and the second request carries a tool
 *           message saying what was refused and what to send instead
 * </pre>
 */
class UnreadableArgumentsReachTheModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A transport that answers each call with the next scripted body, recording every request. */
    private static final class ScriptedTransport implements OpenRouterTransport {

        private final Deque<String> responses = new ArrayDeque<>();
        private final List<String> requests = new ArrayList<>();

        ScriptedTransport(String... bodies) {
            for (String body : bodies) {
                responses.add(body);
            }
        }

        @Override
        public HttpResult post(String url, Map<String, String> headers, String jsonBody) {
            requests.add(jsonBody);
            if (responses.isEmpty()) {
                throw new IllegalStateException("the loop asked for turn " + requests.size()
                        + ", which this transport did not script");
            }
            return new HttpResult(200, responses.removeFirst());
        }

        @Override
        public StreamResult postStreaming(String url, Map<String, String> headers, String body) {
            throw new UnsupportedOperationException("not streamed here");
        }

        JsonNode request(int index) throws Exception {
            return MAPPER.readTree(requests.get(index));
        }
    }

    /** A turn proposing one call to {@code delete_everything} with {@code arguments} verbatim. */
    private static String aTurnProposing(String arguments) {
        var message = MAPPER.createObjectNode();
        message.put("role", "assistant").putNull("content");
        var call = message.putArray("tool_calls").addObject();
        call.put("id", "t1").put("type", "function");
        call.putObject("function").put("name", "delete_everything").put("arguments", arguments);
        var root = MAPPER.createObjectNode();
        var choice = root.putArray("choices").addObject();
        choice.set("message", message);
        choice.put("finish_reason", "tool_calls");
        return root.toString();
    }

    private static String aTurnSaying(String text) {
        var root = MAPPER.createObjectNode();
        var choice = root.putArray("choices").addObject();
        choice.putObject("message").put("role", "assistant").put("content", text);
        choice.put("finish_reason", "stop");
        return root.toString();
    }

    /** What a runner is not allowed to enter, and the record of whether it did. */
    private static final class Tripwire {
        private final List<ToolInvocation> fired = new ArrayList<>();

        dev.agentkit.core.tool.Tool tool() {
            return FunctionTool.builder("delete_everything",
                            "Deletes everything matching 'target'. Irreversible.")
                    .schema(Map.of("type", "object",
                            "properties", Map.of("target", Map.of("type", "string")),
                            "required", List.of()))
                    .sideEffects(SideEffects.EXTERNAL)
                    .handler(invocation -> {
                        fired.add(invocation);
                        return ToolResult.ok("Done. Everything is gone.");
                    })
                    .build();
        }
    }

    @Test
    void aCallWhoseArgumentsCouldNotBeReadEntersNoToolAndIsAnsweredOnTheNextRequest()
            throws Exception {
        // "not json" stands in for every document parseArguments cannot read; the parse-site
        // suite runs the other shapes. What matters here is what the loop does with one.
        ScriptedTransport transport = new ScriptedTransport(
                aTurnProposing("not json"),
                aTurnSaying("Understood."));
        Tripwire tripwire = new Tripwire();
        AtomicInteger gated = new AtomicInteger();
        ToolGate counting = (tool, invocation) -> {
            gated.incrementAndGet();
            return GateResult.allow();
        };

        AgentResult result = Agent.builder(
                        OpenRouterLlmClient.builder("k").transport(transport).build(),
                        new SimpleToolRegistry().register(tripwire.tool()),
                        AgentConfig.builder("openai/gpt-4o").maxSteps(4).build())
                .toolGate(counting)
                .build()
                .run(Goal.of("Tidy up."));

        assertThat(gated.get())
                .as("a gate judged {} — arguments the model never sent")
                .isZero();
        assertThat(tripwire.fired)
                .as("a tool ran with no arguments because its arguments could not be read")
                .isEmpty();
        assertThat(result.stopReason())
                .as("an unreadable call ended the run rather than being answered")
                .isEqualTo(StopReason.COMPLETED);

        // The half that says the model was actually told: the second request, as the client
        // built it, not as this test imagined it.
        JsonNode secondTurn = transport.request(1);
        JsonNode messages = secondTurn.path("messages");
        JsonNode assistant = null;
        JsonNode toolMessage = null;
        for (JsonNode message : messages) {
            if ("assistant".equals(message.path("role").asText())) {
                assistant = message;
            } else if ("tool".equals(message.path("role").asText())) {
                toolMessage = message;
            }
        }
        assertThat(assistant).as("the refused call was dropped from the echoed turn, which"
                + " leaves a tool message with no tool_call to match it").isNotNull();
        assertThat(assistant.path("tool_calls")).hasSize(1);
        assertThat(assistant.path("tool_calls").get(0).path("id").asText()).isEqualTo("t1");
        assertThat(assistant.path("tool_calls").get(0).path("function").path("arguments")
                .asText())
                .as("the echo carries arguments the framework never held")
                .isEqualTo("{}");

        assertThat(toolMessage).as("no result was correlated to the refused call").isNotNull();
        assertThat(toolMessage.path("tool_call_id").asText()).isEqualTo("t1");
        assertThat(toolMessage.path("content").asText())
                .contains("refused before anything ran")
                .contains("No tool ran, no gate was asked")
                .contains("could not be read as a JSON object")
                .as("the model is told the shape to send back")
                .contains("a single JSON object");
    }

    @Test
    void aCallWhoseArgumentsCanBeReadStillRuns() throws Exception {
        // The denominator. Every assertion above is satisfied by a client that refuses every
        // tool call there is, which is the failure direction a fail-closed change makes easy
        // — and it would be invisible here without this arm, because a run that calls no
        // tools also ends COMPLETED with an empty tripwire.
        ScriptedTransport transport = new ScriptedTransport(
                aTurnProposing("{\"target\": \"the-staging-row\"}"),
                aTurnSaying("Understood."));
        Tripwire tripwire = new Tripwire();
        AtomicInteger gated = new AtomicInteger();

        AgentResult result = Agent.builder(
                        OpenRouterLlmClient.builder("k").transport(transport).build(),
                        new SimpleToolRegistry().register(tripwire.tool()),
                        AgentConfig.builder("openai/gpt-4o").maxSteps(4).build())
                .toolGate((tool, invocation) -> {
                    gated.incrementAndGet();
                    return GateResult.allow();
                })
                .build()
                .run(Goal.of("Tidy up."));

        assertThat(gated.get()).isEqualTo(1);
        assertThat(tripwire.fired).singleElement()
                .satisfies(invocation -> assertThat(invocation.arguments())
                        .as("the tool was entered with something other than what the model"
                                + " sent")
                        .containsEntry("target", "the-staging-row"));
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
    }
}
