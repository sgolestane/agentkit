package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.Tool;
import dev.agentkit.workbench.tools.AlmTools;
import dev.agentkit.workbench.tools.ToolPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The claim this whole surface rests on.
 *
 * <p>The console's model drives the workbench. It must not be able to write to Jira the way
 * a run writes to Jira, because a run's writes go through the supervisor: the gates, the
 * approvals, the trust floor and the standing refusals. Registering {@code AlmTools}' writers
 * on this surface would be one line and would quietly make the console a second door into the
 * same system with none of that behind it.
 *
 * <p>Two of them do write, and are the exception on purpose: {@code alm.comment} and
 * {@code alm.transition} are a <em>person</em> acting, recorded as theirs. Those are covered
 * by {@link TheOperatorsOwnHandsAreRecordedAsTheirsTest}; everything else is covered here.
 */
class NothingReachesJiraExceptThroughARunOrTheOperatorTest {

    /** The console's own two, which are a person acting and are meant to write. */
    private static final Set<String> THE_OPERATORS_HANDS = Set.of("alm.comment", "alm.transition");

    @Test
    void theRunTiersWritersAreNotRegisteredOnThisSurface() {
        Console console = new Console(new ConsoleAlm(ConsoleAlm.open("IT-1", "Grant access",
                "Please add dana@example.com to the reporting group.")));

        List<String> names = console.tools().stream().map(Tool::name).toList();
        List<String> runTierWriters = AlmTools.policies().stream()
                .filter(policy -> policy.baselineRisk() != dev.agentkit.workbench.domain.Risk.READ)
                .map(ToolPolicy::name)
                .toList();

        assertThat(runTierWriters).isNotEmpty();
        assertThat(names).doesNotContainAnyElementsOf(runTierWriters);
    }

    @Test
    void aModelThatWantsToCommentGetsNowhereThroughAnythingButItsOwnHands() {
        ConsoleAlm alm = new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add dana@example.com to reporting."),
                ConsoleAlm.open("IT-2", "Reset password", "I am locked out."));
        alm.seedComment("IT-1", "Riley Chen", "Any progress?");
        Console console = new Console(alm);
        // Every turn this model takes wants to write to the ticket. Whether it gets to is not
        // up to the console: a preview refuses the writers outright and an execute parks.
        for (int i = 0; i < 40; i++) {
            console.llm.proposes("jira.add_comment",
                    ScriptedLlm.args("ticket_key", "IT-1", "body", "Done, closing this."));
        }

        for (Tool tool : console.tools()) {
            if (THE_OPERATORS_HANDS.contains(tool.name())) {
                continue;
            }
            console.call(tool.name(), plausibleArgumentsFor(tool.name()));
        }

        assertThat(alm.commentsWritten)
                .as("no console tool but the operator's own two may write to Jira")
                .isEmpty();
        assertThat(alm.transitionsRun).isEmpty();
        assertThat(alm.assignments).isEmpty();
    }

    /**
     * Arguments good enough that the tool does its work rather than refusing for want of one.
     *
     * <p>An id-shaped argument is deliberately real where the store has one and deliberately
     * absent where it does not: a tool that refuses an unknown id has still been called, and
     * a tool that would have written before checking the id would have written by now.
     */
    private static Object[] plausibleArgumentsFor(String tool) {
        return switch (tool) {
            case "tickets.get", "tickets.search", "workbench.preview", "workbench.execute" ->
                    new Object[] {"ticket_key", "IT-1", "text", "access"};
            case "workbench.bulk_execute" -> new Object[] {"ticket_keys", List.of("IT-1", "IT-2")};
            case "runs.get", "evals.capture" -> new Object[] {"run_id", "run-1"};
            case "rules.toggle" -> new Object[] {"rule_id", "rule-1"};
            case "rules.automate" -> new Object[] {"category", "access-request"};
            case "decisions.lift", "decisions.revoke" ->
                    new Object[] {"capability", "ticketing.write"};
            case "approvals.decide" ->
                    new Object[] {"approval_id", "ap-1", "approved", true};
            case "approvals.answer" ->
                    new Object[] {"approval_id", "ap-1", "answer", "Yes, go ahead."};
            default -> new Object[] {};
        };
    }

    @Test
    void everyToolIsGradedAndNothingIsGradedThatDoesNotExist() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add someone.")));
        // Built with everything wired, so the optional tools are present to be graded.
        List<String> built = ConsoleTools.of(Console.DEPLOYMENT, console.alm,
                        console.workbench, null, console.learnings, console.store, null)
                .stream().map(Tool::name).toList();
        List<String> graded = ConsoleTools.policies().stream().map(ToolPolicy::name).toList();

        // The triage and capture tools need collaborators this console has not wired, so they
        // are graded and absent — which is the right way round. The reverse would be a tool
        // the supervisor has no policy for, and ToolCatalog grades those HIGH by default
        // precisely because "nobody wrote one" must not read as "somebody said it was safe".
        assertThat(graded).containsAll(built);
        assertThat(graded).doesNotHaveDuplicates();
    }

    @Test
    void theGradingSaysWhatIsIrreversible() {
        Map<String, ToolPolicy> byName = ConsoleTools.policies().stream()
                .collect(java.util.stream.Collectors.toMap(ToolPolicy::name, one -> one));

        // A comment cannot be unsaid; a transition can be transitioned back.
        assertThat(byName.get("alm.comment").reversible()).isFalse();
        assertThat(byName.get("alm.transition").reversible()).isTrue();
        // Executing is the door to the run tier: what happens inside is bounded by that
        // tier's gates, not by anything stated here, so it is graded as what it opens.
        assertThat(byName.get("workbench.execute").baselineRisk())
                .isEqualTo(dev.agentkit.workbench.domain.Risk.HIGH);
        assertThat(byName.get("workbench.execute").reversible()).isFalse();
        // A rehearsal is not READ — it spends model calls and leaves a run — but it is safe.
        assertThat(byName.get("workbench.preview").baselineRisk())
                .isEqualTo(dev.agentkit.workbench.domain.Risk.LOW);
        assertThat(byName.get("workbench.preview").reversible()).isTrue();
    }

    @Test
    void aWriterIsNotFiledUnderTheReadFamily() {
        // A capability is what progressive disclosure searches on and what a standing refusal
        // names. Filing a writer under the read family makes it a tool that a refusal of the
        // reads would stop and a refusal of the writers would not — the exact inversion of
        // what an operator saying "stop writing things" means.
        for (ToolPolicy policy : ConsoleTools.policies()) {
            if (policy.baselineRisk() == dev.agentkit.workbench.domain.Risk.READ) {
                continue;
            }
            assertThat(policy.capability())
                    .as("%s changes something and must not wear the read family's name",
                            policy.name())
                    .isNotEqualTo("workbench.read");
        }
        assertThat(ConsoleTools.policies()).extracting(ToolPolicy::capability)
                .contains("workbench.read", "workbench.run", "workbench.decide",
                        "workbench.operator", "workbench.triage", "workbench.evals");
    }
}
