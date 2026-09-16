package dev.agentkit.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawMessageStopEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextDelta;
import com.anthropic.models.messages.ThinkingDelta;
import org.junit.jupiter.api.Test;

class AnthropicStreamingTest {

    private static RawMessageStreamEvent textDeltaEvent(String text) {
        return RawMessageStreamEvent.ofContentBlockDelta(RawContentBlockDeltaEvent.builder()
                .index(0)
                .delta(TextDelta.builder().text(text).build())
                .build());
    }

    @Test
    void textDeltaExtractsTheFragmentFromATextDeltaEvent() {
        assertThat(AnthropicLlmClient.textDelta(textDeltaEvent("Hello"))).contains("Hello");
    }

    @Test
    void textDeltaIgnoresNonTextContentDeltas() {
        RawMessageStreamEvent thinking = RawMessageStreamEvent.ofContentBlockDelta(
                RawContentBlockDeltaEvent.builder()
                        .index(0)
                        .delta(ThinkingDelta.builder().thinking("reasoning...").build())
                        .build());
        assertThat(AnthropicLlmClient.textDelta(thinking)).isEmpty();
    }

    @Test
    void textDeltaIgnoresNonDeltaEvents() {
        RawMessageStreamEvent stop = RawMessageStreamEvent.ofMessageStop(RawMessageStopEvent.builder().build());
        assertThat(AnthropicLlmClient.textDelta(stop)).isEmpty();
    }
}
