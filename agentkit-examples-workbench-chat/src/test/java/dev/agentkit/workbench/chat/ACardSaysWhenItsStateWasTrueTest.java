package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.View;
import dev.agentkit.workbench.domain.Run;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A view that names something with a lifecycle says when that state began.
 *
 * <h2>The failure, which was measured rather than imagined (#405)</h2>
 *
 * <p>A view is frozen the moment its tool runs: {@code Turn} copies its views in, the console
 * renders that stored data, and nothing ever revisits it. Recording the console for #387 left
 * a card reading
 *
 * <pre>
 * run-1   run
 * STATUS       WAITING_FOR_HUMAN
 * WAITING ON   apr-2
 * </pre>
 *
 * <p>sitting in one conversation while a second correctly reported that the run was still
 * waiting — and it would have gone on saying so after somebody decided. Two truths on one
 * screen, the older one looking exactly as authoritative as the newer. A person reads it and
 * either approves something already decided, or does not go, believing somebody else has.
 *
 * <h2>Why the entity's clock rather than the wall</h2>
 *
 * <p>The obvious fix is to stamp the card with the time it was rendered. This does not do
 * that. It reports the moment the state <em>began</em>, taken from the thing being described,
 * which is a better answer for three reasons and this file pins all three:
 *
 * <ul>
 *   <li>It is <strong>true forever</strong> — "waiting since 10:32" is a correct sentence at
 *       11:15, where "as at 10:32" is only a fact about the rendering.</li>
 *   <li>It says <strong>how long</strong>, which is the thing a person actually wants.</li>
 *   <li>There is <strong>no clock to inject</strong>, so no test here measures the machine it
 *       ran on.</li>
 * </ul>
 *
 * <p>The rule is stated on {@code ConsoleTools.since}: a view that names state which can change
 * without the view changing names when that state began; a view that is a fact about a moment —
 * a count, a chart, a diff, an answer — does not.
 */
class ACardSaysWhenItsStateWasTrueTest {

    private static final Instant FILED = Instant.parse("2026-09-05T10:00:00Z");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldsOf(View view) {
        List<Map<String, Object>> cards = (List<Map<String, Object>>) view.data().get("cards");
        return (Map<String, Object>) cards.getFirst().get("fields");
    }

    private static View onlyCard(dev.agentkit.core.tool.ToolResult result) {
        assertThat(result.isError()).as("%s", result.content()).isFalse();
        return result.views().stream().filter(one -> one.kind().equals("cards"))
                .findFirst().orElseThrow(() ->
                        new AssertionError("no card view; got " + result.views().stream()
                                .map(View::kind).toList()));
    }

    @SuppressWarnings("unchecked")
    private static List<String> columnsOf(Console console, String tool, Object... arguments) {
        View table = console.call(tool, arguments).views().stream()
                .filter(one -> one.kind().equals("table")).findFirst()
                .orElseThrow(() -> new AssertionError(tool + " produced no table"));
        return ((List<Map<String, Object>>) table.data().get("columns")).stream()
                .map(one -> String.valueOf(one.get("name"))).toList();
    }

    private static Console consoleHolding(String key, String summary) {
        return new Console(new ConsoleAlm(
                ConsoleAlm.open(key, summary, "Please sort this out.", FILED)));
    }

    @Test
    void aRunCardSaysWhenTheRunEnteredTheStatusItIsIn() {
        Console console = consoleHolding("IT-1", "Grant access");
        console.llm.says("Nothing more to do.");

        View card = onlyCard(console.call("workbench.execute", "ticket_key", "IT-1"));
        Map<String, Object> fields = fieldsOf(card);

        // The pair, together: a status with no time beside it is the defect this closes.
        assertThat(fields).containsKey("status").containsKey("since");
        assertThat(String.valueOf(fields.get("since")))
                .as("a run card whose 'since' is a placeholder tells a reader nothing, which"
                        + " is the state this exists to leave behind")
                .isNotBlank()
                .isNotEqualTo("unknown");
        // Parseable, not prose: a reader compares it against now, and a client may want to.
        assertThat(Instant.parse(String.valueOf(fields.get("since")))).isNotNull();
    }

    @Test
    void aTicketCardSaysWhenTheTicketLastMoved() {
        // status and assignee are both on this card and both change underneath it.
        Console console = consoleHolding("IT-2", "Reset password");

        Map<String, Object> fields = fieldsOf(onlyCard(
                console.call("tickets.get", "ticket_key", "IT-2")));

        assertThat(fields).containsKey("status").containsKey("assignee");
        assertThat(fields).containsEntry("updated", FILED.toString());
    }

    @Test
    void theApprovalsTableSaysWhenEachDecisionWasAskedOrTaken() {
        // The row most worth getting right: a PENDING approval is the most actionable thing
        // this console renders and the one whose truth expires soonest.
        Console console = consoleHolding("IT-3", "Delete the archive");
        console.llm.proposes("jira.add_comment",
                ScriptedLlm.args("ticket_key", "IT-3", "body", "Looking at this."));
        console.call("workbench.execute", "ticket_key", "IT-3");
        assertThat(console.store.approvals(Console.TENANT))
                .as("nothing parked, so this measures an empty table")
                .isNotEmpty();

        View table = console.call("approvals.list").views().stream()
                .filter(one -> one.kind().equals("table")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns =
                (List<Map<String, Object>>) table.data().get("columns");

        assertThat(columns).extracting(one -> String.valueOf(one.get("name")))
                .as("a state column with no time beside it is the same defect as the card's")
                .contains("state", "since");
    }

    @Test
    void aListingOfLifecycleStateIsStampedPerRowToo() {
        // A listing is not exempt. The first draft of this file assumed it was — it asserted
        // that tickets.inbox had NO time column and called the inbox "a fact about a moment".
        // It lists `status` and `assignee`, which are the same mutable state the run card was
        // wrong about, so the exemption was the inconsistency and not the rule.
        //
        // Per row, from each entity's own clock, exactly as the cards do — rather than one
        // stamp for the whole table, which could only be the moment the query ran and would
        // put a wall clock back in.
        Console console = consoleHolding("IT-4", "Monitor flickers");
        console.llm.says("Nothing to do.");
        // runs.list has no table until there is a run to list, so this needs one — and a
        // listing with no rows is the one shape that cannot show a missing column.
        console.call("workbench.execute", "ticket_key", "IT-4");

        assertThat(columnsOf(console, "tickets.inbox")).contains("status", "updated");
        assertThat(columnsOf(console, "runs.list")).contains("status", "since");
    }

    @Test
    void aViewThatNamesNoLifecycleStateIsNotStamped() {
        // The other half of the rule, and the reason it is a rule rather than a habit. What
        // this deployment can do is not a thing that changes underneath the answer, and a
        // stamp on every view is a stamp nobody reads — which is how a signal dies.
        Console console = consoleHolding("IT-6", "Monitor flickers");

        assertThat(columnsOf(console, "workbench.capabilities"))
                .doesNotContain("since").doesNotContain("updated");
    }

    @Test
    void theStampIsTheRunsOwnClockAndNotTheRenderers() {
        // The whole argument for the entity's clock over a render-time one, asserted against
        // the run in the store rather than by reading twice: it is the same value, so it
        // cannot drift, and a later reader of a frozen card is reading a fact about the run.
        Console console = consoleHolding("IT-5", "Grant access");
        console.llm.says("Done.");

        Map<String, Object> fields = fieldsOf(onlyCard(
                console.call("workbench.execute", "ticket_key", "IT-5")));
        Run run = console.store.runs(Console.TENANT).getFirst();

        assertThat(run.status()).isEqualTo(Run.Status.COMPLETED);
        assertThat(fields.get("since"))
                .as("a completed run should report when it completed; reporting anything else"
                        + " means this is a clock rather than the run's own history")
                .isEqualTo(run.completedAt().truncatedTo(ChronoUnit.SECONDS).toString());
    }

    @Test
    void eachStatusReportsTheMomentThatStatusBegan() {
        // Asserted on the function rather than through a live run, and that is the point: a
        // run completes and its card is built microseconds later, so at second precision a
        // wall clock and completedAt agree and the mutation that matters most — replacing
        // this with Instant.now() — survives a test that goes through the console.
        Instant made = Instant.parse("2026-09-05T09:00:00Z");
        Instant began = Instant.parse("2026-09-05T09:05:00Z");
        Instant ended = Instant.parse("2026-09-05T09:09:00Z");

        assertThat(ConsoleTools.enteredAt(run(Run.Status.COMPLETED, made, began, ended)))
                .isEqualTo(ended);
        assertThat(ConsoleTools.enteredAt(run(Run.Status.FAILED, made, began, ended)))
                .isEqualTo(ended);
        // Still going, so the moment it started IS the moment this status began.
        assertThat(ConsoleTools.enteredAt(run(Run.Status.WAITING_FOR_HUMAN, made, began, null)))
                .isEqualTo(began);
        assertThat(ConsoleTools.enteredAt(run(Run.Status.RUNNING, made, began, null)))
                .isEqualTo(began);
        // Never picked up: its own creation is the honest answer, not a null.
        assertThat(ConsoleTools.enteredAt(run(Run.Status.PENDING, made, null, null)))
                .isEqualTo(made);
        // And the half-written cases, which a store can produce and a card must not choke on.
        assertThat(ConsoleTools.enteredAt(run(Run.Status.COMPLETED, made, began, null)))
                .isEqualTo(began);
        assertThat(ConsoleTools.enteredAt(run(Run.Status.RUNNING, made, null, null)))
                .isEqualTo(made);
    }

    private static Run run(Run.Status status, Instant made, Instant began, Instant ended) {
        return new Run("run-1", Console.TENANT, "IT-1", Run.Mode.SUPERVISED,
                Run.Trigger.OPERATOR, "do the thing", status, made, began, ended, "");
    }
}
