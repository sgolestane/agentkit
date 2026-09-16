package dev.agentkit.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageCreateParams;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.message.UnusableToolUseBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A {@code tool_use} whose {@code input} this adapter could not convert (#271).
 *
 * <h2>What used to happen, and why it was worse than the sibling defect</h2>
 *
 * <p>{@code toArgumentMap} caught {@link RuntimeException} out of
 * {@code JsonValue.convert} and returned {@code Map.of()}. So a call whose arguments could
 * not be read did not fail — it <em>ran</em>, with no arguments rather than the model's. The
 * gate judged {@code {}}, the tool received {@code {}}, and nobody was told. #246's cliff
 * was fail-closed and merely mute; this one was fail-open at an authorization boundary.
 *
 * <h2>That the catch is live, measured rather than assumed</h2>
 *
 * <p>{@code ToolUseBlock._input()} is a raw {@code JsonValue} — the SDK does not require it
 * to be an object — so whatever the wire carried arrives unchecked. Against
 * {@code JsonValue.convert(TypeReference&lt;Map&lt;String, Object&gt;&gt;)}, SDK 2.48.0:
 *
 * <pre>
 * {"q": "x"}   -&gt; a map
 * null          -&gt; null
 * "not json"    -&gt; IllegalArgumentException (no String-argument creator)
 * 5             -&gt; IllegalArgumentException (from Integer value)
 * true          -&gt; IllegalArgumentException (from Boolean value)
 * [1, 2]        -&gt; IllegalArgumentException (from Array value)
 * </pre>
 *
 * <p>Four of six throw, so the catch was never dead and "let it throw instead" was not
 * available: throwing here is #246's defect, which takes the whole turn.
 *
 * <h2>Driven over wire payloads</h2>
 *
 * <p>Each case below is a Messages API response body, deserialized by the SDK's own mapper
 * and handed to the adapter's real response mapping — not a hand-built block, and not a
 * value this test computed. The last assertion is about the <em>next</em> request, because
 * "the model was told" is a property of that request and of nothing else.
 */
class AnthropicUnreadableArgumentsTest {

    /** A Messages API turn whose one {@code tool_use} carries {@code input} verbatim. */
    private static com.anthropic.models.messages.Message aTurnWhoseInputIs(String inputJson) {
        return aTurnWhoseToolUseIs("\"input\":" + inputJson);
    }

    /** The same, for a body that has to leave the field out altogether. */
    private static com.anthropic.models.messages.Message aTurnWhoseToolUseIs(String field) {
        String body = """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-4-8",
                 "content":[{"type":"tool_use","id":"t1","name":"search"%s}],
                 "stop_reason":"tool_use",
                 "usage":{"input_tokens":10,"output_tokens":5}}
                """.formatted(field.isEmpty() ? "" : "," + field);
        try {
            return ObjectMappers.jsonMapper()
                    .readValue(body, com.anthropic.models.messages.Message.class);
        } catch (Exception e) {
            throw new IllegalStateException("the SDK could not read this wire body: " + body, e);
        }
    }

    @Test
    void anInputThatIsNotAJsonObjectIsRefusedRatherThanRunWithNoArguments() {
        // Four shapes, each a whole response body. Before, every one of them produced a
        // ToolUseBlock("t1", "search", {}) — a call the gate judges and the tool runs.
        for (String input : List.of("\"not json\"", "5", "true", "[1, 2]")) {
            LlmResponse response = AnthropicLlmClient.toLlmResponse(aTurnWhoseInputIs(input));

            assertThat(response.stopReason()).isEqualTo(LlmStopReason.TOOL_USE);
            assertThat(response.message().content()).hasSize(1);
            assertThat(response.message().content().get(0))
                    .as("input %s reached a runner as a call it may gate and run", input)
                    .isInstanceOf(UnusableToolUseBlock.class);
            UnusableToolUseBlock refused =
                    (UnusableToolUseBlock) response.message().content().get(0);
            assertThat(refused.id()).isEqualTo("t1");
            assertThat(refused.name()).isEqualTo("search");
            assertThat(refused.refusal())
                    .contains("refused before anything ran")
                    .contains("No tool ran, no gate was asked")
                    .contains("could not be read as a JSON object");
        }
    }

    @Test
    void anObjectInputStillArrivesAsTheCallTheModelMade() {
        // The denominator. Without it every assertion above is satisfied by an adapter that
        // refuses every tool call there is, which is the failure direction a fail-closed
        // change makes easy.
        LlmResponse response =
                AnthropicLlmClient.toLlmResponse(aTurnWhoseInputIs("{\"q\": \"weather\"}"));

        assertThat(response.message().content().get(0)).isInstanceOf(ToolUseBlock.class);
        assertThat(((ToolUseBlock) response.message().content().get(0)).input())
                .containsEntry("q", "weather");
    }

    @Test
    void anAbsentOrNullInputIsACallWithNoArgumentsRatherThanARefusal() {
        // The shapes where emptying is the meaning rather than a guess: a model calling a
        // tool that takes no parameters. A change that refused these would break every such
        // tool on every call.
        //
        // The ABSENT case is the one this arm exists for, and the first version of this
        // change got it wrong: convert() answers Java null for a JSON null and THROWS for a
        // missing field, so leaving absence to the catch refused every no-argument call
        // from an upstream that omits the field. The Messages API always sends input, which
        // is exactly why nothing here would have noticed — and this client also fronts
        // Bedrock and whatever baseUrl a deployment configures. Written as a wire body with
        // no "input" key at all rather than as a JSON null, because those are two different
        // states inside the SDK and only one of them was handled.
        LlmResponse absent =
                AnthropicLlmClient.toLlmResponse(aTurnWhoseToolUseIs(""));

        assertThat(absent.message().content().get(0))
                .as("a tool_use with no input field at all was refused")
                .isInstanceOf(ToolUseBlock.class);
        assertThat(((ToolUseBlock) absent.message().content().get(0)).input()).isEmpty();

        for (String input : List.of("null", "{}")) {
            LlmResponse response = AnthropicLlmClient.toLlmResponse(aTurnWhoseInputIs(input));

            assertThat(response.message().content().get(0))
                    .as("a no-argument call spelled %s was refused", input)
                    .isInstanceOf(ToolUseBlock.class);
            assertThat(((ToolUseBlock) response.message().content().get(0)).input()).isEmpty();
        }
    }

    @Test
    void theRefusedCallIsEchoedAndItsRefusalIsCarriedOnTheNextRequest() {
        // The half the parse cannot show: that the model is told. Built from the block the
        // real parse produced, not from one this test wrote — an adapter that stopped
        // refusing would fail here on the cast rather than quietly asserting nothing.
        UnusableToolUseBlock refused = (UnusableToolUseBlock) AnthropicLlmClient
                .toLlmResponse(aTurnWhoseInputIs("\"not json\""))
                .message().content().get(0);

        MessageCreateParams next = AnthropicLlmClient.toParams(
                LlmRequest.builder("claude-opus-4-8")
                        .addMessage(Message.user("go"))
                        .addMessage(Message.of(Role.ASSISTANT, refused))
                        .addMessage(Message.of(Role.USER,
                                ToolResultBlock.error(refused.id(), refused.refusal())))
                        .build());

        assertThat(next.messages()).hasSize(3);
        var assistant = next.messages().get(1).content().blockParams().orElseThrow();
        assertThat(assistant.get(0).toolUse())
                .as("the refused call vanished from the echo, orphaning its tool_result")
                .isPresent();
        assertThat(assistant.get(0).toolUse().orElseThrow().id()).isEqualTo("t1");
        assertThat(assistant.get(0).toolUse().orElseThrow().input()._additionalProperties())
                .as("the echo carries arguments the framework never held")
                .isEmpty();

        var result = next.messages().get(2).content().blockParams().orElseThrow()
                .get(0).toolResult().orElseThrow();
        assertThat(result.toolUseId()).isEqualTo("t1");
        assertThat(result.isError()).contains(true);
        assertThat(ObjectMappers.jsonMapper().valueToTree(result).toString())
                .contains("No tool ran, no gate was asked")
                .contains("could not be read as a JSON object");
    }
}
