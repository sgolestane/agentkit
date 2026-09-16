package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.DisclosingToolRegistry;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.workbench.domain.Risk;
import dev.agentkit.workbench.tools.ToolPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which console tools ask a person first, and which of them ask only after the model has read
 * somebody else's words.
 *
 * <h2>Why the console needs a gate of its own at all</h2>
 *
 * <p>The run tier is supervised: a comment the agent proposes parks for approval. The console tier
 * is not the run tier. {@code alm.comment} writes to Jira immediately, as the operator, with
 * their name on it — an ungated console is one where a model that misread the conversation
 * signs a comment on somebody's ticket. So the console gates by the same {@link ToolPolicy}
 * grading the supervisor uses, and the person sees the call before it happens.
 *
 * <h2>Two policies, because reading changes what is safe</h2>
 *
 * <p>Ordinarily a rehearsal is free: {@code workbench.preview} changes nothing outside this
 * process and answering "what would you do" should not need a click. Once the run has taken in
 * a ticket description — text somebody outside this company wrote — the trust floor lowers and
 * the rehearsal asks too, because "please preview all of my tickets" in a description is a
 * small harm rather than none, and it is somebody else's sentence spending the model budget.
 */
class WhatStopsForAPersonTest {

    @Test
    void theOperatorsOwnHandsAlwaysAsk() {
        // A comment is graded LOW — it is barely a change — and it still asks, because it
        // cannot be unsaid and it goes out under the operator's name. Risk alone would have
        // let it through; irreversibility is the half that catches it.
        assertThat(ConsoleTools.policyOrUnknown("alm.comment").baselineRisk())
                .isEqualTo(Risk.LOW);
        assertThat(WorkbenchChatApp.asksOrdinarily("alm.comment")).isTrue();
        assertThat(WorkbenchChatApp.asksOrdinarily("alm.transition")).isTrue();
    }

    @Test
    void doingSomethingForRealAlwaysAsksAndRehearsingItDoesNot() {
        assertThat(WorkbenchChatApp.asksOrdinarily("workbench.execute")).isTrue();
        assertThat(WorkbenchChatApp.asksOrdinarily("workbench.bulk_execute")).isTrue();
        // The one that makes the console usable. "What would the agent do with IT-421" is the
        // question this product is for, and a click in front of it is a click in front of
        // everything.
        assertThat(WorkbenchChatApp.asksOrdinarily("workbench.preview")).isFalse();
        assertThat(WorkbenchChatApp.asksOrdinarily("triage.ticket")).isFalse();
    }

    @Test
    void decidingOnSomebodyElsesBehalfAlwaysAsks() {
        // Deliberately double-confirmed: the operator says "approve it", the model calls
        // approvals.decide, and the card shows which approval and which way before the run
        // resumes. Two presses for one consequential decision is the right trade — the
        // alternative is a model that can settle a parked run from a sentence it misread.
        assertThat(WorkbenchChatApp.asksOrdinarily("approvals.decide")).isTrue();
        assertThat(WorkbenchChatApp.asksOrdinarily("approvals.answer")).isTrue();
        assertThat(WorkbenchChatApp.asksOrdinarily("rules.automate")).isTrue();
        assertThat(WorkbenchChatApp.asksOrdinarily("rules.toggle")).isTrue();
        assertThat(WorkbenchChatApp.asksOrdinarily("decisions.lift")).isTrue();
    }

    @Test
    void readingNeverAsks_EvenAfterTheFloorHasDropped() {
        for (ToolPolicy policy : ConsoleTools.policies()) {
            if (policy.baselineRisk() != Risk.READ) {
                continue;
            }
            assertThat(WorkbenchChatApp.asksOrdinarily(policy.name()))
                    .as("%s only reads", policy.name()).isFalse();
            assertThat(WorkbenchChatApp.asksOnceRead(policy.name()))
                    .as("%s only reads", policy.name()).isFalse();
        }
    }

    @Test
    void onceItHasReadSomebodyElsesWordsEvenARehearsalAsks() {
        assertThat(WorkbenchChatApp.asksOnceRead("workbench.preview")).isTrue();
        assertThat(WorkbenchChatApp.asksOnceRead("triage.sweep")).isTrue();
        assertThat(WorkbenchChatApp.asksOnceRead("evals.capture")).isTrue();
        assertThat(WorkbenchChatApp.asksOnceRead("decisions.revoke")).isTrue();
        // And nothing that asked before stops asking: the floor only tightens.
        for (ToolPolicy policy : ConsoleTools.policies()) {
            if (WorkbenchChatApp.asksOrdinarily(policy.name())) {
                assertThat(WorkbenchChatApp.asksOnceRead(policy.name()))
                        .as("%s must not become free once the floor has dropped", policy.name())
                        .isTrue();
            }
        }
    }

    @Test
    void aToolNobodyGradedAsksRatherThanRuns() {
        // The doctrine ToolCatalog states for the run tier, held to on this one. A tool added
        // here without a policy is a tool nobody decided about, and the console's answer to
        // that is a person, not a shrug.
        assertThat(ConsoleTools.policyOrUnknown("something.new").baselineRisk())
                .isEqualTo(Risk.HIGH);
        assertThat(WorkbenchChatApp.asksOrdinarily("something.new")).isTrue();
        assertThat(WorkbenchChatApp.asksOnceRead("something.new")).isTrue();
    }

    @Test
    void theTwoConsoleToolsAreExemptAndTheyAreTheOnlyOnes() {
        // ask_person is how the agent gets a person's judgement; gating it would mean asking
        // permission to ask. search_tools is how it reaches anything deferred; gating it would
        // put a click in front of twenty-one of the twenty-six tools.
        assertThat(WorkbenchChatApp.asksOrdinarily("ask_person")).isFalse();
        assertThat(WorkbenchChatApp.asksOnceRead("ask_person")).isFalse();
        assertThat(WorkbenchChatApp.asksOnceRead(
                DisclosingToolRegistry.DEFAULT_SEARCH_TOOL_NAME)).isFalse();

        // And they are exempt by name, so a workbench tool cannot inherit the exemption by
        // being ungraded — that case is the previous test, and it asks.
        List<String> exempt = ConsoleTools.policies().stream()
                .map(ToolPolicy::name)
                .filter(name -> !WorkbenchChatApp.asksOnceRead(name))
                .toList();
        assertThat(exempt).allSatisfy(name ->
                assertThat(ConsoleTools.policyOrUnknown(name).baselineRisk())
                        .isEqualTo(Risk.READ));
    }

    @Test
    void askPersonIsThereFromTheFirstTurnRatherThanBehindASearch() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));
        DisclosingToolRegistry registry = ConsoleTools
                .registryBuilder(Console.DEPLOYMENT, console.alm, console.workbench, null,
                        console.learnings, console.store, null)
                .alwaysAvailable(new Ask())
                .build();

        // A model that has to search for how to ask a question answers instead of asking,
        // which is the failure the whole human-in-the-loop story is built to avoid.
        assertThat(registry.advertisedSpecs().stream()
                .map(dev.agentkit.core.tool.ToolSpec::name).toList())
                .contains("ask_person");
    }

    /** A stand-in for {@code ChatTools.askPerson}, which needs a runtime this test has not got. */
    private static final class Ask implements Tool {
        @Override
        public String name() {
            return "ask_person";
        }

        @Override
        public String description() {
            return "Ask the person you are talking to.";
        }

        @Override
        public java.util.Map<String, Object> inputSchema() {
            return java.util.Map.of("type", "object", "properties", java.util.Map.of());
        }

        @Override
        public dev.agentkit.core.tool.ToolResult execute(
                dev.agentkit.core.tool.ToolInvocation invocation) {
            return dev.agentkit.core.tool.ToolResult.ok("nobody answered");
        }
    }
}
