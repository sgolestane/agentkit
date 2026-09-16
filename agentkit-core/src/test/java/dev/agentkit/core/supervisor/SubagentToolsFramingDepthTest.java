package dev.agentkit.core.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A delegated subgoal that cannot fit the slot it is carried in is refused, not delivered
 * with its ask cut off (#207).
 *
 * <p>The framing every hop adds — this tool's routing sentence plus the whole
 * {@code Spotlight.INSTRUCTION} clause — is 2,039 characters and is not stripped on the way
 * in, so a supervisor restating the subgoal it was handed compounds it at 2,095 characters
 * a hop. The cut that bounds it takes the tail, and the tail is the request.
 */
class SubagentToolsFramingDepthTest {

    /** Every subgoal a delegation delivered, in order. */
    private final List<String> arrived = new ArrayList<>();

    private Tool delegateTo(String name) {
        arrived.clear();
        return SubagentTools.delegateTool(SubagentRoster.of(
                Subagent.handling(name, "does things", subgoal -> {
                    arrived.add(subgoal.description());
                    return AgentResult.completed("done", 1, TokenUsage.ZERO);
                })));
    }

    private static ToolResult delegate(Tool tool, String subagent, String goal) {
        return tool.execute(new ToolInvocation("i", "delegate",
                Map.of("subagent", subagent, "goal", goal)));
    }

    /** How much of the operator's own request survived into the subgoal that arrived. */
    private long requestCharsInLastSubgoal() {
        return arrived.get(arrived.size() - 1).chars().filter(c -> c == 'R').count();
    }

    /**
     * The label the subgoal is fenced under is the one the delegation decided against.
     *
     * <p>Not decoration: {@link SubagentTools} asks {@code Spotlight.fenceBounded} whether
     * the subgoal fits and then has {@code Spotlight.requestFrom} build the fence, and the
     * label does not affect what fits — it sits on the marker line rather than in the body.
     * So the two could drift apart without any assertion here noticing, which is why there
     * is one.
     */
    @Test
    void theSubgoalIsFencedAsComingFromTheSupervisor() {
        Tool tool = delegateTo("worker");

        delegate(tool, "worker", "summarise the incident report");

        assertThat(arrived.get(0)).contains("source=\"supervisor\"");
    }

    @Test
    void aSubgoalThatFitsIsDelegatedUnchanged() {
        Tool tool = delegateTo("worker");

        ToolResult result = delegate(tool, "worker", "summarise the incident report");

        assertThat(result.isError()).isFalse();
        assertThat(arrived).hasSize(1);
        assertThat(arrived.get(0)).contains("summarise the incident report");
    }

    @Test
    void theRequestArrivesWholeUntilTheHopThatCannotCarryIt() {
        Tool tool = delegateTo("worker");
        String request = "R".repeat(500);

        // Depth 1 and 2: a supervisor restating what it was handed still leaves room, and
        // every character of the operator's request arrives.
        assertThat(delegate(tool, "worker", request).isError()).isFalse();
        assertThat(requestCharsInLastSubgoal()).isEqualTo(500);
        String depthOne = arrived.get(arrived.size() - 1);

        assertThat(delegate(tool, "worker", depthOne).isError()).isFalse();
        assertThat(requestCharsInLastSubgoal()).isEqualTo(500);
        String depthTwo = arrived.get(arrived.size() - 1);
        int delivered = arrived.size();

        // Depth 3 is the hop where the slot can no longer carry the request: unfixed, this
        // delegation succeeded and the subgoal that arrived held 0 of the 500 characters.
        ToolResult refused = delegate(tool, "worker", depthTwo);

        assertThat(refused.isError()).isTrue();
        assertThat(refused.content())
                .contains("too long to delegate")
                .contains("Nothing was delegated and no subagent ran")
                .contains(String.valueOf(SubagentTools.DEFAULT_MAX_OUTPUT_CHARS));
        assertThat(arrived).as("no subagent ran for the refused delegation")
                .hasSize(delivered);
    }

    @Test
    void theRefusalSaysHowLongTheSubgoalWasAndWhatToSendInstead() {
        Tool tool = delegateTo("worker");
        String overLong = "R".repeat(SubagentTools.DEFAULT_MAX_OUTPUT_CHARS + 1);

        ToolResult refused = delegate(tool, "worker", overLong);

        assertThat(refused.isError()).isTrue();
        assertThat(refused.content())
                .contains("you sent " + overLong.length() + " characters")
                .contains("Send the request itself, briefly");
        assertThat(arrived).isEmpty();
    }

    /**
     * The slot is spent by the neutralised body, not by the argument's own length, so the
     * refusal has to be asked of the fence rather than of {@code String.length}.
     *
     * <p>U+FDFA is one UTF-16 unit that NFKC expands to eighteen characters, so 300 of them
     * are 300 characters going in and 5,400 filling a 4,000-character slot. Three hundred,
     * not three hundred thousand: the expansion is what is being tested, and a large payload
     * would buy nothing but build time.
     */
    @Test
    void aSubgoalThatOnlyOverflowsOnceNormalisedIsRefusedToo() {
        Tool tool = delegateTo("worker");
        String expanding = "ﷺ".repeat(300);

        assertThat(expanding.length()).isLessThan(SubagentTools.DEFAULT_MAX_OUTPUT_CHARS);

        ToolResult refused = delegate(tool, "worker", expanding);

        assertThat(refused.isError()).isTrue();
        assertThat(refused.content()).contains("too long to delegate");
        assertThat(arrived).isEmpty();
    }

    /**
     * A raised ceiling raises the refusal with it, because the bound is the slot the caller
     * chose rather than a number written here.
     */
    @Test
    void theCeilingTheCallerChoseIsTheOneEnforced() {
        arrived.clear();
        Tool generous = SubagentTools.delegateTool(SubagentRoster.of(
                Subagent.handling("worker", "does things", subgoal -> {
                    arrived.add(subgoal.description());
                    return AgentResult.completed("done", 1, TokenUsage.ZERO);
                })), SubagentTools.DEFAULT_MAX_OUTPUT_CHARS + 2_000);
        String overTheDefault = "R".repeat(SubagentTools.DEFAULT_MAX_OUTPUT_CHARS + 1);

        ToolResult result = delegate(generous, "worker", overTheDefault);

        assertThat(result.isError()).isFalse();
        assertThat(requestCharsInLastSubgoal())
                .isEqualTo(SubagentTools.DEFAULT_MAX_OUTPUT_CHARS + 1);
    }
}
