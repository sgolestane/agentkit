package dev.agentkit.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.UnusableToolUseBlock;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.util.Frozen;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shared stand-in behaves like the providers it stands in for (#277).
 *
 * <h2>The failure mode this exists to make loud</h2>
 *
 * <p>Every shipped adapter goes through {@code ProposedCall.of}, which turns arguments this
 * framework will not carry into an {@code UnusableToolUseBlock} the model receives as a
 * refusal. A stand-in built on {@code new ToolUseBlock(...)} <strong>throws</strong> there
 * instead — out of the fake, through the loop's catch-all, and the run ends {@code ERROR}
 * naming nothing.
 *
 * <p>#269 found the first instance in the pentest suite's {@code PersuadedModel} and stated
 * the finding plainly: the stand-in went on throwing after every real adapter had stopped,
 * and {@code UnusualArgumentTest} stayed <em>green</em> against a fake that no longer
 * modelled anything. That is the shape — not a broken test, a passing one that has quietly
 * stopped testing the thing it names — and #277 found 47 more constructions across 24
 * files in seven modules.
 *
 * <p>So this test is deliberately not about {@code ProposedCall}, which
 * {@code ProposedCallTest} and {@code ToolUseBlockUnusableArgumentsTest} already pin. It is
 * about {@link FakeLlmClient}, the fake that backs most of this module's agent tests: revert
 * its {@code toolUse} factory to the direct constructor and both assertions below fail,
 * where before #277 nothing anywhere failed.
 */
class StandInModelsAProviderTest {

    /** A tree one level past the cap, which is what a real provider's turn can carry. */
    private static Map<String, Object> nested(int depth) {
        Map<String, Object> leaf = new HashMap<>();
        leaf.put("k", "v");
        Map<String, Object> current = leaf;
        for (int i = 1; i < depth; i++) {
            Map<String, Object> parent = new HashMap<>();
            parent.put("k", current);
            current = parent;
        }
        return current;
    }

    @Test
    @DisplayName("a turn the framework cannot carry comes back as a block, not a throw")
    void theFakeRefusesWhereItUsedToThrow() {
        assertThatCode(() -> FakeLlmClient.toolUse("t1", "publish",
                nested(Frozen.MAX_DEPTH + 1)))
                .as("the stand-in threw where every shipped adapter carries a refusal")
                .doesNotThrowAnyException();

        List<ContentBlock> blocks = FakeLlmClient
                .toolUse("t1", "publish", nested(Frozen.MAX_DEPTH + 1))
                .message().content();

        assertThat(blocks).singleElement().isInstanceOf(UnusableToolUseBlock.class);
    }

    @Test
    @DisplayName("the run finishes and the model is told, which is what a provider produces")
    void theLoopDeliversTheRefusalRatherThanEndingTheRun() {
        // The half a type assertion cannot reach: what the run does with it. Driven through
        // a real Agent because the defect #269 measured was end-to-end — 101 levels ended
        // the run ERROR with no gate consulted and no tool entered and nothing said.
        AtomicInteger entered = new AtomicInteger();
        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "publish", nested(Frozen.MAX_DEPTH + 1)),
                FakeLlmClient.text("I will send something flatter."));
        SimpleToolRegistry tools = new SimpleToolRegistry().register(
                FunctionTool.builder("publish", "publishes something")
                        .handler(invocation -> {
                            entered.incrementAndGet();
                            return ToolResult.ok("published");
                        })
                        .build());

        AgentResult result = new Agent(llm, tools,
                AgentConfig.builder("m").maxSteps(3).build())
                .run(Goal.of("publish something deep"));

        assertThat(result.isSuccess())
                .as("the refused arguments ended the run instead of being reported")
                .isTrue();
        assertThat(entered.get()).as("no tool may be entered for a refused call").isZero();
        assertThat(llm.received().get(1).messages().stream()
                .flatMap(message -> message.content().stream())
                .filter(ToolResultBlock.class::isInstance)
                .map(block -> ((ToolResultBlock) block).content())
                .toList())
                .as("the model was told nothing about the call the framework refused")
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("No tool ran, no gate was asked");
    }
}
