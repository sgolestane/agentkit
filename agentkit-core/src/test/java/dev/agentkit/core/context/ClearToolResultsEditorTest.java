package dev.agentkit.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ToolResultBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClearToolResultsEditorTest {

    private static Message toolResult(String id, String content) {
        return Message.of(Role.USER, ToolResultBlock.ok(id, content));
    }

    @Test
    void clearsOldToolResultsButKeepsRecentWindow() {
        List<Message> history = List.of(
                Message.user("goal"),
                toolResult("t1", "a very large old tool result ".repeat(5)),
                Message.assistant("thinking about it"),
                toolResult("t2", "a very large recent tool result ".repeat(5)));

        List<Message> edited = new ClearToolResultsEditor(2).edit(history);

        // Oldest tool result (index 1) is cleared; recent one (index 3) preserved.
        assertThat(((ToolResultBlock) edited.get(1).content().get(0)).content())
                .isEqualTo(ClearToolResultsEditor.DEFAULT_PLACEHOLDER);
        assertThat(((ToolResultBlock) edited.get(3).content().get(0)).content())
                .contains("recent tool result");
    }

    @Test
    void preservesToolUseId() {
        List<Message> history = List.of(
                toolResult("keep-id", "old ".repeat(50)),
                Message.user("recent"));
        List<Message> edited = new ClearToolResultsEditor(1).edit(history);
        assertThat(((ToolResultBlock) edited.get(0).content().get(0)).toolUseId()).isEqualTo("keep-id");
    }

    @Test
    void nonToolBlocksUntouched() {
        List<Message> history = List.of(Message.of(Role.ASSISTANT, TextBlock.of("hello")), Message.user("x"));
        List<Message> edited = new ClearToolResultsEditor(0).edit(history);
        assertThat(edited.get(0).text()).isEqualTo("hello");
    }

    @Test
    void keepingAllLeavesHistoryUnchanged() {
        List<Message> history = List.of(toolResult("t1", "big ".repeat(50)));
        assertThat(new ClearToolResultsEditor(5).edit(history)).isSameAs(history);
    }

    @Test
    void shortResultsNotCleared() {
        List<Message> history = List.of(toolResult("t1", "ok"), Message.user("x"), Message.user("y"));
        List<Message> edited = new ClearToolResultsEditor(0).edit(history);
        assertThat(((ToolResultBlock) edited.get(0).content().get(0)).content()).isEqualTo("ok");
    }
}
