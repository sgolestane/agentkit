package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Step;
import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.message.Message;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plan-execute agent's {@code before:} checks run for each task a request holds, as the person, before anything is
 * planned. A check that answers with an error stops that task: nothing is planned or done, and the person is told why.
 * What a check answers otherwise is given to the planner and to each step.
 */
class ATaskIsCheckedBeforeItIsPlannedTest {

    private static final String PRIYA = new Tenant("acme", HelpdeskConnector.PRIYA).id();

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private OrgHost org;
    private ChatRuntime runtime;

    private void start(ScriptedLlm llm) throws Exception {
        helpdesk = new HelpdeskConnector();
        RepoFixture repo = RepoFixture.copyInto(dir)
                .write("agents/replacement/agent.yaml", """
                        name: Laptop Replacement
                        description: Replaces a lost or broken laptop end to end.
                        pattern: plan-execute
                        prompt:
                          planner: planner.md
                          executor: executor.md
                        tools:
                          - connector: helpdesk
                            effects: [read, request, notify]
                        bind:
                          helpdesk/open_ticket: {requester: principal.email}
                        input:
                          schema: input.yaml
                        before:
                          - tool: helpdesk/directory_lookup
                            with: {email: input.email}
                        """)
                .write("agents/replacement/input.yaml", """
                        type: object
                        required: [email]
                        properties:
                          email: {type: string, title: Whose laptop}
                          reason: {type: string, title: What happened}
                        """)
                .write("agents/replacement/planner.md", "You plan laptop replacements. Answer with a numbered list only.")
                .write("agents/replacement/executor.md", "You carry out one step of a laptop replacement.");
        org = OrgHost.open(repo.root(), AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        HostChat chat = new HostChat(Map.of("acme", org), Optional.of(llm), () -> Instant.parse("2026-09-19T12:00:00Z"),
                self::get);
        runtime = new ChatRuntime(new InMemoryChatStore(), new ChatEvents(), chat);
        self.set(runtime);
    }

    @AfterEach
    void stop() {
        if (runtime != null) {
            runtime.close();
        }
        if (org != null) {
            org.close();
        }
        if (helpdesk != null) {
            helpdesk.close();
        }
    }

    @Test
    void aTaskACheckRefusesIsNotPlannedAndNothingIsDone() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        start(llm);

        Turn turn = say("Replace this laptop.\n- email: " + HelpdeskConnector.NOBODY + "\n- reason: stolen");

        assertThat(turn.state()).isEqualTo(Turn.State.COMPLETED);
        assertThat(turn.answer()).isEqualTo("Nothing was done. Nobody in the directory has the email "
                + HelpdeskConnector.NOBODY);
        assertThat(llm.received()).as("no plan was asked for").isEmpty();
        assertThat(helpdesk.calls("open_ticket")).isEmpty();
        assertThat(turn.steps()).filteredOn(s -> s.kind() == Step.Kind.TOOL_CALL).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("directory_lookup");
            assertThat(s.failed()).isTrue();
            assertThat(s.detail()).containsEntry("check", true);
        });
    }

    @Test
    void severalTasksInOneMessageAreEachCheckedPlannedAndAnsweredOnTheirOwn() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(
                ScriptedLlm.text("1. Open a ticket for Priya's replacement laptop."),
                ScriptedLlm.toolUse("s1", "open_ticket", Map.of("summary", "Replacement laptop for Priya")),
                ScriptedLlm.text("Opened TICKET-1001.\nOUTCOME: done"));
        start(llm);

        Turn turn = say("""
                Replace these laptops.

                email: %s
                reason: stolen

                email: %s
                reason: broken
                """.formatted(HelpdeskConnector.PRIYA, HelpdeskConnector.NOBODY));

        // One refused does not stop the other; each has its own section, under its own label.
        assertThat(turn.answer()).isEqualTo("""
                ### priya.natarajan@acme.example · stolen

                1. Open a ticket for Priya's replacement laptop.
                   - Done: Opened TICKET-1001.

                ### nobody@acme.example · broken

                Nothing was done. Nobody in the directory has the email nobody@acme.example""");
        String planned = llm.received().get(0).messages().stream().map(Message::text).reduce("", String::concat);
        assertThat(planned).contains("Checked before planning", "Priya Natarajan").doesNotContain("nobody@");
        assertThat(turn.views()).singleElement().satisfies(v -> assertThat(v.data().toString())
                .contains("Plan for priya.natarajan@acme.example · stolen"));
    }

    @Test
    void aTaskInPlainWordsTheChecksRefuseIsLeftOutOfThePlanForTheRest() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(
                ScriptedLlm.text("{\"tasks\":[{\"email\":\"" + HelpdeskConnector.PRIYA + "\"},{\"email\":\""
                        + HelpdeskConnector.NOBODY + "\"}]}"),
                ScriptedLlm.text("1. Open a ticket for Priya's replacement laptop."),
                ScriptedLlm.text("Opened TICKET-1001.\nOUTCOME: done"));
        start(llm);

        say("Please replace the laptops of Priya and " + HelpdeskConnector.NOBODY + ".");

        String planned = llm.received().get(1).messages().stream().map(Message::text).reduce("", String::concat);
        assertThat(planned).contains("Checked before planning", "Priya Natarajan", "Refused before planning",
                HelpdeskConnector.NOBODY + ": Nobody in the directory has the email");
    }

    @Test
    void aRequestInPlainWordsIsCheckedWithTheFieldsTheModelReadsFromIt() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(
                ScriptedLlm.text("{\"tasks\":[{\"email\":\"" + HelpdeskConnector.NOBODY + "\"}]}"));
        start(llm);

        Turn turn = say("Please replace the laptop of " + HelpdeskConnector.NOBODY + ", it was stolen.");

        assertThat(turn.answer()).startsWith("Nothing was done. Nobody in the directory");
        assertThat(llm.received()).singleElement().satisfies(request -> {
            assertThat(request.outputSchema()).isPresent();
            assertThat(request.tools()).isEmpty();
        });
    }

    @Test
    void aQuestionHoldsNoTaskAndIsNotChecked() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(ScriptedLlm.text("{\"tasks\":[]}"),
                ScriptedLlm.text("ANSWER: I replace lost or broken laptops."));
        start(llm);

        Turn turn = say("What do you do?");

        assertThat(turn.answer()).isEqualTo("I replace lost or broken laptops.");
        assertThat(turn.steps()).noneMatch(s -> s.kind() == Step.Kind.TOOL_CALL);
    }

    private Turn say(String text) {
        Conversation conversation = runtime.store().create(PRIYA, "laptop", new Conversation.Pin("replacement",
                org.current().repo().version()));
        Turn turn = runtime.say(PRIYA, conversation.id(), text, List.of());
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Turn> now = runtime.store().turn(PRIYA, conversation.id(), turn.id());
            if (now.isPresent() && now.get().state().isTerminal()) {
                return now.get();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("The turn did not finish");
    }
}
