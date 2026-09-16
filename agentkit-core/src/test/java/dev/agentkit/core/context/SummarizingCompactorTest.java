package dev.agentkit.core.context;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.prompt.Spotlight;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SummarizingCompactorTest {

    private static SummarizingCompactor compactor(LlmClient llm, int trigger, int keep) {
        return SummarizingCompactor.builder(llm, "m")
                .triggerTokens(trigger).keepRecentMessages(keep).build();
    }

    @Test
    void belowTriggerLeavesHistoryUnchanged() {
        List<Message> history = List.of(Message.user("short"), Message.assistant("ok"));
        Compactor c = compactor(new FakeLlmClient(FakeLlmClient.text("SUMMARY")), 1_000_000, 2);
        assertThat(c.compact(history)).isSameAs(history);
    }

    @Test
    void aboveTriggerReplacesHeadWithSummary() {
        List<Message> history = List.of(
                Message.user("goal"), Message.assistant("a"),
                Message.user("b"), Message.assistant("c"), Message.user("d"));
        Compactor c = compactor(new FakeLlmClient(FakeLlmClient.text("SUMMARY")), 1, 2);

        List<Message> compacted = c.compact(history);

        assertThat(compacted).hasSize(4); // objective + summary + 2 recent
        assertThat(compacted.get(0)).isEqualTo(Message.user("goal")); // verbatim, unfenced
        assertThat(compacted.get(1).role()).isEqualTo(Role.USER);
        assertThat(compacted.get(1).text()).startsWith("[Summary").contains("SUMMARY");
        assertThat(compacted.get(2)).isEqualTo(Message.assistant("c"));
        assertThat(compacted.get(3)).isEqualTo(Message.user("d"));
    }

    @Test
    void boundaryNeverOrphansAToolResult() {
        List<Message> history = List.of(
                Message.user("goal"),
                Message.of(Role.ASSISTANT, new ToolUseBlock("t1", "tool", Map.of())),
                Message.of(Role.USER, ToolResultBlock.ok("t1", "result")),
                Message.assistant("continuing"),
                Message.user("e"),
                Message.user("f"));
        // keepRecent=4 would cut at the tool_result (index 2); the boundary must advance.
        Compactor c = compactor(new FakeLlmClient(FakeLlmClient.text("SUMMARY")), 1, 4);

        List<Message> compacted = c.compact(history);

        // First surviving non-summary message must not carry an orphaned tool_result.
        // Index 2 now: the objective is held out at 0 and the summary sits at 1.
        assertThat(compacted.get(2).content()).noneMatch(b -> b instanceof ToolResultBlock);
        assertThat(compacted.get(2)).isEqualTo(Message.assistant("continuing"));
    }

    @Test
    void overTriggerWithFewerMessagesThanWindowDoesNotCrash() {
        // A tiny-but-huge history (over trigger) with keepRecent(6) default: cut
        // would be negative — must clamp and return unchanged, not throw.
        List<Message> history = List.of(Message.user("goal"), Message.assistant("a"));
        // FakeLlmClient with no scripted responses: summarise() must not be called.
        Compactor c = SummarizingCompactor.builder(new FakeLlmClient(), "m")
                .triggerTokens(1).keepRecentMessages(6).build();
        assertThat(c.compact(history)).isSameAs(history);
    }

    @Test
    void systemPromptOverrideIsUsed() {
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("SUMMARY"));
        Compactor c = SummarizingCompactor.builder(llm, "m")
                .triggerTokens(1).keepRecentMessages(0).systemPrompt("CUSTOM PROMPT").build();
        c.compact(List.of(Message.user("goal"), Message.assistant("a"), Message.user("b")));
        assertThat(llm.received().get(0).system()).get(as(STRING))
                .startsWith("CUSTOM PROMPT").contains(Spotlight.INSTRUCTION);
    }

    @Test
    void theObjectiveIsKeptVerbatimRatherThanSummarisedIntoAFence() {
        // Message 0 is the operator's, and no later user message is — those carry tool
        // results.
        // every later user turn is a tool result. Summarised, it would come back fenced as
        // evidence, whose clause says to disregard directions found there and report them:
        // one compaction and the agent denounces its own goal as an injection attempt.
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("SUMMARY"));
        Message objective = Message.user("Email the quarterly report to finance.");
        List<Message> history = List.of(objective, Message.assistant("a"),
                Message.user("b"), Message.assistant("c"), Message.user("d"));

        List<Message> compacted = compactor(llm, 1, 2).compact(history);

        assertThat(compacted.get(0)).isSameAs(objective);
        assertThat(Spotlight.outsideFences(compacted.get(0).text()))
                .as("the objective must not arrive fenced")
                .isEqualTo(objective.text());
        // ... and the summariser was never shown it, so it cannot reappear inside the fence.
        assertThat(llm.received().get(0).messages().get(0).text())
                .doesNotContain("Email the quarterly report");
    }

    @Test
    void aHistoryNotStartingAtAnObjectiveIsSummarisedWholeRatherThanPinned() {
        // A ContextEditor running ahead of this one can trim the head, and a Compactor is a
        // public interface anyone may call. Pinning a message that carries a tool_result
        // would orphan it — an API error at the provider, which is worse than the weaker
        // prompt this avoids.
        FakeLlmClient llm = new FakeLlmClient(FakeLlmClient.text("SUMMARY"));
        List<Message> history = List.of(
                Message.of(Role.USER, ToolResultBlock.ok("t1", "orphan")),
                Message.assistant("a"), Message.user("b"),
                Message.assistant("c"), Message.user("d"));

        List<Message> compacted = compactor(llm, 1, 2).compact(history);

        assertThat(compacted.get(0).content()).noneMatch(b -> b instanceof ToolResultBlock);
        assertThat(compacted.get(0).text()).startsWith("[Summary");
        assertThat(compacted).hasSize(3); // summary + 2 recent; nothing pinned
    }

    @Test
    void repeatedCompactionReachesAFixedPointWithoutFurtherModelCalls() {
        // Holding the objective out means the result always has at least two messages, so a
        // history over the trigger because of the objective ITSELF never gets under it: the
        // compactor re-summarised its own summary on every call, one model call per
        // invocation forever. The guard requires two messages in the head, since replacing
        // one message with one summary is not progress. Only a repeated-compaction test can
        // see this — a single call looks fine either way.
        AtomicInteger calls = new AtomicInteger();
        LlmClient counting = request -> {
            calls.incrementAndGet();
            return FakeLlmClient.text("SUMMARY");
        };
        Compactor c = SummarizingCompactor.builder(counting, "m")
                .triggerTokens(10).keepRecentMessages(0).build();
        List<Message> history = List.of(Message.user("word ".repeat(400)), Message.assistant("a"));

        for (int round = 0; round < 4; round++) {
            history = c.compact(history);
        }

        assertThat(calls.get())
                .as("compaction kept calling the model without shrinking the history")
                .isZero(); // the head is one message, so there is nothing to summarise at all
        assertThat(history).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void summarisationFailureKeepsFullHistory() {
        LlmClient failing = request -> {
            throw new LlmException("summariser down");
        };
        List<Message> history = List.of(
                Message.user("goal"), Message.assistant("a"),
                Message.user("b"), Message.assistant("c"), Message.user("d"));
        assertThat(compactor(failing, 1, 2).compact(history)).isSameAs(history);
    }
}
