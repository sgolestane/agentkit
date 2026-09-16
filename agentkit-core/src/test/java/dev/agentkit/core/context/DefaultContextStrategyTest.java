package dev.agentkit.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentObserver;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.Provenance;
import dev.agentkit.core.tool.SimpleToolRegistry;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * An agent whose caller chose no context strategy still has a transcript bound (#151).
 *
 * <h2>The measurement</h2>
 *
 * <p>{@link BoundedFailureTextEditor} landed in #220 with its mechanism measured and tested,
 * and nothing wired it: it was reachable only through {@code Agent.Builder.contextStrategy},
 * so a default deployment ran with the pre-#220 behaviour. Counting characters a third party
 * wrote, through a real {@code Agent} with one tool that throws on every call:
 *
 * <pre>
 *          IDENTITY (the old default)      the new default
 * calls    in transcript   cumulative      in transcript   cumulative
 *     1            4,136        4,136              4,136        4,136
 *     8           33,088      148,896              8,272       62,040
 *    32          132,352    2,183,808              8,272      260,568
 *   256        1,058,816  136,057,856              4,136    2,113,496
 * </pre>
 *
 * <p>The transcript is re-sent every turn, so the unbounded cost is quadratic in the number
 * of failures. These tests run at 8 and 32 calls: enough for the bound to bite several
 * times, and small enough that the suite's logs stay in kilobytes. #220's own tests wedged
 * CI for 29 minutes by making a real {@code Agent} throw 200,000-character exceptions, and
 * the size of a message past {@code ToolResult}'s 4,000-character cap changes none of these
 * numbers — which is why the thrower here raises 4,100 characters and not 200,000.
 *
 * <h2>What each test is for</h2>
 *
 * <p>Three separate properties, because a repair can have any one without the others: the
 * default binds; the default is a no-op on an ordinary run, which is the whole argument for
 * defaulting a control on; and {@code ContextStrategy.IDENTITY} still means identity, which
 * is the part of #151's suggested shape that was deliberately not taken.
 */
class DefaultContextStrategyTest {

    /** Rendered size of one failure: the framework's frame, a fence, and a 4,000-char cut. */
    private static final int ONE_FAILURE_CHARS = 4_136;

    private static SimpleToolRegistry alwaysFails() {
        return new SimpleToolRegistry().register(
                FunctionTool.builder("flaky", "fails on every call")
                        .handler(invocation -> {
                            throw new IllegalStateException("x".repeat(4_100));
                        })
                        .build());
    }

    /** A script of {@code calls} tool calls followed by a final answer. */
    private static FakeLlmClient failing(int calls) {
        LlmResponse[] script = new LlmResponse[calls + 1];
        for (int i = 0; i < calls; i++) {
            script[i] = FakeLlmClient.toolUse("t" + i, "flaky", Map.of());
        }
        script[calls] = FakeLlmClient.text("giving up");
        return new FakeLlmClient(script);
    }

    /** Runs {@code calls} failing tool calls through {@code build}, returning the requests. */
    private static List<LlmRequest> transcript(int calls, Function<FakeLlmClient, Agent> build) {
        FakeLlmClient llm = failing(calls);
        build.apply(llm).run(Goal.of("go"));
        return llm.received();
    }

    private static AgentConfig config(int calls) {
        return AgentConfig.builder("m").maxSteps(calls + 2).build();
    }

    /** Characters in {@code messages} that somebody other than the framework wrote. */
    private static long thirdPartyChars(List<Message> messages) {
        long total = 0;
        for (Message message : messages) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolResultBlock result && result.isError()
                        && result.provenance() != Provenance.FIRST_PARTY
                        && !isPlaceholder(result.content())) {
                    total += result.content().length();
                }
            }
        }
        return total;
    }

    private static boolean isPlaceholder(String content) {
        return content.equals(BoundedFailureTextEditor.REPEATED_PLACEHOLDER)
                || content.equals(BoundedFailureTextEditor.OVER_BUDGET_PLACEHOLDER);
    }

    private static long cumulativeThirdPartyChars(List<LlmRequest> requests) {
        long total = 0;
        for (LlmRequest request : requests) {
            total += thirdPartyChars(request.messages());
        }
        return total;
    }

    @Test
    void anAgentWithNoStrategyChosenStillBoundsFailureText() {
        int calls = 8;

        List<LlmRequest> defaulted = transcript(calls,
                llm -> new Agent(llm, alwaysFails(), config(calls)));
        List<LlmRequest> unbounded = transcript(calls,
                llm -> new Agent(llm, alwaysFails(), config(calls), AgentObserver.NONE,
                        ContextStrategies.identity()));

        assertThat(thirdPartyChars(unbounded.get(unbounded.size() - 1).messages()))
                .as("the unbounded control stopped being unbounded, so this test compares"
                        + " nothing with nothing")
                .isGreaterThan(BoundedFailureTextEditor.DEFAULT_MAX_TOTAL_FAILURE_CHARS);
        assertThat(thirdPartyChars(defaulted.get(defaulted.size() - 1).messages()))
                .as("a default deployment had no transcript bound at all, which is exactly"
                        + " as live a defect as before PR #220")
                .isLessThanOrEqualTo(BoundedFailureTextEditor.DEFAULT_MAX_TOTAL_FAILURE_CHARS);
        assertThat(cumulativeThirdPartyChars(defaulted))
                .as("the transcript is re-sent every turn, so what an unbounded one costs is"
                        + " the sum over every request and not the size of the last")
                .isLessThan(cumulativeThirdPartyChars(unbounded) / 2);
    }

    @Test
    void theObserverConstructorIsDefaultedToo() {
        // The two convenience constructors and the builder are three doors to the same
        // default and a repair can easily fit only one of them. This is the door an
        // instrumented deployment uses.
        int calls = 8;

        List<LlmRequest> defaulted = transcript(calls,
                llm -> new Agent(llm, alwaysFails(), config(calls), AgentObserver.NONE));

        assertThat(thirdPartyChars(defaulted.get(defaulted.size() - 1).messages()))
                .isLessThanOrEqualTo(BoundedFailureTextEditor.DEFAULT_MAX_TOTAL_FAILURE_CHARS);
    }

    @Test
    void theBuilderIsDefaultedToo() {
        int calls = 8;

        List<LlmRequest> defaulted = transcript(calls,
                llm -> Agent.builder(llm, alwaysFails(), config(calls)).build());

        assertThat(thirdPartyChars(defaulted.get(defaulted.size() - 1).messages()))
                .isLessThanOrEqualTo(BoundedFailureTextEditor.DEFAULT_MAX_TOTAL_FAILURE_CHARS);
    }

    @Test
    void anOrdinaryRunIsSentByteForByteWhatItWouldHaveBeenSentBefore() {
        // The whole argument for defaulting a control on. Three failures is 12,408
        // characters, inside the 16,000 budget, and the editor returns the same list
        // instance rather than a rewritten one — so a caller who reads back what they sent
        // sees exactly what they would have seen with no strategy at all.
        //
        // It is also the test that fails for the tempting stronger repair: collapsing
        // repeats unconditionally, which #220 measured and rejected because it spends
        // legible error strings against a budget it is nowhere near.
        int calls = 3;

        List<LlmRequest> defaulted = transcript(calls,
                llm -> new Agent(llm, alwaysFails(), config(calls)));
        List<LlmRequest> unbounded = transcript(calls,
                llm -> new Agent(llm, alwaysFails(), config(calls), AgentObserver.NONE,
                        ContextStrategies.identity()));

        assertThat(thirdPartyChars(defaulted.get(defaulted.size() - 1).messages()))
                .as("three ordinary failures were rewritten by a default nobody asked for")
                .isEqualTo(3L * ONE_FAILURE_CHARS);
        assertThat(defaulted).hasSameSizeAs(unbounded);
        for (int i = 0; i < defaulted.size(); i++) {
            assertThat(defaulted.get(i).messages())
                    .as("request %d differed from what identity would have sent", i)
                    .isEqualTo(unbounded.get(i).messages());
        }
    }

    @Test
    void theBoundHoldsAsTheRunGetsLongerAndTheUnboundedOneDoesNot() {
        // The property, as against the single figure the tests above pin. Four times the
        // failures cost the unbounded transcript four times as much per request — and, since
        // it is re-sent every turn, sixteen times as much in total — while the bounded one
        // does not grow at all. A repair that raised the budget instead of applying it would
        // pass every other test in this file.
        List<LlmRequest> shortDefault = transcript(8,
                llm -> new Agent(llm, alwaysFails(), config(8)));
        List<LlmRequest> longDefault = transcript(32,
                llm -> new Agent(llm, alwaysFails(), config(32)));
        List<LlmRequest> shortIdentity = transcript(8,
                llm -> new Agent(llm, alwaysFails(), config(8), AgentObserver.NONE,
                        ContextStrategies.identity()));
        List<LlmRequest> longIdentity = transcript(32,
                llm -> new Agent(llm, alwaysFails(), config(32), AgentObserver.NONE,
                        ContextStrategies.identity()));

        assertThat(lastTranscript(longIdentity))
                .as("the unbounded control stopped growing with the run, so this test"
                        + " compares nothing with nothing")
                .isEqualTo(4 * lastTranscript(shortIdentity));
        assertThat(lastTranscript(longDefault))
                .as("the bound grew with the run, which is not a bound")
                .isEqualTo(lastTranscript(shortDefault));
        assertThat(lastTranscript(longDefault))
                .isLessThanOrEqualTo(BoundedFailureTextEditor.DEFAULT_MAX_TOTAL_FAILURE_CHARS);
    }

    private static long lastTranscript(List<LlmRequest> requests) {
        return thirdPartyChars(requests.get(requests.size() - 1).messages());
    }

    @Test
    void identityStillMeansIdentity() {
        // #151 proposed changing ContextStrategy.IDENTITY itself and noted that its name
        // and javadoc would then have to move. This is the record that the other shape was
        // taken: the constant is public API, a caller naming it is asking for the history
        // through unchanged, and it still is. It is also the escape hatch the argument for
        // defaulting leans on, so it has to keep working.
        List<Message> history = List.of(Message.user("one"), Message.user("two"));

        assertThat(ContextStrategy.IDENTITY.prepare(history)).isSameAs(history);
        assertThat(ContextStrategies.identity().prepare(history)).isSameAs(history);
    }

    @Test
    void theDefaultIsTheOneBothRunnersCanReach() {
        // #151's third point: a transcript bound is a property of an agent run and not of
        // the in-memory loop, so the default is named in agentkit-core where the durable
        // runner can reach it rather than being spelled inside Agent. Shared safely because
        // it holds one int and its prepare is a pure function of its argument — which is
        // what replay on the durable path will require of it.
        List<Message> history = List.of(Message.user("one"));

        assertThat(ContextStrategies.DEFAULT)
                .as("the default became a per-call allocation, so no runner can name it")
                .isSameAs(ContextStrategies.DEFAULT);
        assertThat(ContextStrategies.DEFAULT.prepare(history))
                .as("the default is not a no-op on a transcript with nothing to bound")
                .isEqualTo(history);
    }
}
