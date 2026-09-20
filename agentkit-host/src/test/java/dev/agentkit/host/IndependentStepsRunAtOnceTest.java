package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ToolUseBlock;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The planner says what each step needs; steps that need nothing of each other are carried out at once, a step waits
 * for the ones it needs and is given their results, and a plan that says nothing is carried out in order. Each step's
 * failed changes are its own, however the steps' calls interleave.
 */
class IndependentStepsRunAtOnceTest {

    private static final String PRIYA = new Tenant("acme", HelpdeskConnector.PRIYA).id();

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private OrgHost org;
    private ChatRuntime runtime;
    private final Map<String, LlmRequest> stepRequests = new ConcurrentHashMap<>();

    /** A model whose planner says {@code plan}, and whose step agents answer as {@code step} says for each step. */
    private void start(String plan, Function<String, LlmResponse> step) throws Exception {
        helpdesk = new HelpdeskConnector();
        RepoFixture repo = RepoFixture.copyInto(dir)
                .write("agents/replacement/agent.yaml", """
                        name: Laptop Replacement
                        description: Replaces a lost or broken laptop end to end.
                        pattern: plan-execute
                        prompt: {planner: planner.md, executor: executor.md}
                        tools:
                          - connector: helpdesk
                            effects: [read, request, notify]
                        """)
                .write("agents/replacement/planner.md", "You plan laptop replacements. Answer with a numbered list only.")
                .write("agents/replacement/executor.md", "You carry out one step of a laptop replacement.");
        org = OrgHost.open(repo.root(), AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
        LlmClient model = request -> {
            if (request.system().orElse("").startsWith("You plan")) {
                return reply(plan);
            }
            String asked = request.messages().stream().map(Message::text).reduce("", String::concat);
            String current = asked.substring(asked.lastIndexOf("Complete step"));
            boolean afterTool = request.messages().size() > 1;
            String name = current.contains("Order") ? "order" : current.contains("Message") ? "message"
                    : current.contains("Tell") ? "tell" : "other";
            stepRequests.putIfAbsent(name, request);
            return afterTool ? reply("Done.\nOUTCOME: done") : step.apply(name);
        };
        AtomicReference<ChatRuntime> self = new AtomicReference<>();
        HostChat chat = new HostChat(Map.of("acme", org), Optional.of(model), Instant::now, self::get);
        runtime = new ChatRuntime(new InMemoryChatStore(), new ChatEvents(), chat);
        self.set(runtime);
    }

    private static LlmResponse reply(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)), LlmStopReason.END_TURN,
                new TokenUsage(10, 10));
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
    void stepsThatNeedNothingOfEachOtherRunAtOnceAndTheOneThatNeedsThemWaits() throws Exception {
        // Each of the first two waits until the other has started: carried out one after the other, they would not.
        CountDownLatch bothStarted = new CountDownLatch(2);
        start("""
                1. [needs: none] Order a laptop.
                2. [needs: none] Message the service desk.
                3. [needs: 1, 2] Tell Priya.""", name -> {
            if (!name.equals("tell")) {
                bothStarted.countDown();
                try {
                    if (!bothStarted.await(5, TimeUnit.SECONDS)) {
                        return reply("Waited alone.\nOUTCOME: not done");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return reply((name.equals("order") ? "Ordered ORD-7." : name.equals("message") ? "Messaged." : "Told.")
                    + "\nOUTCOME: done");
        });

        Turn turn = say("My laptop was stolen.");

        assertThat(turn.answer()).isEqualTo("""
                1. Order a laptop.
                   - Done: Ordered ORD-7.
                2. Message the service desk.
                   - Done: Messaged.
                3. Tell Priya.
                   - Done: Told.""");
        // The step that needs the first two is given what they said; the plan is shown without the marks.
        String tell = stepRequests.get("tell").messages().stream().map(Message::text).reduce("", String::concat);
        assertThat(tell).contains("Ordered ORD-7.", "Messaged.");
        assertThat(turn.views()).singleElement().satisfies(v -> assertThat(v.data().toString())
                .contains("1. Order a laptop.").doesNotContain("[needs"));
    }

    @Test
    void aStepThatDoesNotFinishStartsNothingThatNeedsIt() throws Exception {
        start("""
                1. [needs: none] Order a laptop.
                2. [needs: 1] Tell Priya.""", name -> {
            throw new IllegalStateException("The model is unavailable.");
        });

        Turn turn = say("My laptop was stolen.");

        assertThat(turn.answer()).startsWith("1. Order a laptop.\n   - Stopped (error)").endsWith("""
                2. Tell Priya.
                   - Not started.""");
    }

    @Test
    void eachStepsFailedChangesAreItsOwnThoughTheyRunAtOnce() throws Exception {
        start("""
                1. [needs: none] Message nobody@acme.example.
                2. [needs: none] Order a laptop.""", name -> name.equals("message")
                ? toolUse("send_message", Map.of("to_email", HelpdeskConnector.NOBODY, "text", "Hi"))
                : toolUse("directory_lookup", Map.of("email", HelpdeskConnector.NOBODY)));

        Turn turn = say("My laptop was stolen.");

        assertThat(turn.answer()).isEqualTo("""
                1. Message nobody@acme.example.
                   - Failed (send_message): Done.
                2. Order a laptop.
                   - Done: Done.""");
    }

    private static LlmResponse toolUse(String tool, Map<String, Object> arguments) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, ToolUseBlock.of("t-" + tool, tool, arguments)),
                LlmStopReason.TOOL_USE, new TokenUsage(10, 10));
    }

    private Turn say(String text) {
        Conversation conversation = runtime.store().create(PRIYA, "laptop", new Conversation.Pin("replacement",
                org.current().repo().version()));
        Turn turn = runtime.say(PRIYA, conversation.id(), text, List.of());
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
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
