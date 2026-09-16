package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.Turn;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.workbench.WorkbenchApp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A console with something missing boots, says which variable, and says it in the one place a
 * person is looking.
 *
 * <p>The alternative — refusing to start — leaves them with a stack trace in a terminal and
 * six environment variables to guess between. The workbench dashboard already degrades this way and
 * the chat has to match it, because the two are configured from the same shell and a person
 * who got one wrong got both wrong.
 *
 * <p>The sentence has to reach two places, and the second is the one that gets forgotten: the
 * banner on the page, <em>and</em> the answer to whatever they typed before reading it.
 */
class TheConsoleBootsAndSaysWhatIsMissingTest {

    private ChatRuntime runtime;

    @AfterEach
    void stop() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void noJiraAndNoModelNamesBothRatherThanTheFirstOne() {
        List<String> problems = WorkbenchChatApp.problemsWith(Optional.empty(), Optional.empty());

        assertThat(problems).hasSize(2);
        assertThat(String.join(" ", problems))
                .contains("JIRA_BASE_URL", "JIRA_EMAIL", "JIRA_API_TOKEN")
                .contains("WORKBENCH_LLM", "ANTHROPIC_API_KEY", "OPENROUTER_API_KEY", "WORKBENCH_MODEL");
    }

    @Test
    void aModelWithNoJiraSaysSoAndOnlySo() {
        List<String> problems = WorkbenchChatApp.problemsWith(Optional.empty(),
                Optional.of(new WorkbenchApp.Backend(request -> null, "some-model")));

        assertThat(problems).singleElement()
                .satisfies(problem -> assertThat(problem).contains("JIRA_BASE_URL"));
    }

    @Test
    void theOverviewSaysReadyOnlyWhenNothingIsMissing() {
        Map<String, Object> broken = WorkbenchChatApp.overview(null, Optional.empty(),
                WorkbenchChatApp.problemsWith(Optional.empty(), Optional.empty()));
        assertThat(broken.get("ready")).isEqualTo(false);
        assertThat(broken.get("model")).isEqualTo("");
        // The console's page disables the composer when `problems` is non-empty, so this key
        // is not decoration — it is what stops a person typing into a console that cannot
        // answer, and it is what puts the sentence in front of them.
        assertThat((List<?>) broken.get("problems")).isNotEmpty();

        ConsoleAlm alm = new ConsoleAlm();
        Map<String, Object> working = WorkbenchChatApp.overview(alm,
                Optional.of(new WorkbenchApp.Backend(request -> null, "some-model")), List.of());
        assertThat(working.get("ready")).isEqualTo(true);
        assertThat(working.get("model")).isEqualTo("some-model");
        assertThat(working.get("alm")).isEqualTo("jira");
        assertThat((List<?>) working.get("problems")).isEmpty();
    }

    @Test
    void aTurnComesBackWithTheSameSentenceRatherThanSomethingWentWrong() throws Exception {
        List<String> problems = WorkbenchChatApp.problemsWith(Optional.empty(), Optional.empty());
        InMemoryChatStore store = new InMemoryChatStore();
        // The production factory rather than a lambda shaped like it. A test that rewrites the
        // branch it is checking passes whatever the branch does.
        java.util.concurrent.atomic.AtomicReference<ChatRuntime> self =
                new java.util.concurrent.atomic.AtomicReference<>();
        runtime = new ChatRuntime(store, new ChatEvents(),
                WorkbenchChatApp.agents(problems, self, Optional.empty(),
                        new WorkbenchChatApp.Wiring(
                                new ConsoleTools.Deployment("default", "operator", ""),
                                new ConsoleAlm(), null, null, null, null, null)));
        self.set(runtime);
        Conversation conversation = store.create("default", "");

        Turn asked = runtime.say("default", conversation.id(), "what is in my inbox?", List.of());
        Turn ended = await(store, conversation.id(), asked.id());

        assertThat(ended.state()).isEqualTo(Turn.State.FAILED);
        assertThat(ended.detail())
                .contains("JIRA_BASE_URL")
                .contains("WORKBENCH_LLM")
                .doesNotContain("Exception");
    }

    @Test
    void aMistypedPortIsTheDefaultAndASentenceRatherThanAStackTrace() {
        assertThat(WorkbenchChatApp.port(null, 8083, "WORKBENCH_CHAT_PORT")).isEqualTo(8083);
        assertThat(WorkbenchChatApp.port("  ", 8083, "WORKBENCH_CHAT_PORT")).isEqualTo(8083);
        assertThat(WorkbenchChatApp.port("9001", 8083, "WORKBENCH_CHAT_PORT")).isEqualTo(9001);
        assertThat(WorkbenchChatApp.port(" 9001 ", 8083, "WORKBENCH_CHAT_PORT")).isEqualTo(9001);
        // The console's whole posture is that a slip is stated, not fatal. An unguarded parse
        // answers this with a NumberFormatException and no console at all — which is the one
        // failure the class javadoc promises it does not have.
        assertThat(WorkbenchChatApp.port("808o", 8083, "WORKBENCH_CHAT_PORT")).isEqualTo(8083);
        assertThat(WorkbenchChatApp.port("70000", 8083, "WORKBENCH_CHAT_PORT")).isEqualTo(8083);
        assertThat(WorkbenchChatApp.port("-1", 8083, "WORKBENCH_CHAT_PORT")).isEqualTo(8083);
        // And the dashboard's port falls back to the dashboard's default, not the chat's — one
        // shared helper with one hardcoded fallback would have put the dashboard on 8083 and
        // silently collided the two servers.
        assertThat(WorkbenchChatApp.port("nope", 8082, "WORKBENCH_PORT")).isEqualTo(8082);
    }

    @Test
    void theSecondFrontDoorNotOpeningDoesNotTakeTheFirstOneWithIt() throws Exception {
        Console console = new Console(new ConsoleAlm(
                ConsoleAlm.open("IT-1", "Grant access", "Add me.")));

        // Both consoles on one port is a person's slip, and the chat is what this module is
        // for — so the dashboard is the one that gives way, out loud.
        assertThat(dashboardOn(console, 8083, 8083))
                .as("two consoles cannot share a port")
                .isNull();

        // And a port somebody else already has is the same answer rather than a stack trace
        // and no console at all. An unguarded constructor throws BindException out of main.
        try (java.net.ServerSocket taken = new java.net.ServerSocket(0)) {
            assertThat(dashboardOn(console, 8083, taken.getLocalPort()))
                    .as("a busy port must not take the chat down with it")
                    .isNull();
        }

        // On a free one it is there, over the very store the chat is holding — which is the
        // whole point of starting it here rather than in a second process.
        dev.agentkit.workbench.web.WebServer both = dashboardOn(console, 8083, 0);
        assertThat(both).isNotNull();
        both.close();
    }

    private static dev.agentkit.workbench.web.WebServer dashboardOn(Console console, int chatPort,
            int dashboardPort) {
        return WorkbenchChatApp.dashboardOver(chatPort, dashboardPort, console.store, "default",
                Optional.empty(), console.alm, console.workbench, null, console.learnings, null);
    }

    private static Turn await(InMemoryChatStore store, String conversationId, String turnId)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Turn turn = store.turn("default", conversationId, turnId).orElseThrow();
            if (turn.state().isTerminal()) {
                return turn;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the turn never ended");
    }
}
