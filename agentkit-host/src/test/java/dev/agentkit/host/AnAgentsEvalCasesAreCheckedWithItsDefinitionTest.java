package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.host.repo.DefinitionException;
import dev.agentkit.host.repo.EvalCase;
import dev.agentkit.host.repo.OrgRepo;
import dev.agentkit.host.repo.RepoLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An agent's eval cases live beside it, in {@code evals.yaml}, and are checked as its definition is: the file when the
 * repository is read — every problem at once, and a form's input against the form — and the tools the cases name when
 * the agent is assembled against its connectors.
 */
class AnAgentsEvalCasesAreCheckedWithItsDefinitionTest {

    @TempDir
    Path dir;

    @Test
    void theCasesAreReadWithTheAgent() {
        RepoFixture repo = RepoFixture.copyInto(dir).write("agents/helpdesk/evals.yaml", """
                cases:
                  - name: locked-out
                    as: Priya.Natarajan@acme.example
                    say: I lost my phone and can't sign in.
                    answers: ["My phone."]
                    expect:
                      - calls: open_ticket
                        with: {summary: lost phone}
                      - never: reset_mfa
                      - asks: true
                      - answer_contains: ticket
                      - judge: It tells Priya what happens next.
                """);

        OrgRepo loaded = RepoLoader.load(repo.root(), "v1");
        List<EvalCase> cases = loaded.agents().get("helpdesk").evals();

        assertThat(cases).singleElement().satisfies(c -> {
            assertThat(c.as()).isEqualTo("priya.natarajan@acme.example");
            assertThat(c.answers()).containsExactly("My phone.");
            assertThat(c.expect()).extracting(EvalCase.Expectation::describe).containsExactly(
                    "calls open_ticket with {summary=lost phone}", "never calls reset_mfa", "asks the person something",
                    "answer contains \"ticket\"", "judged: It tells Priya what happens next.");
        });
        assertThat(loaded.agents().get("security-desk").evals()).isEmpty();
    }

    @Test
    void aWrongFileIsRefusedWithEveryProblem() {
        RepoFixture repo = RepoFixture.copyInto(dir)
                .write("agents/helpdesk/evals.yaml", """
                        cases:
                          - name: one
                            as: priya
                            say: hello
                            input: {x: 1}
                            expect: [{calls: open_ticket, asks: true}]
                          - name: one
                            as: priya.natarajan@acme.example
                            say: hello
                            expect: [{asks: maybe}, {answer_contains: hi, with: {a: 1}}, {sees: everything}]
                          - name: three
                            as: priya.natarajan@acme.example
                            input: {x: 1}
                            expect: [{never: reset_mfa}]
                        """);

        assertThatThrownBy(() -> RepoLoader.load(repo.root(), "v1")).isInstanceOf(DefinitionException.class)
                .satisfies(e -> assertThat(((DefinitionException) e).problems()).extracting(Object::toString)
                        .containsExactlyInAnyOrder(
                                "agents/helpdesk/evals.yaml cases[0].as: is the email of someone in the directory",
                                "agents/helpdesk/evals.yaml cases[0]: starts from say or from input: exactly one of them",
                                "agents/helpdesk/evals.yaml cases[0].expect[0]: must say exactly one of [answer_contains, "
                                        + "asks, calls, judge, never]",
                                "agents/helpdesk/evals.yaml cases[1].name: another case is named one",
                                "agents/helpdesk/evals.yaml cases[1].expect[0].asks: is true or false",
                                "agents/helpdesk/evals.yaml cases[1].expect[1].with: goes with calls or never",
                                "agents/helpdesk/evals.yaml cases[1].expect[2].sees: is not an expectation; they are "
                                        + "[answer_contains, asks, calls, judge, never]",
                                "agents/helpdesk/evals.yaml cases[1].expect[2]: must say exactly one of [answer_contains, "
                                        + "asks, calls, judge, never]",
                                "agents/helpdesk/evals.yaml cases[2].input: this agent takes no form; use say"));
    }

    @Test
    void aFormsInputIsCheckedAgainstTheForm() {
        RepoFixture repo = RepoFixture.copyInto(dir)
                .write("agents/helpdesk/agent.yaml", RepoFixture.copyInto(dir.resolve("x")).read("agents/helpdesk/agent.yaml")
                        + "\ninput: {schema: input.yaml}\n")
                .write("agents/helpdesk/input.yaml", """
                        type: object
                        required: [device]
                        properties:
                          device: {type: string, enum: [phone, key], title: Device}
                        """)
                .write("agents/helpdesk/evals.yaml", """
                        cases:
                          - name: lost
                            as: priya.natarajan@acme.example
                            input: {device: laptop}
                            expect: [{calls: open_ticket}]
                        """);

        assertThatThrownBy(() -> RepoLoader.load(repo.root(), "v1")).isInstanceOf(DefinitionException.class)
                .hasMessageContaining("agents/helpdesk/evals.yaml cases[0].input: Device must be one of [phone, key]");
    }

    @Test
    void aToolTheCasesNameMustBeOneOfTheAgentsWhenItIsAssembled() throws Exception {
        try (HelpdeskConnector helpdesk = new HelpdeskConnector()) {
            RepoFixture repo = RepoFixture.copyInto(dir).write("agents/helpdesk/evals.yaml", """
                    cases:
                      - name: gone
                        as: priya.natarajan@acme.example
                        say: Delete me.
                        expect: [{never: delete_account}, {calls: open_ticket}]
                    """);

            assertThatThrownBy(() -> AgentHost.open(repo.root(), "v1", AgentHost.Options.hosted(Secrets.of(Map.of(
                    "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN)))))
                    .isInstanceOf(DefinitionException.class)
                    .hasMessageContaining("agents/helpdesk/evals.yaml cases[0].expect[0]: delete_account is not one of "
                            + "this agent's tools");
        }
    }
}
