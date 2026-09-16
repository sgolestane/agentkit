package dev.agentkit.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.ImageBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.tool.SimpleToolRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * An operator's commonest attachment is a screenshot, and the model has to be able to see it.
 *
 * <h2>The failure this replaces</h2>
 *
 * <p>Before #374 an image reached the agent as <em>"there is a file called shot.png (image/png,
 * 240 kB)"</em> and nothing else — the file tools decode as text and refuse anything that is
 * not. So the agent answered about a filename while a person believed it was answering about
 * the picture, and nothing about the answer said which of the two had happened. That is worse
 * than not accepting attachments at all: a missing feature is visible, and this was not.
 */
class AnUploadedScreenshotReachesTheModelAsAnImageTest {

    /** A one-pixel PNG. Real bytes, so the media type is not the only thing being tested. */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private final ChatStore store = new InMemoryChatStore();
    private final ChatEvents events = new ChatEvents();
    private final List<LlmRequest> seen = new CopyOnWriteArrayList<>();

    private ChatRuntime runtime;

    @AfterEach
    void stop() {
        if (runtime != null) {
            runtime.close();
        }
    }

    private LlmClient recording() {
        return request -> {
            seen.add(request);
            return LlmResponse.of(Message.assistant("I can see it."), LlmStopReason.END_TURN,
                    TokenUsage.ZERO);
        };
    }

    private void start() {
        runtime = new ChatRuntime(store, events, session -> session
                .agent(recording(), new SimpleToolRegistry(),
                        AgentConfig.builder("m").maxSteps(2).build())
                .build());
    }

    /** Every block of every message the model was sent. */
    private List<ContentBlock> everythingSent() {
        List<ContentBlock> blocks = new ArrayList<>();
        for (LlmRequest request : seen) {
            for (Message message : request.messages()) {
                blocks.addAll(message.content());
            }
        }
        return blocks;
    }

    @Test
    void anImageUploadedWithTheMessageIsInTheFirstModelCall() throws Exception {
        start();
        Conversation conversation = store.create("acme", "");
        Attachment shot = store.attach("acme", conversation.id(), "shot.png", "image/png", PNG);

        Turn asked = runtime.say("acme", conversation.id(), "what is wrong here?",
                List.of(shot.id()));
        await(conversation.id(), asked.id());

        assertThat(everythingSent()).anySatisfy(block -> {
            assertThat(block).isInstanceOf(ImageBlock.class);
            ImageBlock image = (ImageBlock) block;
            assertThat(image.mediaType()).isEqualTo("image/png");
            assertThat(image.base64())
                    .isEqualTo(java.util.Base64.getEncoder().encodeToString(PNG));
        });
        // And the question is still there, and still first — a model handed a screenshot and
        // then asked what to do with it has read it as context for a question it had not
        // been asked yet.
        ContentBlock first = seen.getFirst().messages().getFirst().content().getFirst();
        assertThat(first).isInstanceOf(TextBlock.class);
        assertThat(((TextBlock) first).text()).contains("what is wrong here?");
    }

    @Test
    void aFileThatIsNotAnImageIsLeftAloneRatherThanMangled() throws Exception {
        start();
        Conversation conversation = store.create("acme", "");
        Attachment log = store.attach("acme", conversation.id(), "server.log", "text/plain",
                "connection refused".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Turn asked = runtime.say("acme", conversation.id(), "what happened?", List.of(log.id()));
        await(conversation.id(), asked.id());

        // Still an attachment, still downloadable, still reachable through the file tools —
        // this is only about the ones a vision model can look at.
        assertThat(everythingSent()).noneSatisfy(block ->
                assertThat(block).isInstanceOf(ImageBlock.class));
        assertThat(store.attachments("acme", conversation.id())).hasSize(1);
    }

    @Test
    void aTurnWithTooManyScreenshotsSaysWhatItLeftOut() throws Exception {
        start();
        Conversation conversation = store.create("acme", "");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            ids.add(store.attach("acme", conversation.id(), "shot" + i + ".png", "image/png",
                    PNG).id());
        }

        Turn asked = runtime.say("acme", conversation.id(), "look at these", ids);
        await(conversation.id(), asked.id());

        assertThat(everythingSent()).filteredOn(ImageBlock.class::isInstance).hasSize(4);
        // Said, not silently dropped. A console that showed the model half of what a person
        // handed it, without saying so, is one whose answers cannot be reasoned about.
        assertThat(everythingSent()).anySatisfy(block ->
                assertThat(block).isInstanceOfSatisfying(TextBlock.class, text ->
                        assertThat(text.text()).contains("3 further image(s)")));
    }

    @Test
    void anImageTooLargeForAContextWindowIsRefusedRatherThanCut() {
        // Half an image is not a smaller image. The bound is stated where the refusal is,
        // with what to do instead — the digest rule this repository applies to every other
        // tool result does not stop applying because the bytes are pixels.
        byte[] huge = new byte[ImageBlock.MAX_BYTES + 1];

        assertThatThrownBy(() -> ImageBlock.of("image/png", huge))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Half an image is not a smaller image");
    }

    @Test
    void aTypeNoProviderTakesIsRefusedHereRatherThanAtTheFarEnd() {
        // The media type comes from whoever uploaded the file. A type a provider rejects is
        // a request that fails at the far end with a message about somebody else's API,
        // rather than a refusal here with a sentence about this one.
        for (String type : List.of("image/bmp", "image/svg+xml", "application/pdf", "")) {
            assertThatThrownBy(() -> ImageBlock.of(type, PNG))
                    .as("%s", type)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ImageBlock.canBeSeen("IMAGE/PNG")).isTrue();
        assertThat(ImageBlock.canBeSeen(" image/jpeg ")).isTrue();
        assertThat(ImageBlock.canBeSeen("image/bmp")).isFalse();
        assertThat(ImageBlock.canBeSeen(null)).isFalse();
    }

    @Test
    void anUploadThatIsTooBigDoesNotTakeTheTurnDownWithIt() throws Exception {
        start();
        Conversation conversation = store.create("acme", "");
        // Declared an image and too large to show. The turn still answers.
        Attachment enormous = store.attach("acme", conversation.id(), "huge.png", "image/png",
                new byte[ImageBlock.MAX_BYTES + 1]);

        Turn asked = runtime.say("acme", conversation.id(), "look", List.of(enormous.id()));
        Turn ended = await(conversation.id(), asked.id());

        assertThat(ended.state()).isEqualTo(Turn.State.COMPLETED);
        assertThat(everythingSent()).noneSatisfy(block ->
                assertThat(block).isInstanceOf(ImageBlock.class));
        assertThat(everythingSent()).anySatisfy(block ->
                assertThat(block).isInstanceOfSatisfying(TextBlock.class, text ->
                        assertThat(text.text()).contains("further image(s)")));
    }

    @Test
    void aTurnWithNoAttachmentsSendsNothingExtra() throws Exception {
        start();
        Conversation conversation = store.create("acme", "");

        Turn asked = runtime.say("acme", conversation.id(), "just a question", List.of());
        await(conversation.id(), asked.id());

        assertThat(seen.getFirst().messages().getFirst().content()).hasSize(1);
    }

    @Test
    void askingToReadAnImageIsToldItIsLookingAtOneRatherThanThatItIsMissing() {
        // Two ways to read a file is one too many, so files.peek still refuses an image —
        // that rule did not change. What changed is what the refusal SAYS. "There is no
        // readable text file with that id" is true and, for an image, actively misleading:
        // the model has already been shown it, and a refusal that reads as "you cannot have
        // this" is how an answer comes back saying it could not see the screenshot it is
        // looking at.
        Conversation conversation = store.create("acme", "");
        Attachment shot = store.attach("acme", conversation.id(), "shot.png", "image/png", PNG);
        // Genuinely not decodable: a lead byte with no continuation. The first draft used
        // {0, 1, 2}, which is perfectly good UTF-8 — three control characters — so peek read
        // it and the assertion below was measuring nothing.
        Attachment binary = store.attach("acme", conversation.id(), "gone.bin",
                "application/octet-stream", new byte[] {(byte) 0xC3, (byte) 0x28});

        ChatRuntime.Session session = new ChatRuntime.Session("acme", conversation.id(),
                "turn-1", "look", List.of(shot.id()), store, events, null);
        List<dev.agentkit.core.tool.Tool> tools = Attachments.tools(store, session);
        dev.agentkit.core.tool.Tool peek = tools.stream()
                .filter(one -> one.name().equals("files.peek")).findFirst().orElseThrow();

        dev.agentkit.core.tool.ToolResult image = peek.execute(
                new dev.agentkit.core.tool.ToolInvocation("c1", "files.peek",
                        java.util.Map.of("id", shot.id())));
        assertThat(image.isError()).isTrue();
        assertThat(image.content()).contains("already been shown it");

        // And a file that is genuinely unreadable still says so, rather than everything
        // becoming "it is an image".
        dev.agentkit.core.tool.ToolResult other = peek.execute(
                new dev.agentkit.core.tool.ToolInvocation("c2", "files.peek",
                        java.util.Map.of("id", binary.id())));
        assertThat(other.isError()).isTrue();
        assertThat(other.content()).contains("no readable text file");
    }

    private Turn await(String conversationId, String turnId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Turn turn = store.turn("acme", conversationId, turnId).orElseThrow();
            if (turn.state().isTerminal()) {
                return turn;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the turn never ended");
    }
}
