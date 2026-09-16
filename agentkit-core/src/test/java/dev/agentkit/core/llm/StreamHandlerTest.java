package dev.agentkit.core.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamHandlerTest {

    private static final LlmRequest REQUEST = LlmRequest.builder("m").addMessage(Message.user("hi")).build();

    @Test
    void defaultStreamingEmitsTheFinalTextAsASingleDelta() {
        LlmClient blocking = request -> FakeLlmClient.text("hello world");
        List<String> deltas = new ArrayList<>();

        LlmResponse response = blocking.generate(REQUEST, deltas::add);

        assertThat(deltas).containsExactly("hello world");
        assertThat(response.message().text()).isEqualTo("hello world");
    }

    @Test
    void defaultStreamingEmitsNothingForEmptyText() {
        LlmClient blocking = request -> LlmResponse.of(
                Message.of(Role.ASSISTANT, TextBlock.of("")), LlmStopReason.END_TURN, TokenUsage.ZERO);
        List<String> deltas = new ArrayList<>();

        blocking.generate(REQUEST, deltas::add);

        assertThat(deltas).isEmpty();
    }

    @Test
    void defaultStreamingRejectsANullHandler() {
        LlmClient blocking = request -> FakeLlmClient.text("x");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> blocking.generate(REQUEST, null))
                .isInstanceOf(NullPointerException.class);
    }
}
