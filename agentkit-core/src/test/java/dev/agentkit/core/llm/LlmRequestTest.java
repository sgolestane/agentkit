package dev.agentkit.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.message.Message;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmRequestTest {

    @Test
    void requiresAtLeastOneMessage() {
        assertThatThrownBy(() -> LlmRequest.builder("m").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankModel() {
        assertThatThrownBy(() -> LlmRequest.builder("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveMaxTokens() {
        assertThatThrownBy(() -> LlmRequest.builder("m").maxTokens(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void messagesListIsCopiedAndImmutable() {
        List<Message> source = new ArrayList<>();
        source.add(Message.user("a"));
        LlmRequest request = LlmRequest.builder("m").messages(source).build();

        source.add(Message.user("b")); // must not leak in
        assertThat(request.messages()).hasSize(1);
        assertThatThrownBy(() -> request.messages().add(Message.user("c")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void systemDefaultsToEmptyOptional() {
        LlmRequest request = LlmRequest.builder("m").addMessage(Message.user("a")).build();
        assertThat(request.system()).isEmpty();
        assertThat(request.maxTokens()).isEqualTo(4096);
    }

    @Test
    void tokenUsagePlusSumsComponents() {
        TokenUsage sum = new TokenUsage(1, 2).plus(new TokenUsage(3, 4));
        assertThat(sum.inputTokens()).isEqualTo(4);
        assertThat(sum.outputTokens()).isEqualTo(6);
        assertThat(sum.totalTokens()).isEqualTo(10);
    }
}
