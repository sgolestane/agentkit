package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.workbench.domain.Ticket;
import dev.agentkit.workbench.domain.TriageVerdict;
import dev.agentkit.workbench.store.WorkbenchStore;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * A badge that shows yesterday's answer with today's confidence.
 *
 * <p>{@link WorkbenchStore.TriageEntry} records what a verdict was computed against — the
 * ticket revision, and a fingerprint of what the agent had been taught — for exactly this
 * question. A console that read the verdict and not those two would tell an operator that
 * The agent cannot handle a ticket that it learned how to handle last Tuesday.
 */
class AVerdictSaysWhenItIsOutOfDateTest {

    private static final Instant JUDGED_AT = Instant.parse("2026-08-27T10:00:00Z");

    @Test
    void aVerdictAgainstTheCurrentTicketAndCurrentKnowledgeIsNotStale() {
        Console console = withVerdict(JUDGED_AT, 0);

        assertThat(console.say("tickets.inbox"))
                .contains("The agent can handle it (access-request)")
                .doesNotContain("(stale)");
    }

    @Test
    void aTicketEditedSinceTheVerdictMakesItStale() {
        Console console = withVerdict(JUDGED_AT, 0);
        console.alm.update(ConsoleAlm.open("IT-1", "Grant access",
                "Actually, make that the whole team.", JUDGED_AT.plusSeconds(3600)));

        assertThat(console.say("tickets.inbox")).contains("(stale)");
    }

    @Test
    void knowledgeLearnedSinceTheVerdictMakesItStaleToo() {
        Console console = withVerdict(JUDGED_AT, 0);
        // The learning loop's whole point: what the agent was taught can flip "needs a person"
        // into "can handle", and a verdict formed before the lesson does not know that.
        console.learnings.record("Reporting-group access is granted by the ops rota, not IT.");

        assertThat(console.learnings.fingerprint()).isNotEqualTo(0);
        assertThat(console.say("tickets.inbox")).contains("(stale)");
    }

    @Test
    void aTicketNobodyHasJudgedSaysThatRatherThanGuessing() {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));

        assertThat(console.say("tickets.inbox")).contains("not triaged");
    }

    /** A console holding one ticket and one stored verdict against a stated world. */
    private static Console withVerdict(Instant ticketUpdatedAt, int knowledgeFingerprint) {
        Ticket ticket = ConsoleAlm.open("IT-1", "Grant access",
                "Please add dana@example.com to the reporting group.", ticketUpdatedAt);
        Console console = new Console(new ConsoleAlm(ticket));
        console.store.saveTriage(Console.TENANT, new WorkbenchStore.TriageEntry(
                ticket.key(), ticketUpdatedAt,
                new TriageVerdict(true, "access-request", 0.9, "Add them to the group.", ""),
                ticketUpdatedAt, knowledgeFingerprint));
        return console;
    }
}
