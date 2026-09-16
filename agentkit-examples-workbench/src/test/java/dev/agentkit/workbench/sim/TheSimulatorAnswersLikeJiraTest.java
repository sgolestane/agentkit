package dev.agentkit.workbench.sim;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.workbench.connector.Alm;
import dev.agentkit.workbench.connector.HttpTransport;
import dev.agentkit.workbench.connector.JiraClient;
import dev.agentkit.workbench.domain.Ticket;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The shipped {@code JiraClient} against the simulator, over real HTTP on a loopback port —
 * both sides of the wire exercised together, no canned responses. If the simulator drifts
 * from the response shapes the client parses, this is the test that says so.
 */
class TheSimulatorAnswersLikeJiraTest {

    private JiraSimulator simulator;
    private JiraClient client;

    @BeforeEach
    void boot() throws Exception {
        simulator = new JiraSimulator(0);
        simulator.start();
        client = new JiraClient(HttpTransport.overTheWire(), simulator.baseUrl(),
                "sid@example.com", "any-token", null);
    }

    @AfterEach
    void shutDown() {
        simulator.close();
    }

    @Test
    void theWholeWorkbenchConversationRoundTrips() {
        // Identity: the simulator attributes writes to the Basic credentials' user.
        Alm.Me me = client.myself();
        assertThat(me.displayName()).isEqualTo("sid@example.com");

        // Inbox: seeded, unresolved, newest-updated first.
        List<Ticket> inbox = client.inbox(50);
        assertThat(inbox).isNotEmpty();
        assertThat(inbox).allMatch(ticket -> ticket.statusCategory() != Ticket.Category.DONE);

        // One ticket, in full.
        Ticket mailbox = client.ticket("SIM-2").orElseThrow();
        assertThat(mailbox.summary()).contains("mailbox");
        assertThat(mailbox.reporter()).isEqualTo("Priya Patel");
        assertThat(mailbox.createdAt()).isBefore(mailbox.updatedAt().plusSeconds(1));

        // Free-text search reaches the description.
        assertThat(client.search("flickers", 10))
                .extracting(Ticket::key).containsExactly("SIM-3");

        // Writes really change state and read back.
        client.assignToMe("SIM-1");
        assertThat(client.ticket("SIM-1").orElseThrow().assignee())
                .isEqualTo("sid@example.com");

        client.addComment("SIM-1", "Added Dana to Analytics Viewers; verifying.");
        assertThat(client.comments("SIM-1"))
                .anyMatch(comment -> comment.author().equals("sid@example.com")
                        && comment.body().contains("Analytics Viewers"));

        assertThat(client.transitions("SIM-1"))
                .extracting(Alm.Transition::toName).contains("In Progress", "Done");
        client.transition("SIM-1", "Done");
        Ticket done = client.ticket("SIM-1").orElseThrow();
        assertThat(done.status()).isEqualTo("Done");
        assertThat(done.statusCategory()).isEqualTo(Ticket.Category.DONE);

        // A resolved ticket leaves the default inbox scope, like real Jira.
        assertThat(client.inbox(50)).extracting(Ticket::key).doesNotContain("SIM-1");

        // A missing key is an empty answer, not a fault.
        assertThat(client.ticket("SIM-404")).isEmpty();
    }
}
