package dev.agentkit.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.tool.Disposition;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The rule that makes a view worth having: the person sees it and the model does not (#336).
 *
 * <h2>What the split buys</h2>
 *
 * <p>Before this, a tool with a lot to show had one channel and both audiences on it. Showing
 * a person four thousand rows meant spending four thousand rows of context on a model that
 * needed a sentence, so the honest thing to do was to show less. A view separates the two, and
 * the separation is only worth anything if it holds: a tool author decides to be generous here
 * on the strength of the model not paying for it.
 *
 * <h2>Asserted through the wire, not through the field</h2>
 *
 * <p>Reading {@code result.views()} back and finding it non-empty proves nothing about what the
 * model was told. So this drives a real loop and inspects the {@link LlmRequest} the client
 * actually received on the next turn — every content block of every message — for a canary that
 * exists only inside the view. A future change that folded views into the transcript "for
 * context" would pass a field check and fail this one.
 *
 * <p>The positive half is asserted in the same test rather than a separate one, because the two
 * are one property: the {@link AgentObserver} is handed the whole {@link ToolResult}, views
 * included, and that is the seam a console renders from. A view the model cannot see and a
 * client cannot reach either would satisfy the headline and be useless.
 */
class AViewNeverReachesTheModelTest {

    /** A string that exists nowhere but inside the view. */
    private static final String CANARY = "zqx-only-in-the-view";

    @Test
    void theModelGetsTheDigestAndTheObserverGetsTheView() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            rows.add(List.of(CANARY + '-' + i, i));
        }
        SimpleToolRegistry registry = new SimpleToolRegistry();
        registry.register(FunctionTool.builder("tickets.by_category",
                        "Open tickets grouped by category.")
                .schema(Map.of("type", "object", "properties", Map.of()))
                .readOnly()
                .handler(invocation -> ToolResult.ok("500 open tickets across 500 categories")
                        .withView(View.table(View.Column.texts("category", "open"), rows)))
                .build());

        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("t1", "tickets.by_category", Map.of()),
                FakeLlmClient.text("Access requests are the biggest group."));
        List<ToolResult> seenByObserver = new ArrayList<>();
        AgentObserver console = new AgentObserver() {
            @Override
            public void onToolResult(AgentRun run, int step, ToolInvocation proposed,
                    ToolInvocation effective, ToolResult result, Disposition disposition) {
                seenByObserver.add(result);
            }
        };

        AgentResult outcome = Agent.builder(llm, registry,
                        AgentConfig.builder("m").maxSteps(4).build())
                .observer(console)
                .name("views")
                .build()
                .run(Goal.of("What is the shape of the queue?"));

        assertThat(outcome.isSuccess()).isTrue();

        // The model was told the digest…
        List<ToolResultBlock> results = toolResultsIn(llm.received());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("500 open tickets across 500 categories");

        // …and nothing of the view, anywhere in the conversation it was sent.
        assertThat(everythingTheModelWasSent(llm.received())).doesNotContain(CANARY);

        // The console, meanwhile, has all five hundred rows.
        assertThat(seenByObserver).hasSize(1);
        assertThat(seenByObserver.get(0).views()).hasSize(1);
        assertThat((List<?>) seenByObserver.get(0).views().get(0).data().get("rows")).hasSize(500);
    }

    @Test
    void aViewSurvivesTheChangesAResultMakesToItself() {
        // asError and attributedTo both rebuild the record. A tool that returns a diff of what
        // it would have written and then marks the call failed — or any tool at all, since
        // every runner attributes every result — must not lose it on the way.
        ToolResult result = ToolResult.ok("would have written a comment")
                .withView(View.diff("comment", "before", "after"));

        assertThat(result.asError().views()).hasSize(1);
        assertThat(result.asError().isError()).isTrue();

        FunctionTool tool = FunctionTool.builder("write", "Writes.")
                .schema(Map.of("type", "object", "properties", Map.of()))
                .provenance(dev.agentkit.core.tool.Provenance.THIRD_PARTY)
                .handler(invocation -> result)
                .build();
        assertThat(result.attributedTo(tool).views()).hasSize(1);
        assertThat(result.attributedTo(tool).provenance())
                .isEqualTo(dev.agentkit.core.tool.Provenance.THIRD_PARTY);
    }

    @Test
    void aResultSaysItHasNoViewsRatherThanSayingNothing() {
        // Every factory predates views, and a null here would reach a client as an NPE at
        // render time rather than as an empty list at build time.
        assertThat(ToolResult.ok("x").views()).isEmpty();
        assertThat(ToolResult.error("x").views()).isEmpty();
        assertThat(ToolResult.refused("x").views()).isEmpty();
        assertThat(new ToolResult("x", false, null, null).views()).isEmpty();
    }

    @Test
    void aNullInsideAViewsListIsDroppedRatherThanEndingTheRun() {
        // List.copyOf was the first spelling and throws NPE on a null element. Nothing this
        // framework writes produces "views":[null], so reaching this means a malformed or
        // hand-edited payload — and on the durable path a throw in the canonical constructor
        // is not a failed call, it is a failed workflow task, which Temporal retries forever.
        // A dropped widget is the cheaper wrong answer, which is the side of the trade the
        // design principles put "worst case is a missing feature" on.
        List<View> withAHole = new ArrayList<>();
        withAHole.add(View.markdown("shown"));
        withAHole.add(null);

        ToolResult result = new ToolResult("x", false, null, withAHole);

        assertThat(result.views()).hasSize(1);
        assertThat(result.views().get(0).data()).containsEntry("text", "shown");
    }

    private static List<ToolResultBlock> toolResultsIn(List<LlmRequest> requests) {
        List<ToolResultBlock> found = new ArrayList<>();
        for (LlmRequest request : requests) {
            for (Message message : request.messages()) {
                for (ContentBlock block : message.content()) {
                    if (block instanceof ToolResultBlock result) {
                        found.add(result);
                    }
                }
            }
        }
        // The same block appears in every later request; one distinct result is what matters.
        return found.stream().distinct().toList();
    }

    /** Every character the model was sent, across every turn, as one string. */
    private static String everythingTheModelWasSent(List<LlmRequest> requests) {
        StringBuilder all = new StringBuilder();
        for (LlmRequest request : requests) {
            request.system().ifPresent(all::append);
            for (Message message : request.messages()) {
                for (ContentBlock block : message.content()) {
                    all.append(switch (block) {
                        case TextBlock text -> text.text();
                        case ToolResultBlock result -> result.content();
                        // Anything else is rendered by its record toString, which names every
                        // component — so a view smuggled into a block this test does not know
                        // about is still caught.
                        default -> String.valueOf(block);
                    });
                }
            }
        }
        return all.toString();
    }
}
