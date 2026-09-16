package dev.agentkit.core.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageTest {

    @Test
    void userFactoryBuildsSingleTextBlock() {
        Message m = Message.user("hello");

        assertThat(m.role()).isEqualTo(Role.USER);
        assertThat(m.content()).containsExactly(TextBlock.of("hello"));
        assertThat(m.text()).isEqualTo("hello");
    }

    @Test
    void textConcatenatesOnlyTextBlocks() {
        Message m = new Message(Role.ASSISTANT, List.of(
                TextBlock.of("part one"),
                new ToolUseBlock("t1", "search", Map.of("q", "x")),
                TextBlock.of("part two")));

        assertThat(m.text()).isEqualTo("part one\npart two");
    }

    @Test
    void contentIsImmutable() {
        Message m = Message.user("x");
        assertThatThrownBy(() -> m.content().add(TextBlock.of("y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyContentIsRejected() {
        assertThatThrownBy(() -> new Message(Role.USER, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolUseInputIsDefensivelyCopiedAndUnmodifiable() {
        var mutable = new java.util.HashMap<String, Object>();
        mutable.put("a", 1);
        ToolUseBlock block = new ToolUseBlock("id", "tool", mutable);

        mutable.put("b", 2); // must not leak into the block
        assertThat(block.input()).containsOnlyKeys("a");
        assertThatThrownBy(() -> block.input().put("c", 3))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toolUseInputIsCopiedDeeply() {
        // The mutant that survived: ToolUseBlock reverting to the old shallow copy passed
        // the whole reactor. It is the type the model's own call arrives as, and the one
        // the durable path re-reads on every replay, so "the outer map is copied" was the
        // only thing under test on the type where it matters most.
        var nested = new java.util.ArrayList<>(java.util.List.of("/tmp/harmless.txt"));
        ToolUseBlock block = new ToolUseBlock("id", "tool",
                java.util.Map.of("paths", nested));

        nested.set(0, "/etc/shadow");
        assertThat(block.input().get("paths"))
                .isEqualTo(java.util.List.of("/tmp/harmless.txt"));
    }

    @Test
    void toolUseInputPermitsNullValues() {
        var input = new java.util.HashMap<String, Object>();
        input.put("optional", null);
        ToolUseBlock block = new ToolUseBlock("id", "tool", input);

        assertThat(block.input()).containsKey("optional");
        assertThat(block.input().get("optional")).isNull();
    }

    @Test
    void textIsEmptyForToolOnlyMessage() {
        Message m = Message.of(Role.ASSISTANT,
                new ToolUseBlock("t1", "search", Map.of("q", "x")));
        assertThat(m.text()).isEmpty();
    }

    @Test
    void toolResultFactoriesSetErrorFlag() {
        assertThat(ToolResultBlock.ok("id", "done").isError()).isFalse();
        assertThat(ToolResultBlock.error("id", "boom").isError()).isTrue();
    }
}
