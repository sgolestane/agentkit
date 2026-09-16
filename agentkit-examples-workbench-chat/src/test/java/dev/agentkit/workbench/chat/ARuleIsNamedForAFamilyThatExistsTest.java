package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.domain.TriageVerdict;
import dev.agentkit.workbench.store.WorkbenchStore;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Automating a family is the largest thing this console can be asked to do, and the argument
 * is a string a model chose.
 *
 * <p>Triage reduces a category through {@code Spotlight.name} on the way into the store, so
 * every stored category is a name or the word {@code unknown} — and {@code unknown} is the
 * bucket for tickets triage could <em>not</em> name. Reducing the same way here would turn
 * "automate password resets" into a rule that fires on precisely that bucket: the tickets
 * nobody could classify, worked without asking. So this refuses instead.
 */
class ARuleIsNamedForAFamilyThatExistsTest {

    @Test
    void aFamilyNameBecomesARule() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));

        String said = console.say("rules.automate", "category", "access-request");

        assertThat(console.store.rules(Console.TENANT)).singleElement()
                .satisfies(rule -> {
                    assertThat(rule.category()).isEqualTo("access-request");
                    assertThat(rule.enabled()).isTrue();
                });
        assertThat(said).contains("access-request");
        assertThat(console.store.automated(Console.TENANT, "access-request")).isTrue();
    }

    @Test
    void aSentenceIsRefusedRatherThanReducedToTheUnknownBucket() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));

        ToolResult refused = console.call("rules.automate", "category", "password resets");

        assertThat(refused.isError()).isTrue();
        assertThat(console.store.rules(Console.TENANT)).isEmpty();
        // The specific disaster this prevents: a rule on the bucket for tickets triage could
        // not classify, which is the last set of tickets anyone would choose to automate.
        assertThat(console.store.automated(Console.TENANT, "unknown")).isFalse();
    }

    @Test
    void askingTwiceDoesNotMakeTwoRules() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));
        console.say("rules.automate", "category", "access-request");

        String again = console.say("rules.automate", "category", "Access-Request");

        assertThat(console.store.rules(Console.TENANT)).hasSize(1);
        assertThat(again).contains("already");
    }

    @Test
    void togglingPausesTheRuleAndTogglingAgainResumesIt() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));
        console.say("rules.automate", "category", "access-request");
        String id = console.store.rules(Console.TENANT).getFirst().id();

        assertThat(console.say("rules.toggle", "rule_id", id)).contains("paused");
        assertThat(console.store.automated(Console.TENANT, "access-request")).isFalse();

        console.say("rules.toggle", "rule_id", id);
        assertThat(console.store.automated(Console.TENANT, "access-request")).isTrue();
    }

    @Test
    void aRuleSaysHowMuchItCoversAndHowMuchItHasDone() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me."),
                ConsoleAlm.open("IT-2", "Grant access too", "Add them."),
                ConsoleAlm.open("IT-3", "Something else", "Unrelated."),
                ConsoleAlm.open("IT-4", "Grant odd access", "Something unusual.")));
        judged(console, "IT-1", true, "access-request");
        judged(console, "IT-2", true, "access-request");
        judged(console, "IT-3", true, "laptop-request");
        // Same family, but triage said a person is needed — so the rule does not cover it,
        // and a count that ignored the verdict would say three.
        judged(console, "IT-4", false, "access-request");
        console.say("rules.automate", "category", "access-request");
        // One run this rule started, and one the operator started, which does not count.
        Run byRule = console.store.createRun(Console.TENANT, "IT-1", Run.Mode.AUTO,
                Run.Trigger.RULE, "go");
        console.store.createRun(Console.TENANT, "IT-2", Run.Mode.AUTO, Run.Trigger.OPERATOR, "go");

        String said = console.say("rules.list");

        assertThat(said).contains("covers 2 open ticket(s)");
        // The half a coverage count on its own gets wrong: IT-4 is the same family and triage
        // said a person is needed, so it is not the rule's and is sitting there waiting. A
        // console that reported "covers 2" and stopped reads as "that family is handled".
        assertThat(said).contains("leaves 1 to you");
        assertThat(said).contains("1 run(s)");
        assertThat(said).contains("last acted");

        // And the counts are per-outcome, so "it has run nine times" cannot hide nine parks.
        console.store.save(byRule.concluded(Run.Status.COMPLETED, "Added them to the group."));
        assertThat(console.say("rules.list")).contains("1 finished");
    }

    @Test
    void aRuleThatDoesNotExistIsAnAnswerNotAnException() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));

        ToolResult refused = console.call("rules.toggle", "rule_id", "rule-nope");

        assertThat(refused.isError()).isTrue();
        assertThat(refused.content()).contains("rules.list");
    }

    private static void judged(Console console, String key, boolean canHandle, String category) {
        console.store.saveTriage(Console.TENANT, new WorkbenchStore.TriageEntry(key,
                Instant.parse("2026-08-27T10:00:00Z"),
                new TriageVerdict(canHandle, category, 0.9, "Do the thing.", ""),
                Instant.parse("2026-08-27T10:00:00Z"), 0));
    }
}
