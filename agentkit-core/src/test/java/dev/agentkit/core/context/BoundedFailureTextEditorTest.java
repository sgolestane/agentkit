package dev.agentkit.core.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.prompt.Spotlight;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #151: a per-fence bound on one failure detail is not a bound on what a run accumulates.
 *
 * <p>The oracle is deliberately <em>not</em> the length of the whole transcript. A dropped
 * failure still leaves a short framework-written block, so a test on the total would pass
 * against a change that merely reworded things. Every assertion here counts the characters a
 * <em>third party</em> contributed — the blocks that are not one of the two placeholders —
 * because that is the quantity #151 says nothing bounds.
 */
class BoundedFailureTextEditorTest {

    /**
     * A detail one character past what a fence will carry.
     *
     * <p>It was 200,000 and the size never mattered: {@code ToolResult.failed} cuts at
     * {@code MAX_FAILURE_CHARS = 4_000}, so anything past that renders to the same 4,136
     * characters and every number these tests assert is unchanged. What the size did change
     * was the log. A real {@code Agent} logs the throwable it caught, and until
     * {@code Quoted.failure} bounded a message these tests wrote 200,000 characters per
     * failed call into the operator's pipeline — invisible behind a local pipe and, in CI,
     * 25+ minutes of a run against a 3.5 minute norm. A test that is fine locally and
     * pathological in CI is a defect in the test.
     */
    private static final String HUGE = "X".repeat(4_100);

    // --- helpers ------------------------------------------------------------

    private static boolean placeholder(String content) {
        return content.equals(BoundedFailureTextEditor.REPEATED_PLACEHOLDER)
                || content.equals(BoundedFailureTextEditor.OVER_BUDGET_PLACEHOLDER);
    }

    /** Characters of failure text somebody outside the framework wrote. */
    private static long thirdPartyFailureChars(List<Message> history) {
        long total = 0;
        for (Message message : history) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolResultBlock r && r.isError() && !placeholder(r.content())) {
                    total += r.content().length();
                }
            }
        }
        return total;
    }

    private static List<ToolResultBlock> errors(List<Message> history) {
        List<ToolResultBlock> out = new ArrayList<>();
        for (Message message : history) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolResultBlock r && r.isError()) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    /** One failed tool result per message, in the shape the agent loop builds. */
    private static Message failure(String id, String detail) {
        return Message.of(Role.USER, new ToolResultBlock(id,
                ToolResult.failed("Tool 'flaky' failed.", dev.agentkit.core.prompt.Source.of("tool", "flaky"), detail).content(),
                true, Provenance.THIRD_PARTY));
    }

    /**
     * {@code n} failed calls in the shape the agent loop builds them, without an agent.
     *
     * <p>A real {@code Agent} is the right oracle for "does the wiring reach the editor" and
     * the wrong one for everything else: it costs a model turn and a logged throwable per
     * call, and re-sends the whole transcript each turn, so a test of the bound at N=256 was
     * quadratic in the one dimension the bound is supposed to make flat. Two tests below
     * still go end to end; the rest ask the editor directly.
     */
    private static List<Message> transcript(int n, java.util.function.IntFunction<String> detail) {
        List<Message> history = new ArrayList<>();
        history.add(Message.user("call flaky until it works"));
        for (int i = 0; i < n; i++) {
            history.add(Message.of(Role.ASSISTANT, new ToolUseBlock("t" + i, "flaky", Map.of())));
            history.add(failure("t" + i, detail.apply(i)));
        }
        return List.copyOf(history);
    }

    private static Tool alwaysFails(java.util.function.IntFunction<String> detail) {
        int[] call = {0};
        return FunctionTool.builder("flaky", "always fails")
                .handler(inv -> {
                    throw new IllegalStateException(detail.apply(call[0]++));
                })
                .build();
    }

    /** Runs {@code n} failing tool calls through a real agent loop and returns the last prompt. */
    private static List<Message> runOf(Tool tool, int n, ContextStrategy strategy) {
        LlmResponse[] script = new LlmResponse[n + 1];
        for (int i = 0; i < n; i++) {
            script[i] = FakeLlmClient.toolUse("t" + i, "flaky", Map.of());
        }
        script[n] = FakeLlmClient.text("giving up");
        FakeLlmClient llm = new FakeLlmClient(script);
        new Agent(llm, new SimpleToolRegistry().register(tool),
                AgentConfig.builder("m").maxSteps(n + 2).build(), AgentObserver.NONE, strategy)
                .run(Goal.of("call flaky until it works"));
        List<LlmRequest> received = llm.received();
        return received.get(received.size() - 1).messages();
    }

    // --- the defect ---------------------------------------------------------

    @Test
    void aRunOfDistinctFailuresIsBoundedInTotal() {
        // End to end through a real Agent, because the claim is about a run and not about a
        // list of messages. Sixteen calls rather than sixty-four: the budget bites at four,
        // and every call past that costs a model turn and a logged throwable for nothing.
        Tool tool = alwaysFails(i -> "attempt " + i + ": " + HUGE);

        // Unedited, which is the state #151 reports: 16 calls at the per-fence cap.
        long unedited = thirdPartyFailureChars(runOf(tool, 16, ContextStrategy.IDENTITY));
        assertThat(unedited).isGreaterThan(60_000);

        long edited = thirdPartyFailureChars(runOf(alwaysFails(i -> "attempt " + i + ": " + HUGE),
                16, ContextStrategies.editing(new BoundedFailureTextEditor())));
        assertThat(edited)
                .isLessThanOrEqualTo(BoundedFailureTextEditor.DEFAULT_MAX_TOTAL_FAILURE_CHARS);
    }

    @Test
    void theBoundDoesNotMoveWithTheNumberOfFailures() {
        // The shape of the defect, not just one point on it: 4x the calls used to be 4x the
        // text. A bound that grew with N would pass a single-N assertion.
        for (int n : new int[] {16, 64, 256}) {
            long edited = thirdPartyFailureChars(new BoundedFailureTextEditor()
                    .edit(transcript(n, i -> "attempt " + i + ": " + HUGE)));
            assertThat(edited)
                    .as("N=%d", n)
                    .isLessThanOrEqualTo(BoundedFailureTextEditor.DEFAULT_MAX_TOTAL_FAILURE_CHARS);
        }
    }

    @Test
    void anIdenticalFailureIsCarriedOnceHoweverOftenItIsRepeated() {
        List<Message> edited = new BoundedFailureTextEditor().edit(transcript(64, i -> HUGE));

        List<ToolResultBlock> errors = errors(edited);
        assertThat(errors).hasSize(64);
        assertThat(errors.stream().filter(r -> !placeholder(r.content()))).hasSize(1);
        assertThat(errors.stream()
                .filter(r -> r.content().equals(BoundedFailureTextEditor.REPEATED_PLACEHOLDER)))
                .hasSize(63);
    }

    // --- which end goes, and how it is said ---------------------------------

    @Test
    void theNewestFailureSurvivesAndTheOldestIsTheOneDropped() {
        String big = "Y".repeat(10_000);
        List<Message> history = List.of(
                Message.user("goal"),
                failure("t1", "oldest: " + big),
                failure("t2", "middle: " + big),
                failure("t3", "newest: " + big));

        List<Message> edited = new BoundedFailureTextEditor(6_000).edit(history);

        List<ToolResultBlock> errors = errors(edited);
        assertThat(errors.get(0).content())
                .isEqualTo(BoundedFailureTextEditor.OVER_BUDGET_PLACEHOLDER);
        assertThat(errors.get(1).content())
                .isEqualTo(BoundedFailureTextEditor.OVER_BUDGET_PLACEHOLDER);
        assertThat(errors.get(2).content()).contains("newest");
    }

    @Test
    void everyDropIsAnnouncedOutsideAnyFence() {
        List<Message> edited = new BoundedFailureTextEditor()
                .edit(transcript(32, i -> "attempt " + i + ": " + HUGE));

        for (ToolResultBlock r : errors(edited)) {
            if (!placeholder(r.content())) {
                continue;
            }
            // The whole block is the announcement, so it is outside every fence by
            // construction — and it must not itself look like one.
            assertThat(Spotlight.outsideFences(r.content())).isEqualTo(r.content());
            assertThat(r.content()).contains("earlier");
        }
        assertThat(errors(edited)).anySatisfy(r -> assertThat(placeholder(r.content())).isTrue());
    }

    @Test
    void aRepeatIsOnlyCalledARepeatWhileTheKeptCopyIsStillThere() {
        // Three identical failures, and a budget too small for even one. Nothing is "still
        // further down", so all three take the over-budget wording rather than the repeat one.
        List<Message> history = List.of(
                failure("t1", HUGE), failure("t2", HUGE), failure("t3", HUGE));

        List<ToolResultBlock> errors = errors(new BoundedFailureTextEditor(100).edit(history));

        assertThat(errors).allSatisfy(r -> assertThat(r.content())
                .isEqualTo(BoundedFailureTextEditor.OVER_BUDGET_PLACEHOLDER));
    }

    @Test
    void everyToolUseStillHasAToolResult() {
        // The other end-to-end test: only a real Agent produces the tool_use blocks this
        // pairing is about. Eight calls is twice what the budget carries, which is all the
        // claim needs.
        List<Message> edited = runOf(alwaysFails(i -> "attempt " + i + ": " + HUGE), 8,
                ContextStrategies.editing(new BoundedFailureTextEditor()));

        List<String> uses = new ArrayList<>();
        List<String> results = new ArrayList<>();
        for (Message message : edited) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolUseBlock u) {
                    uses.add(u.id());
                } else if (block instanceof ToolResultBlock r) {
                    results.add(r.toolUseId());
                }
            }
        }
        assertThat(uses).hasSize(8);
        assertThat(results).containsExactlyInAnyOrderElementsOf(uses);
    }

    @Test
    void aDroppedBlockKeepsItsIdItsErrorFlagAndItsProvenance() {
        List<Message> history = List.of(failure("keep-id", HUGE), failure("newer", HUGE));

        ToolResultBlock dropped = errors(new BoundedFailureTextEditor(5_000).edit(history)).get(0);

        assertThat(dropped.toolUseId()).isEqualTo("keep-id");
        assertThat(dropped.isError()).isTrue();
        assertThat(dropped.provenance()).isEqualTo(Provenance.THIRD_PARTY);
    }

    // --- what it must not do ------------------------------------------------

    @Test
    void anInBudgetTranscriptIsReturnedUntouched() {
        // Twenty repetitions of a real error string: well inside the budget, and the
        // unconditional collapse this class measured and rejected would have rewritten
        // nineteen of them.
        List<Message> history = new ArrayList<>();
        history.add(Message.user("goal"));
        for (int i = 0; i < 20; i++) {
            history.add(failure("t" + i, "Connection refused: api.example.com:443"));
        }
        List<Message> frozen = List.copyOf(history);

        assertThat(new BoundedFailureTextEditor().edit(frozen)).isSameAs(frozen);
    }

    @Test
    void successfulResultsAreNotFailureText() {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            history.add(Message.of(Role.USER, ToolResultBlock.ok("t" + i, HUGE)));
        }
        List<Message> frozen = List.copyOf(history);

        assertThat(new BoundedFailureTextEditor(100).edit(frozen)).isSameAs(frozen);
    }

    @Test
    void aLargeSuccessfulResultSurvivesAPassTheFailuresTriggered() {
        // The walk has to keep filtering on isError even once it is running, and only a
        // transcript that is over budget on its failures and carrying a big success at the
        // same time can show it. Otherwise a tool's actual answer comes back to the model
        // reading "an earlier failure was dropped", which is a loss and a lie at once.
        List<Message> history = new ArrayList<>();
        history.add(Message.of(Role.USER, ToolResultBlock.ok("good", "W".repeat(50_000))));
        for (int i = 0; i < 10; i++) {
            history.add(failure("t" + i, "attempt " + i + ": " + HUGE));
        }

        List<Message> edited = new BoundedFailureTextEditor().edit(history);

        ToolResultBlock success = (ToolResultBlock) edited.get(0).content().get(0);
        assertThat(success.isError()).isFalse();
        assertThat(success.content()).hasSize(50_000);
        assertThat(thirdPartyFailureChars(edited))
                .isLessThanOrEqualTo(BoundedFailureTextEditor.DEFAULT_MAX_TOTAL_FAILURE_CHARS);
    }

    @Test
    void successfulResultsDoNotSpendTheFailureBudget() {
        // The budget is on failure text. A transcript full of legitimate tool output and two
        // small identical failures is inside it, so nothing happens -- and if successes were
        // charged, the pass they triggered would collapse those two, which is the rewrite
        // this class measured and rejected.
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            history.add(Message.of(Role.USER, ToolResultBlock.ok("g" + i, "W".repeat(10_000))));
        }
        history.add(failure("t1", "Connection refused: api.example.com:443"));
        history.add(failure("t2", "Connection refused: api.example.com:443"));
        List<Message> frozen = List.copyOf(history);

        assertThat(new BoundedFailureTextEditor().edit(frozen)).isSameAs(frozen);
    }

    @Test
    void editingIsIdempotent() {
        BoundedFailureTextEditor editor = new BoundedFailureTextEditor();
        List<Message> raw = transcript(64, i -> "attempt " + i + ": " + HUGE);

        List<Message> once = editor.edit(raw);
        List<Message> twice = editor.edit(once);

        // Same list back, not merely an equal one: the agent loop persists the output and
        // runs the editor again every turn, so a second pass that rebuilt anything would be
        // a second log line and a second chance to drop something.
        assertThat(twice).isSameAs(once);
        assertThat(thirdPartyFailureChars(once))
                .isLessThanOrEqualTo(BoundedFailureTextEditor.DEFAULT_MAX_TOTAL_FAILURE_CHARS);
    }

    @Test
    void aBlockThatIsAlreadyAPlaceholderIsNeitherChargedNorReplaced() {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            history.add(Message.of(Role.USER, new ToolResultBlock("t" + i,
                    BoundedFailureTextEditor.OVER_BUDGET_PLACEHOLDER, true, Provenance.THIRD_PARTY)));
        }
        List<Message> frozen = List.copyOf(history);

        assertThat(new BoundedFailureTextEditor(100).edit(frozen)).isSameAs(frozen);
    }

    @Test
    void aBudgetOfExactlyOneFailureCarriesThatFailure() {
        // The off-by-one at the boundary, which is where a bound is either a ceiling or one
        // character short of one.
        // Two failures of equal rendered length, and a budget of exactly one of them: the
        // newest fits exactly and must be carried, the older must not.
        String newest = ToolResult.failed("Tool 'flaky' failed.", dev.agentkit.core.prompt.Source.of("tool", "flaky"), "boom-b").content();
        List<Message> history = List.of(failure("t1", "boom-a"), failure("t2", "boom-b"));

        List<ToolResultBlock> errors =
                errors(new BoundedFailureTextEditor(newest.length()).edit(history));

        assertThat(errors.get(0).content())
                .isEqualTo(BoundedFailureTextEditor.OVER_BUDGET_PLACEHOLDER);
        assertThat(errors.get(1).content()).isEqualTo(newest);
    }

    @Test
    void withinOneMessageTheLastBlockIsTheOneKept() {
        // The results of a parallel tool call are simultaneous, so this order is a tie-break
        // chosen for determinism rather than a claim about recency. Pinned because it decides
        // which evidence survives, and a silent flip would be invisible.
        String big = "Z".repeat(10_000);
        String first = ToolResult.failed("Tool 'flaky' failed.", dev.agentkit.core.prompt.Source.of("tool", "flaky"), "first: " + big)
                .content();
        String second = ToolResult.failed("Tool 'flaky' failed.", dev.agentkit.core.prompt.Source.of("tool", "flaky"), "second: " + big)
                .content();
        List<Message> history = List.of(Message.of(Role.USER, List.of(
                new ToolResultBlock("a", first, true, Provenance.THIRD_PARTY),
                new ToolResultBlock("b", second, true, Provenance.THIRD_PARTY))));

        List<ToolResultBlock> errors = errors(new BoundedFailureTextEditor(6_000).edit(history));

        assertThat(errors.get(0).content())
                .isEqualTo(BoundedFailureTextEditor.OVER_BUDGET_PLACEHOLDER);
        assertThat(errors.get(1).content()).isEqualTo(second);
    }

    @Test
    void stubsFromAnEarlierPassDoNotPushALaterPassIntoCollapsingRepeats() {
        // If the placeholders an earlier pass left were charged to the budget, a transcript
        // whose real failure text is well inside it would still be rewritten -- and the
        // rewrite would be the unconditional repeat-collapse this class measured and
        // rejected. So they are not charged, and this is the shape that proves it.
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            history.add(Message.of(Role.USER, new ToolResultBlock("old" + i,
                    BoundedFailureTextEditor.OVER_BUDGET_PLACEHOLDER, true,
                    Provenance.THIRD_PARTY)));
        }
        for (int i = 0; i < 5; i++) {
            history.add(failure("t" + i, "Connection refused: api.example.com:443"));
        }
        List<Message> frozen = List.copyOf(history);

        assertThat(new BoundedFailureTextEditor().edit(frozen)).isSameAs(frozen);
    }

    @Test
    void aTranscriptSittingExactlyOnTheBudgetIsStillInsideIt() {
        // The guard is "more than the budget", not "as much as it". A transcript that fills
        // the budget exactly is within it, and the difference is visible rather than
        // academic: these two are identical, so the pass this would let run would collapse
        // one of them.
        String rendered = ToolResult.failed("Tool 'flaky' failed.", dev.agentkit.core.prompt.Source.of("tool", "flaky"), "boom").content();
        List<Message> frozen = List.of(failure("t1", "boom"), failure("t2", "boom"));

        assertThat(new BoundedFailureTextEditor(2 * rendered.length()).edit(frozen))
                .isSameAs(frozen);
    }

    @Test
    void anEarlierPassesStubsAreNotRewrittenByALaterPassThatDoesRun() {
        // The other half of the exemption. Above, the stubs keep a later pass from running
        // at all; here the pass runs anyway because the real failure text is over budget,
        // and the stubs must still come through untouched -- rewriting an over-budget drop
        // as a repeat would tell the model the text is "still further down" when it is gone.
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            history.add(Message.of(Role.USER, new ToolResultBlock("old" + i,
                    BoundedFailureTextEditor.OVER_BUDGET_PLACEHOLDER, true,
                    Provenance.THIRD_PARTY)));
        }
        for (int i = 0; i < 10; i++) {
            history.add(failure("t" + i, "attempt " + i + ": " + HUGE));
        }

        List<Message> edited = new BoundedFailureTextEditor().edit(history);

        List<ToolResultBlock> stubs = errors(edited).subList(0, 50);
        assertThat(stubs).allSatisfy(r -> assertThat(r.content())
                .isEqualTo(BoundedFailureTextEditor.OVER_BUDGET_PLACEHOLDER));
        assertThat(thirdPartyFailureChars(edited))
                .isLessThanOrEqualTo(BoundedFailureTextEditor.DEFAULT_MAX_TOTAL_FAILURE_CHARS);
        assertThat(new BoundedFailureTextEditor().edit(edited)).isSameAs(edited);
    }

    @Test
    void aNonPositiveBudgetIsRefusedWhereItIsBuilt() {
        assertThatThrownBy(() -> new BoundedFailureTextEditor(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTotalFailureChars");
    }

    @Test
    void itComposesAsAContextEditor() {
        ContextEditor editor = new BoundedFailureTextEditor(5_000);
        assertThat(editor.edit(List.of(Message.user("nothing to do")))).hasSize(1);
        assertThat(new BoundedFailureTextEditor(7).maxTotalFailureChars()).isEqualTo(7);
    }

    @Test
    void itComposesWithTheOtherShippedEditor() {
        List<Message> history = new ArrayList<>();
        history.add(Message.user("goal"));
        for (int i = 0; i < 10; i++) {
            history.add(failure("t" + i, "attempt " + i + ": " + HUGE));
        }
        history.add(Message.of(Role.USER, ToolResultBlock.ok("good", "W".repeat(40_000))));

        List<Message> edited = new BoundedFailureTextEditor()
                .andThen(new ClearToolResultsEditor(2))
                .edit(history);

        // The failure budget held, and the second editor still cleared the old success.
        assertThat(thirdPartyFailureChars(edited))
                .isLessThanOrEqualTo(BoundedFailureTextEditor.DEFAULT_MAX_TOTAL_FAILURE_CHARS);
        assertThat(edited).hasSameSizeAs(history);
    }
}
