package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Step;
import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.host.plans.PlanBook;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plan-execute agent started from its form plans each task with the model until the plans for a kind of task
 * settle; the next task of that kind is carried out on the settled plan, with its own values, and no planning call —
 * each step still by the model, as the person. A task of another kind, or one with anything else said, is planned
 * afresh.
 */
class ASettledPlanIsReusedForTheNextTaskLikeItTest {

    private static final String PRIYA = new Tenant("acme", HelpdeskConnector.PRIYA).id();
    private static final Pattern RECIPIENT = Pattern.compile("recipient: (.+)");
    private static final Pattern LAPTOP = Pattern.compile("laptop: (.+)");

    @TempDir
    Path dir;

    private final AtomicInteger plansAsked = new AtomicInteger();
    private final List<String> planRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicInteger stepsRun = new AtomicInteger();
    private final PlanBook book = PlanBook.inMemory();
    private HelpdeskConnector helpdesk;
    private OrgHost org;
    private HostChat chat;
    private ChatRuntime runtime;

    /** Plans from the request it is given; carries out any step by saying it did. */
    private final LlmClient model = request -> {
        String system = request.system().orElse("");
        String text;
        if (system.startsWith("You plan")) {
            plansAsked.incrementAndGet();
            String asked = request.messages().stream().map(Message::text).reduce("", String::concat);
            planRequests.add(asked);
            Matcher recipient = RECIPIENT.matcher(asked);
            Matcher laptop = LAPTOP.matcher(asked);
            recipient.find();
            laptop.find();
            text = "1. Order a " + laptop.group(1).strip() + " for " + recipient.group(1).strip() + ".\n"
                    + "2. Tell " + HelpdeskConnector.PRIYA + " it is on its way to " + recipient.group(1).strip() + ".";
        } else {
            stepsRun.incrementAndGet();
            text = "Done.";
        }
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)), LlmStopReason.END_TURN,
                new TokenUsage(10, 10));
    };

    @BeforeEach
    void start() throws Exception {
        helpdesk = new HelpdeskConnector();
        RepoFixture repo = RepoFixture.copyInto(dir)
                .write("agents/replacement/agent.yaml", """
                        name: Laptop Replacement
                        description: Replaces a laptop end to end.
                        pattern: plan-execute
                        prompt: {planner: planner.md, executor: executor.md}
                        tools:
                          - connector: helpdesk
                            effects: [read, request, notify]
                        input: {schema: input.yaml, goal: goal.md}
                        plans:
                          reuse: {after: 2, recheckEvery: 10}
                        """)
                .write("agents/replacement/planner.md", "You plan laptop replacements. Answer with a numbered list only.")
                .write("agents/replacement/executor.md", "You carry out one step of a laptop replacement.")
                .write("agents/replacement/goal.md", "Replace a laptop.\n{{input}}")
                .write("agents/replacement/input.yaml", """
                        type: object
                        required: [recipient, laptop]
                        properties:
                          recipient: {type: string, title: Who it is for}
                          laptop: {type: string, enum: [mac, pc]}
                        """);
        repo.commit("the replacement desk");
        org = OrgHost.open(repo.root(), AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        chat = new HostChat(Map.of("acme", org), Map.of(),
                dev.agentkit.host.models.ModelAccounts.shared(Optional.of(model)), book, Instant::now, self::get);
        runtime = new ChatRuntime(new InMemoryChatStore(), new ChatEvents(), chat);
        self.set(runtime);
    }

    @AfterEach
    void stop() {
        runtime.close();
        org.close();
        helpdesk.close();
    }

    @Test
    void theThirdTaskLikeTheFirstTwoIsCarriedOutOnTheirPlan() {
        assertThat(fill("Marcus Bell", "mac").answer()).startsWith("1. Order a mac for Marcus Bell.");
        assertThat(fill("Ravi Menon", "mac").answer()).startsWith("1. Order a mac for Ravi Menon.");
        assertThat(plansAsked.get()).isEqualTo(2);
        assertThat(planRequests.get(0)).doesNotContain("planned before");
        // The second is shown the first, with its own values, so that alike tasks are worded alike.
        assertThat(planRequests.get(1)).contains("Tasks like this one have been planned before",
                "1. Order a mac for Ravi Menon.").doesNotContain("Marcus Bell");

        Turn lena = fill("Lena Ortiz", "mac");

        assertThat(plansAsked.get()).as("no planning call").isEqualTo(2);
        assertThat(lena.state()).isEqualTo(Turn.State.COMPLETED);
        assertThat(lena.answer()).isEqualTo("""
                1. Order a mac for Lena Ortiz.
                   - Done: Done.
                2. Tell priya.natarajan@acme.example it is on its way to Lena Ortiz.
                   - Done: Done.""");
        assertThat(stepsRun.get()).as("each step still carried out by the model").isEqualTo(6);
        assertThat(lena.views()).singleElement().satisfies(v -> assertThat(v.data().toString())
                .contains("(reused: the last 2 plans for tasks like this one agreed on it"));
        assertThat(lena.steps()).filteredOn(s -> s.kind() == Step.Kind.NOTE).singleElement()
                .satisfies(s -> assertThat(s.detail()).containsEntry("reused", true));

        fill("Sam Okafor", "pc");
        assertThat(plansAsked.get()).as("another kind of task").isEqualTo(3);

        Conversation conversation = runtime.store().create(PRIYA, "said", chat.pin(PRIYA, "replacement"));
        chat.message(PRIYA, conversation, Map.of("recipient", "Dana Kim", "laptop", "mac"));
        say(conversation, "Replace a laptop.\n- recipient: Dana Kim\n- laptop: mac\nAnd a bag, please.");
        assertThat(plansAsked.get()).as("the form's task and something else said").isEqualTo(4);
    }

    @Test
    void theFormFilledInAndPastedIsATaskLikeAnyOther() {
        fill("Marcus Bell", "mac");
        fill("Ravi Menon", "mac");
        assertThat(plansAsked.get()).isEqualTo(2);

        Conversation conversation = runtime.store().create(PRIYA, "pasted", chat.pin(PRIYA, "replacement"));
        Turn pasted = say(conversation, "Please replace this one:\nrecipient: Lena Ortiz\nlaptop: mac");

        assertThat(plansAsked.get()).as("the settled plan, with no planning call").isEqualTo(2);
        assertThat(pasted.answer()).startsWith("1. Order a mac for Lena Ortiz.");

        // A second message in the same conversation, with the first before it, is still the form's task.
        Turn again = say(conversation, "recipient: Jo Park\nlaptop: mac");
        assertThat(plansAsked.get()).isEqualTo(2);
        assertThat(again.answer()).startsWith("1. Order a mac for Jo Park.");
    }

    private Turn fill(String recipient, String laptop) {
        Conversation conversation = runtime.store().create(PRIYA, recipient, chat.pin(PRIYA, "replacement"));
        String request = chat.message(PRIYA, conversation, Map.of("recipient", recipient, "laptop", laptop));
        return say(conversation, request);
    }

    private Turn say(Conversation conversation, String text) {
        Turn turn = runtime.say(PRIYA, conversation.id(), text, List.of());
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Turn> done = runtime.store().turn(PRIYA, conversation.id(), turn.id());
            if (done.isPresent() && done.get().state().isTerminal()) {
                return done.get();
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
