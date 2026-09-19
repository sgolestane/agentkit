package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.eval.CheckOutcome;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A rehearsal is a real conversation with the agent against its real connectors, as the person a case names — with
 * every tool that could change something refused before it reaches the connector, and recorded, so a pull request can
 * show what the agent set out to do and change nothing.
 */
class ARehearsalChangesNothingAndShowsWhatItWouldHaveDoneTest {

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private AgentHost host;

    @BeforeEach
    void start() throws Exception {
        helpdesk = new HelpdeskConnector();
    }

    @AfterEach
    void stop() {
        if (host != null) {
            host.close();
        }
        helpdesk.close();
    }

    private Rehearsal open(String evals, ScriptedLlm llm) {
        RepoFixture repo = RepoFixture.copyInto(dir).write("agents/helpdesk/evals.yaml", evals);
        host = AgentHost.open(repo.root(), "v1", AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
        return new Rehearsal(host, llm, () -> Instant.parse("2026-09-18T12:00:00Z"), Duration.ofSeconds(20));
    }

    @Test
    void readsRunAndEverythingThatWouldChangeSomethingIsRefusedAndRecorded() {
        ScriptedLlm llm = new ScriptedLlm(
                ScriptedLlm.toolUse("1", "directory_lookup", Map.of("email", HelpdeskConnector.PRIYA)),
                ScriptedLlm.toolUse("2", "reset_mfa", Map.of()),
                ScriptedLlm.toolUse("3", "open_ticket", Map.of("summary", "Lost phone")),
                ScriptedLlm.toolUse("4", "send_message", Map.of("to_email", HelpdeskConnector.DANA, "text", "FYI")),
                ScriptedLlm.text("I would reset your MFA and open a ticket."));
        Rehearsal rehearsal = open("""
                cases:
                  - name: locked-out
                    as: priya.natarajan@acme.example
                    say: I lost my phone and can't sign in.
                    expect:
                      - calls: open_ticket
                        with: {summary: lost phone}
                      - calls: reset_mfa
                      - asks: false
                      - answer_contains: open a ticket
                """, llm);

        Rehearsal.Result result = rehearsal.rehearse(host.agent("helpdesk").orElseThrow()).get(0);

        assertThat(result.outcomes()).as(result.outcomes().toString()).allMatch(CheckOutcome::passed);
        assertThat(result.passed()).isTrue();
        assertThat(result.wouldHave()).extracting(Rehearsal.Call::tool, Rehearsal.Call::would).containsExactly(
                org.assertj.core.groups.Tuple.tuple("reset_mfa", "grant"),
                org.assertj.core.groups.Tuple.tuple("open_ticket", "request"),
                org.assertj.core.groups.Tuple.tuple("send_message", "notify"));
        assertThat(result.calls()).extracting(Rehearsal.Call::tool).startsWith("directory_lookup");
        // The read reached the connector; nothing else did, and the confirmed tool asked nobody.
        assertThat(helpdesk.calls("directory_lookup")).isNotEmpty();
        assertThat(helpdesk.calls("reset_mfa")).isEmpty();
        assertThat(helpdesk.calls("open_ticket")).isEmpty();
        assertThat(helpdesk.calls("send_message")).isEmpty();
        assertThat(llm.received().get(2).messages().toString()).contains("Not run: this is a rehearsal")
                .contains("reset_mfa would grant");
    }

    @Test
    void aCheckThatDoesNotHoldSaysWhy() {
        ScriptedLlm llm = new ScriptedLlm(
                ScriptedLlm.toolUse("1", "open_ticket", Map.of("summary", "Printer on fire")),
                ScriptedLlm.text("Ticket requested."));
        Rehearsal rehearsal = open("""
                cases:
                  - name: printer
                    as: priya.natarajan@acme.example
                    say: The printer is on fire.
                    expect:
                      - never: open_ticket
                      - calls: open_ticket
                        with: {summary: paper jam}
                      - answer_contains: fire brigade
                """, llm);

        Rehearsal.Result result = rehearsal.rehearse(host.agent("helpdesk").orElseThrow()).get(0);

        assertThat(result.passed()).isFalse();
        assertThat(result.outcomes()).filteredOn(o -> !o.passed()).extracting(CheckOutcome::detail).containsExactly(
                "tool was requested",
                "no call to it matched; the calls to it carried ['#1 summary=Printer on fire']",
                "the answer does not say it");
    }

    @Test
    void aQuestionInTheReplyIsAnsweredInTheNextMessageFromTheCase() {
        ScriptedLlm llm = new ScriptedLlm(
                ScriptedLlm.text("Which device did you lose?"),
                ScriptedLlm.toolUse("1", "open_ticket", Map.of("summary", "Lost phone")),
                ScriptedLlm.text("I would open a ticket for your phone."));
        Rehearsal rehearsal = open("""
                cases:
                  - name: which-device
                    as: priya.natarajan@acme.example
                    say: I lost my MFA device.
                    answers: ["My phone."]
                    expect:
                      - asks: true
                      - calls: open_ticket
                """, llm);

        Rehearsal.Result result = rehearsal.rehearse(host.agent("helpdesk").orElseThrow()).get(0);

        assertThat(result.passed()).as(result.outcomes().toString()).isTrue();
        assertThat(result.questions()).containsExactly("Which device did you lose?");
        assertThat(llm.received().get(1).messages().toString()).contains("My phone.");
    }

    @Test
    void someoneTheDirectoryDoesNotKnowIsNotRehearsedAs() {
        Rehearsal rehearsal = open("""
                cases:
                  - name: stranger
                    as: nobody@acme.example
                    say: Hello.
                    expect: [{asks: false}]
                """, new ScriptedLlm());

        Rehearsal.Result result = rehearsal.rehearse(host.agent("helpdesk").orElseThrow()).get(0);

        assertThat(result.state()).isEqualTo("NOT_RUN");
        assertThat(result.outcomes()).singleElement().satisfies(o -> assertThat(o.detail())
                .isEqualTo("nobody@acme.example is not in the directory"));
    }

    @Test
    void theArgumentsACallMustCarryMatchIgnoringCaseAndListsByWhatTheyHold() {
        var args = Rehearsal.args(Map.of("groups", List.of("Sales", "contractors"), "hours", 2));
        var call = new dev.agentkit.core.tool.ToolInvocation("c", "okta_create_user",
                Map.of("groups", List.of("sales", "contractors", "extra"), "hours", "2"));

        assertThat(args.matches(call)).isTrue();
        assertThat(args.matches(new dev.agentkit.core.tool.ToolInvocation("c", "okta_create_user",
                Map.of("groups", List.of("sales"), "hours", 2)))).isFalse();
    }
}
