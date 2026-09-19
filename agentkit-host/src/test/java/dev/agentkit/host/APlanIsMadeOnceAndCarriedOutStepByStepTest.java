package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.ChatEvent;
import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Step;
import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.llm.LlmRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plan-and-execute agent defined in the repository: its plan is made once — from the request, the policy and who is
 * asking — then carried out a step at a time, each step by a fresh agent with the same tools, bindings and
 * confirmations as a chat turn. The person sees the plan when it is made and each step as it starts, and the answer
 * says what came of every step, and where the plan stopped if it did.
 */
class APlanIsMadeOnceAndCarriedOutStepByStepTest {

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
                          policy: policy.md
                        tools:
                          - connector: helpdesk
                            effects: [read, request, notify, grant]
                        confirm: [helpdesk/reset_mfa]
                        bind:
                          helpdesk/open_ticket: {requester: principal.email}
                          helpdesk/reset_mfa: {email: principal.email}
                        """)
                .write("agents/replacement/planner.md", "You plan laptop replacements. Answer with a numbered list only.")
                .write("agents/replacement/executor.md", "You carry out one step of a laptop replacement.")
                .write("agents/replacement/policy.md", "Replacement policy: always tell the person's manager.");
        org = OrgHost.open(repo.root(), AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        HostChat chat = new HostChat(Map.of("acme", org), Optional.of(llm), () -> Instant.parse("2026-09-18T12:00:00Z"),
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
    void thePlanIsMadeWithThePolicyAndEachStepRunsWithTheAgentsToolsAsThePerson() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(
                ScriptedLlm.text("1. Open a ticket for a replacement laptop.\n2. Tell the manager, dana.kim@acme.example."),
                ScriptedLlm.toolUse("s1", "open_ticket", Map.of("summary", "Replacement laptop", "requester", "x@y.z")),
                ScriptedLlm.text("Opened TICKET-1001."),
                ScriptedLlm.toolUse("s2", "send_message", Map.of("to_email", HelpdeskConnector.DANA,
                        "text", "Priya's laptop is being replaced: TICKET-1001.")),
                ScriptedLlm.text("Told Dana."));
        start(llm);

        Conversation conversation = runtime.store().create(PRIYA, "laptop", new Conversation.Pin("replacement",
                org.current().repo().version()));
        Turn turn = say(conversation, "My laptop was stolen.");

        assertThat(turn.state()).isEqualTo(Turn.State.COMPLETED);
        assertThat(turn.answer()).isEqualTo("""
                1. Open a ticket for a replacement laptop.
                   - Done: Opened TICKET-1001.
                2. Tell the manager, dana.kim@acme.example.
                   - Done: Told Dana.""");
        assertThat(helpdesk.calls("open_ticket")).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("requester", HelpdeskConnector.PRIYA));
        assertThat(helpdesk.calls("send_message")).hasSize(1);

        // The plan, on the record and in front of the person; each step announced as it started.
        assertThat(turn.steps()).filteredOn(s -> s.kind() == Step.Kind.NOTE)
                .singleElement().satisfies(s -> assertThat(s.name()).isEqualTo("plan"));
        assertThat(turn.views()).singleElement().satisfies(v -> assertThat(v.data().toString())
                .contains("1. Open a ticket for a replacement laptop."));
        String streamed = runtime.events().since(conversation.id(), 0).stream()
                .filter(e -> e.type() == ChatEvent.Type.TEXT_DELTA)
                .map(e -> String.valueOf(e.data().get("text"))).collect(Collectors.joining());
        assertThat(streamed).contains("**Step 1 of 2:** Open a ticket").contains("**Step 2 of 2:** Tell the manager");

        // The policy is settled while planning; the steps run on the executor's prompt, as the person.
        List<LlmRequest> requests = llm.received();
        assertThat(requests.get(0).system()).hasValueSatisfying(system -> assertThat(system)
                .startsWith("You plan laptop replacements.").contains("Replacement policy")
                .contains("- email: " + HelpdeskConnector.PRIYA));
        assertThat(requests.get(0).tools()).isEmpty();
        assertThat(requests.get(1).system()).hasValueSatisfying(system -> assertThat(system)
                .startsWith("You carry out one step").doesNotContain("Replacement policy"));
        assertThat(requests.get(1).tools()).extracting(t -> t.name()).contains("open_ticket", "reset_mfa")
                .doesNotContain("delete_account");
    }

    @Test
    void aQuestionIsAnsweredWithoutAPlanAndNothingIsCarriedOut() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(ScriptedLlm.text("ANSWER: I opened **TICKET-1001** for your laptop earlier."));
        start(llm);

        Conversation conversation = runtime.store().create(PRIYA, "laptop", new Conversation.Pin("replacement",
                org.current().repo().version()));
        Turn turn = say(conversation, "Give me a summary of what you did");

        assertThat(turn.state()).isEqualTo(Turn.State.COMPLETED);
        assertThat(turn.answer()).isEqualTo("I opened **TICKET-1001** for your laptop earlier.");
        assertThat(turn.views()).as("no plan shown").isEmpty();
        assertThat(llm.received()).hasSize(1);
        assertThat(llm.received().get(0).system()).hasValueSatisfying(system -> assertThat(system)
                .contains("do not plan. Reply starting with \"ANSWER:\""));
        assertThat(helpdesk.calls("send_message")).isEmpty();
    }

    @Test
    void aStepWhoseChangeFailedIsNotCalledDoneThoughItsAgentFinished() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(
                ScriptedLlm.text("1. Look up nobody@acme.example.\n2. Tell nobody@acme.example it is replaced."),
                // A lookup that finds no one is not a failure of the step: it only read.
                ScriptedLlm.toolUse("s1", "directory_lookup", Map.of("email", HelpdeskConnector.NOBODY)),
                ScriptedLlm.text("They are not in the directory."),
                ScriptedLlm.toolUse("s2", "send_message", Map.of("to_email", HelpdeskConnector.NOBODY, "text", "Hi")),
                ScriptedLlm.text("I tried to message them."));
        start(llm);

        Conversation conversation = runtime.store().create(PRIYA, "laptop", new Conversation.Pin("replacement",
                org.current().repo().version()));
        Turn turn = say(conversation, "Tell nobody their laptop is replaced.");

        assertThat(turn.answer()).isEqualTo("""
                1. Look up nobody@acme.example.
                   - Done: They are not in the directory.
                2. Tell nobody@acme.example it is replaced.
                   - Failed (send_message): I tried to message them.""");
    }

    @Test
    void aStepThatDoesNotFinishStopsThePlanAndTheAnswerSaysWhere() throws Exception {
        // The planner answers; the first step's model then fails, and nothing after it runs.
        ScriptedLlm llm = new ScriptedLlm(ScriptedLlm.text("1. Open a ticket.\n2. Tell the manager."));
        start(llm);

        Conversation conversation = runtime.store().create(PRIYA, "laptop", new Conversation.Pin("replacement",
                org.current().repo().version()));
        Turn turn = say(conversation, "My laptop was stolen.");

        assertThat(turn.state()).isEqualTo(Turn.State.FAILED);
        assertThat(turn.answer()).contains("1. Open a ticket.\n   - Stopped (error)")
                .contains("2. Tell the manager.\n   - Not started.");
        // The host looked Priya up; no step touched the helpdesk.
        assertThat(helpdesk.calls).extracting(HelpdeskConnector.Call::tool).containsOnly("directory_lookup");
    }

    @Test
    void aPlanLongerThanATurnMayCarryOutIsRefusedBeforeAnythingRuns() throws Exception {
        String longPlan = java.util.stream.IntStream.rangeClosed(1, PlanExecuteTurn.MAX_PLAN_STEPS + 1)
                .mapToObj(i -> i + ". Step " + i).collect(Collectors.joining("\n"));
        start(new ScriptedLlm(ScriptedLlm.text(longPlan)));

        Conversation conversation = runtime.store().create(PRIYA, "laptop", new Conversation.Pin("replacement",
                org.current().repo().version()));
        Turn turn = say(conversation, "Do everything.");

        assertThat(turn.state()).isEqualTo(Turn.State.FAILED);
        assertThat(turn.answer()).isEqualTo("The plan had 21 steps, more than the 20 a turn may carry out.");
        // The host looked Priya up; no step touched the helpdesk.
        assertThat(helpdesk.calls).extracting(HelpdeskConnector.Call::tool).containsOnly("directory_lookup");
    }

    private Turn say(Conversation conversation, String text) throws InterruptedException {
        Turn turn = runtime.say(conversation.tenantId(), conversation.id(), text, List.of());
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Turn> now = runtime.store().turn(conversation.tenantId(), conversation.id(), turn.id());
            if (now.isPresent() && now.get().state().isTerminal()) {
                return now.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("The turn did not finish");
    }
}
