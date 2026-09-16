package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.View;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.domain.Ticket;
import dev.agentkit.workbench.domain.TriageVerdict;
import dev.agentkit.workbench.store.WorkbenchStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The half a dashboard did for free.
 *
 * <p>A screen shows a ticket as a ticket: the key at the top, the status beside it, the
 * description as filed, and a way through to Jira. A conversation that hands back a paragraph
 * about a ticket has made the person read it out of prose and then go and find the real thing.
 * That is the difference between a chat window with tools behind it and a workbench.
 *
 * <p>These are assertions about what a person is shown, which is why they read the view rather
 * than the digest. The digest is the model's copy and is checked elsewhere.
 */
class EachThingHasACardWorthLookingAtTest {

    private static final Instant WHEN = Instant.parse("2026-08-27T10:00:00Z");

    @Test
    void aTicketCardCarriesWhatAScreenWouldShowAndAWayThroughToJira() {
        Ticket ticket = ConsoleAlm.open("IT-421", "Grant access to reporting",
                "Please add dana@example.com. **URGENT**");
        Console console = new Console(new ConsoleAlm(ticket));

        View card = console.call("tickets.get", "ticket_key", "IT-421").views().getFirst();

        assertThat(card.kind()).isEqualTo("cards");
        Map<String, Object> one = onlyCard(card);
        assertThat(one.get("title")).isEqualTo("IT-421");
        assertThat(one.get("subtitle")).isEqualTo("Grant access to reporting");
        // The link the card asked for. Without it a person reads the card and then goes and
        // finds the ticket anyway, which is the card not having done its job.
        assertThat(one.get("url")).isEqualTo("https://acme.atlassian.net/browse/IT-421");
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) one.get("fields");
        assertThat(fields).containsEntry("status", "Open").containsEntry("assignee", "nobody")
                .containsEntry("reporter", "Riley Chen").containsEntry("runs", 0);
        // As filed, asterisks and all. The renderer never runs markdown over a field, so what
        // the requester typed is what is shown — which is how somebody works out what was
        // actually asked for.
        assertThat(String.valueOf(fields.get("as filed"))).contains("**URGENT**");
    }

    @Test
    void aDeploymentThatCannotSayWhereTheTicketLivesCarriesNoLinkRatherThanABrokenOne() {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-1", "Grant access", "Add me."));
        Console console = new Console(alm,
                new ConsoleTools.Deployment(Console.TENANT, Console.OPERATOR, ""));

        assertThat(onlyCard(console.call("tickets.get", "ticket_key", "IT-1").views().getFirst()))
                .containsEntry("url", "");
    }

    @Test
    void aTicketCardSaysWhenItsVerdictIsOutOfDate() {
        Ticket ticket = ConsoleAlm.open("IT-1", "Grant access", "Add me.", WHEN);
        Console console = new Console(new ConsoleAlm(ticket));
        console.store.saveTriage(Console.TENANT, new WorkbenchStore.TriageEntry("IT-1", WHEN,
                new TriageVerdict(true, "access-request", 0.9, "Add them.", ""), WHEN, 0));

        @SuppressWarnings("unchecked")
        Map<String, Object> fresh = (Map<String, Object>) onlyCard(
                console.call("tickets.get", "ticket_key", "IT-1").views().getFirst())
                .get("fields");
        assertThat(String.valueOf(fresh.get("triage"))).doesNotContain("out of date");

        console.alm.update(ConsoleAlm.open("IT-1", "Grant access",
                "Actually the whole team.", WHEN.plusSeconds(3600)));

        @SuppressWarnings("unchecked")
        Map<String, Object> stale = (Map<String, Object>) onlyCard(
                console.call("tickets.get", "ticket_key", "IT-1").views().getFirst())
                .get("fields");
        assertThat(String.valueOf(stale.get("triage"))).contains("out of date");
        // And the model is told what to do about it, since a card here has no button on it.
        assertThat(console.say("tickets.get", "ticket_key", "IT-1")).contains("triage.ticket");
    }

    @Test
    void aRunIsATimelineBecauseWhereItStoppedIsTheQuestion() {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-1", "Grant access", "Add me."));
        Console console = new Console(alm);
        console.llm.proposes("jira.add_comment",
                ScriptedLlm.args("ticket_key", "IT-1", "body", "Granted."));
        console.say("workbench.execute", "ticket_key", "IT-1");
        String runId = console.store.runs(Console.TENANT).getFirst().id();

        View view = console.call("runs.get", "run_id", runId).views().getFirst();

        assertThat(view.kind()).isEqualTo("timeline");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> moments =
                (List<Map<String, Object>>) view.data().get("moments");
        // It opens with the run and closes with how it ended, whatever happened between —
        // so a person reading it never has to work out which row is the outcome.
        assertThat(moments).hasSizeGreaterThan(2);
        assertThat(moments.getFirst()).containsEntry("kind", "run");
        assertThat(moments.getLast()).containsEntry("label", "waiting for human");
        assertThat(moments).anySatisfy(moment ->
                assertThat(moment.get("kind")).isEqualTo("decision"));
    }

    @Test
    void theOrderIsTheData() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));
        Run run = console.store.createRun(Console.TENANT, "IT-1", Run.Mode.AUTO,
                Run.Trigger.OPERATOR, "work it");
        console.store.append(run.id(), Run.Event.Type.TOOL_STARTED, Map.of("step", "first"));
        console.store.append(run.id(), Run.Event.Type.HUMAN_APPROVED, Map.of("step", "second"));
        console.store.append(run.id(), Run.Event.Type.TOOL_COMPLETED, Map.of("step", "third"));

        View view = console.call("runs.get", "run_id", run.id()).views().getFirst();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> moments =
                (List<Map<String, Object>>) view.data().get("moments");
        // The only claim a timeline makes that a table does not, and the one thing that makes
        // it the right drawing: oldest first, exactly as it happened. Reversed, it is a
        // perfectly plausible-looking list of the same events telling the wrong story about
        // what caused what.
        assertThat(moments).map(one -> String.valueOf(one.get("detail")))
                .filteredOn(detail -> detail.contains("step="))
                .containsExactly("{step=first}", "{step=second}", "{step=third}");
    }

    @Test
    void aRunThatFailedIsMarkedWhereItFailed() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));
        Run run = console.store.createRun(Console.TENANT, "IT-1", Run.Mode.AUTO,
                Run.Trigger.OPERATOR, "work it");
        console.store.save(run.concluded(Run.Status.FAILED, "The connector refused."));

        View view = console.call("runs.get", "run_id", run.id()).views().getFirst();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> moments =
                (List<Map<String, Object>>) view.data().get("moments");
        assertThat(moments.getLast()).containsEntry("failed", true);
    }

    @Test
    void aVeryLongRunStillAnswers() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));
        Run run = console.store.createRun(Console.TENANT, "IT-1", Run.Mode.AUTO,
                Run.Trigger.OPERATOR, "work it");
        for (int i = 0; i < 600; i++) {
            console.store.append(run.id(), Run.Event.Type.TOOL_COMPLETED,
                    Map.of("tool", "jira.get_ticket", "note", "x".repeat(2_000)));
        }

        // View refuses a payload over its size bound, and it refuses it by throwing — so an
        // unbounded timeline turns a long run into an exception out of the handler instead of
        // an answer, which is the one failure this whole surface is built not to have.
        assertThat(console.call("runs.get", "run_id", run.id()).isError()).isFalse();

        View view = console.call("runs.get", "run_id", run.id()).views().getFirst();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> moments =
                (List<Map<String, Object>>) view.data().get("moments");
        assertThat(moments.size()).isLessThanOrEqualTo(203);
        // And it says what it left out. A silent drop reads as "that is all that happened".
        assertThat(moments).anySatisfy(moment ->
                assertThat(String.valueOf(moment.get("label")))
                        .contains("earlier event(s) not shown"));
    }

    @Test
    void aRunSummaryIsCutAtAWordAndNotThroughOne() {
        // The scar the dashboard carries in a comment: summaries are markdown flattened into
        // one string, so heading markers arrive mid-sentence and a hard character slice cuts
        // words in half. The screenshot that motivated it read "Ass", which was "Assigned".
        assertThat(ConsoleTools.summaryLine("# Analysis ## Plan Assigned the ticket"))
                .isEqualTo("Analysis · Plan Assigned the ticket");
        assertThat(ConsoleTools.summaryLine(null)).isEqualTo("none recorded");
        assertThat(ConsoleTools.summaryLine("   ")).isEqualTo("none recorded");

        // The scar itself. At exactly 200 characters this lands inside "Assigned", and a
        // bound alone — Cut.to, which is what the first draft used — happily leaves "Ass".
        String repeated = "Assigned the ticket to the integration identity and ".repeat(20);
        String cut = ConsoleTools.summaryLine(repeated);
        assertThat(cut).endsWith("…");
        assertThat(cut.length()).isLessThanOrEqualTo(201);
        String words = cut.substring(0, cut.length() - 1);
        assertThat(repeated).contains(words + " ");
        assertThat(words).doesNotEndWith("Ass").doesNotEndWith("Assig");

        // A single unbroken token has no boundary to back up to, and losing the whole line is
        // worse than cutting it: a 400-character URL still tells somebody which URL.
        assertThat(ConsoleTools.summaryLine("x".repeat(400))).hasSize(201);
    }

    @Test
    void learningsAndGapsAreListsAPersonCanRead() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));
        console.learnings.record("Reporting access is granted by the ops rota, not IT.");
        console.store.save(new dev.agentkit.workbench.domain.CapabilityGap("gap-1", Console.TENANT,
                "run-1", "IT-1", "identity.reset", "Nothing here can reset a password.", WHEN));

        View learnings = console.call("learnings.list").views().getFirst();
        assertThat(learnings.kind()).isEqualTo("table");
        assertThat(learnings.data().get("rows"))
                .isEqualTo(List.of(List.of(
                        "Reporting access is granted by the ops rota, not IT.")));

        View gaps = console.call("gaps.list").views().getFirst();
        assertThat(gaps.kind()).isEqualTo("table");
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) gaps.data().get("rows");
        assertThat(rows).singleElement().satisfies(row ->
                assertThat(row).containsExactly("identity.reset", "IT-1",
                        "Nothing here can reset a password."));
    }

    @Test
    void aRuleSaysWhatPausingItWouldMean() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me."),
                ConsoleAlm.open("IT-2", "Grant access too", "Add them.")));
        judged(console, "IT-1", "access-request");
        judged(console, "IT-2", "access-request");
        console.say("rules.automate", "category", "access-request");
        String id = console.store.rules(Console.TENANT).getFirst().id();

        // "Paused" is a state; "paused, and these two now wait for you" is the consequence,
        // and the consequence is what somebody is actually deciding about.
        assertThat(console.say("rules.toggle", "rule_id", id))
                .contains("paused")
                .contains("2 open ticket(s)")
                .contains("wait for you");
        assertThat(console.say("rules.toggle", "rule_id", id))
                .contains("without asking")
                .contains("2 open ticket(s)");
    }

    private static void judged(Console console, String key, String category) {
        console.store.saveTriage(Console.TENANT, new WorkbenchStore.TriageEntry(key, WHEN,
                new TriageVerdict(true, category, 0.9, "Do it.", ""), WHEN, 0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyCard(View view) {
        List<Map<String, Object>> cards = (List<Map<String, Object>>) view.data().get("cards");
        assertThat(cards).hasSize(1);
        return cards.getFirst();
    }
}
