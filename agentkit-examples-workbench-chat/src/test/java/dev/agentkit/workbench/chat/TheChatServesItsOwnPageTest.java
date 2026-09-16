package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.ChatUnavailable;
import dev.agentkit.chat.store.InMemoryChatStore;
import dev.agentkit.chat.web.ChatServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The console serves its own page, and this is the only module that can find out.
 *
 * <h2>What happened</h2>
 *
 * <p>A classpath is flat and a resource lookup is last-wins. {@code agentkit-chat} put its
 * built console at {@code ui/index.html}; {@code agentkit-examples-workbench} has shipped its
 * dashboard at {@code ui/index.html} since long before any of this; and this module depends on
 * both. {@code ChatServer.class.getResourceAsStream("/ui/index.html")} returned whichever jar
 * the loader reached first, and it returned <strong>the dashboard</strong> — so the chat
 * console served the dashboard's page, on the chat's port, with the chat's API behind it.
 *
 * <p><strong>Every test in the repository passed.</strong> {@code agentkit-chat}'s own
 * {@code TheConsoleIsBuiltIntoTheJarTest} checks the page thoroughly and runs on a classpath
 * where only one {@code ui/} exists; the same is true of the dashboard's tests. The collision
 * exists only in the third module that depends on both, and nothing there was looking. It was
 * found by opening the console in a browser and seeing the wrong product.
 *
 * <p>The page moved under {@code dev/agentkit/chat/ui}. This is the test that says so from the
 * only place where saying so means anything.
 */
class TheChatServesItsOwnPageTest {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private ChatRuntime runtime;
    private ChatServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        if (runtime != null) {
            runtime.close();
        }
    }

    private String page() throws Exception {
        InMemoryChatStore store = new InMemoryChatStore();
        runtime = new ChatRuntime(store, new ChatEvents(), session -> {
            throw new ChatUnavailable("No model here.");
        });
        server = new ChatServer(0, runtime, Console.TENANT, Map::of);
        server.start();
        return http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void thePageOnTheChatsPortIsTheChatsPage() throws Exception {
        String served = page();

        // What the React console's index.html is: a stamp, one module script, one stylesheet,
        // and an empty root. Vite emits it and nothing else does.
        assertThat(served).contains("id=\"root\"");
        assertThat(served).contains("<script type=\"module\"");
        assertThat(served).contains("name=\"ui-build\"");
        // And what the dashboard's is: a single self-contained file with its own panels in it.
        // Naming them is the point — an assertion that only said "it is the chat's" would pass
        // against an empty file.
        assertThat(served)
                .as("this is agentkit-examples-workbench's dashboard, served on the chat's port")
                .doesNotContain("WAITING ON YOU")
                .doesNotContain("Select a ticket from the inbox");
    }

    @Test
    void itIsTheSmallOneRatherThanTheSelfContainedOne() throws Exception {
        // The tell that made it obvious over curl before anything else did: the dashboard is
        // one file with its whole application inside it, and the console's page is a stub that
        // points at an asset. Two orders of magnitude apart.
        assertThat(page().length())
                .as("a page this size is somebody's whole single-file app, not a Vite stub")
                .isLessThan(4_000);
    }

    @Test
    void bothPagesAreOnThisClasspathWhichIsWhyThisTestExists() {
        // The precondition. If a later change made these stop colliding — the dashboard moving
        // its own resources, say — this test would keep passing while measuring nothing, and
        // somebody should be told that rather than left with a green vacuous check.
        assertThat(ChatServer.class.getResource("/ui/index.html"))
                .as("the dashboard's page is no longer on this classpath, so the collision "
                        + "this test guards against cannot happen and the guard is now empty")
                .isNotNull();
        assertThat(ChatServer.class.getResource("/dev/agentkit/chat/ui/index.html"))
                .as("the console's own page is missing; run without -Dfrontend.skip")
                .isNotNull();
    }
}
