package dev.agentkit.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.agentkit.chat.ChatEvents;
import dev.agentkit.chat.ChatRuntime;
import dev.agentkit.chat.ChatUnavailable;
import dev.agentkit.chat.store.InMemoryChatStore;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The built console is in the jar, and the server serves it.
 *
 * <h2>Why this is a Java test and not a frontend one</h2>
 *
 * <p>Everything the frontend's own tooling can check — that it compiles, that its types line up
 * — it checks. What it cannot check is the seam: that {@code npm run build} produced files, that
 * Maven copied them where {@code ChatServer} looks, and that a request for {@code /} gets the
 * console rather than a 404. Each of those three has failed independently while the other two
 * were fine, and each is invisible from the side that caused it.
 *
 * <h2>Skipped only when nobody asked for a console</h2>
 *
 * <p>{@code -Dfrontend.skip=true} is a supported way to build this module — on a machine with no
 * network, or when iterating on the runtime rather than the page — and a test that failed there
 * would make the flag useless.
 *
 * <p><strong>So the skip is keyed on the flag, not on whether the file happens to exist.</strong>
 * The first version asked {@code getResource(UI + "/index.html") != null}, which is the same
 * question a broken build answers: pointing the resource copy at a directory nobody reads made
 * every test here <em>skip</em>, and the run was green. That is precisely the failure this class
 * exists to catch, and it was invisible to it. Measured by planting it.
 *
 * <p>Now: the console must be there unless the build was told not to make one, and being told
 * that is something only Maven can say.
 */
class TheConsoleIsBuiltIntoTheJarTest {

    /**
     * Where the page lives, which is under this module's package rather than at {@code /ui}.
     *
     * <p>A classpath is flat, and {@code agentkit-examples-workbench} ships a dashboard as
     * {@code ui/index.html}. A module depending on both got whichever the loader reached
     * first — and it was the dashboard, so the chat console served the wrong page on the right
     * port while every test here passed. Nothing on THIS module's classpath collides, which is
     * exactly why nothing here could have caught it.
     */
    private static final String UI = "/dev/agentkit/chat/ui";

    private final HttpClient http = HttpClient.newHttpClient();
    private ChatRuntime runtime;
    private ChatServer server;

    /** Whether this build was told not to make a console. Set by surefire from the pom. */
    private static boolean frontendWasSkipped() {
        return Boolean.parseBoolean(System.getProperty("frontend.skip", "false"));
    }

    @BeforeEach
    void start() throws IOException {
        assumeTrue(!frontendWasSkipped(), "-Dfrontend.skip=true: no console was asked for.");
        assertThat(TheConsoleIsBuiltIntoTheJarTest.class.getResource(UI + "/index.html"))
                .as("The frontend build was not skipped, so a console should be on the "
                        + "classpath under " + UI + ". It is not — npm run build produced "
                        + "nothing, or the copy into target/classes/ui is not happening.")
                .isNotNull();
        runtime = new ChatRuntime(new InMemoryChatStore(), new ChatEvents(), session -> {
            throw new ChatUnavailable("No model here; this test is about the page.");
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
        return http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theRootServesTheBuiltConsole() throws Exception {
        HttpResponse<String> page = get("/");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(header(page, "Content-Type")).contains("text/html");
        assertThat(page.body()).contains("<div id=\"root\">");
        // A built page, not the source one: Vite rewrites the module script to a hashed asset,
        // so this distinguishes "the console" from "index.html was copied verbatim".
        assertThat(page.body()).contains("/assets/").contains(".js");
        assertThat(page.body()).doesNotContain("/src/main.tsx");
    }

    @Test
    void theBuildStampIsSubstitutedIntoThePageAndMatchesTheApi() throws Exception {
        String page = get("/").body();

        assertThat(page).doesNotContain("%UI_BUILD%");
        assertThat(page).contains("content=\"" + server.buildStamp() + "\"");
        assertThat(get("/api/overview").body()).contains(server.buildStamp());
    }

    @Test
    void theAssetsAreServedWithTheirOwnTypesAndCachedForALongTime() throws Exception {
        String page = get("/").body();
        String script = between(page, "src=\"", "\"");
        String stylesheet = between(page, "href=\"", "\"");

        HttpResponse<String> js = get(script);
        assertThat(js.statusCode()).isEqualTo(200);
        assertThat(header(js, "Content-Type")).contains("text/javascript");
        // Hashed names, so a year is safe and a returning tab never gets last week's code.
        assertThat(header(js, "Cache-Control")).contains("max-age=31536000");

        HttpResponse<String> css = get(stylesheet);
        assertThat(css.statusCode()).isEqualTo(200);
        assertThat(header(css, "Content-Type")).contains("text/css");
    }

    @Test
    void thePageItselfIsNeverCached() throws Exception {
        // It carries the build stamp. A cached page would keep reporting the stamp of whichever
        // process first served it, which is exactly the staleness the stamp exists to catch.
        assertThat(header(get("/"), "Cache-Control")).contains("no-store");
    }

    @Test
    void aDeepLinkToAConversationServesThePageRatherThanAFourOhFour() throws Exception {
        // A single-page console owns its own routes. Without this, reloading /c/conv-3 is a
        // 404 and every link a person shares is broken.
        HttpResponse<String> deep = get("/c/conv-3");

        assertThat(deep.statusCode()).isEqualTo(200);
        assertThat(deep.body()).contains("<div id=\"root\">");
    }

    @Test
    void anAssetThatDoesNotExistIsAFourOhFourRatherThanThePage() throws Exception {
        // The extensionless rule serves the page; anything with an extension is an asset, and a
        // missing one must say so. Returning the page for /assets/gone.js would give a browser
        // HTML where it expected JavaScript, which fails much later and much less clearly.
        HttpResponse<String> missing = get("/assets/does-not-exist.js");

        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).doesNotContain("<div id=\"root\">");
    }

    @Test
    void thePageAndItsStylesheetFetchNothingFromAnotherOrigin() throws Exception {
        // The console has to work on a machine with a model endpoint and no internet, and a
        // strict Content-Security-Policy is coming in #356 — every external reference is
        // something it would then block. A stray webfont is the realistic way this breaks, and
        // it lands in the CSS rather than the HTML, so both are checked.
        //
        // The JavaScript bundle is deliberately NOT checked this way, and the reason is worth
        // writing down rather than discovering later: React's own bundle contains
        // `https://react.dev/errors/` in its error messages and `http://www.w3.org/2000/svg` as
        // the namespace it must pass to createElementNS. Those are strings, not loads, and a
        // grep for "https://" over the bundle would fail on them forever — which is how a
        // useful check gets deleted. Proving the bundle issues no cross-origin request needs a
        // browser, and that is #357's to build.
        String page = get("/").body();
        assertThat(page).doesNotContain("http://").doesNotContain("https://");
        assertThat(page).doesNotContain("//cdn.").doesNotContain("//fonts.");

        String stylesheet = get(between(page, "href=\"", "\"")).body();
        assertThat(stylesheet).doesNotContain("http://").doesNotContain("https://");
        assertThat(stylesheet).doesNotContain("@import url(//");
    }

    @Test
    void theJarCarriesOnlyTheAssetsThisBuildProduced() throws Exception {
        // Vite's filenames carry a content hash and maven-resources-plugin copies without
        // deleting, so an incremental build used to leave every asset every previous build had
        // produced on the classpath — measured at 616 kB for a 204 kB console, with two dead
        // bundles nothing referenced. Nothing is broken by that, which is why it would have
        // gone unnoticed until somebody looked inside the jar.
        String page = get("/").body();
        java.util.Set<String> referenced = java.util.Set.of(
                between(page, "src=\"", "\"").substring("/assets/".length()),
                between(page, "href=\"", "\"").substring("/assets/".length()));

        java.net.URL assets = ChatServer.class.getResource(UI + "/assets");
        assertThat(assets).as("the console's assets should be on the classpath").isNotNull();
        java.io.File[] onDisk = new java.io.File(assets.toURI()).listFiles();
        assertThat(onDisk).isNotNull();
        assertThat(java.util.Arrays.stream(onDisk).map(java.io.File::getName).toList())
                .as("a stale asset from an earlier build is still on the classpath")
                .containsExactlyInAnyOrderElementsOf(referenced);
    }

    @Test
    void theConsoleIsSmallEnoughToShipInAJar() throws Exception {
        // Not a performance budget — a smoke alarm. A console that suddenly triples has picked
        // up a dependency somebody meant to be a dev one, which is worth noticing at the point
        // it happens rather than when the jar is being downloaded.
        long bytes = 0;
        for (String asset : new String[] {"/", assetOf(get("/").body(), "src=\""),
                assetOf(get("/").body(), "href=\"")}) {
            bytes += get(asset).body().length();
        }

        assertThat(bytes).isLessThan(1_500_000);
    }

    private static String assetOf(String page, String attribute) {
        return between(page, attribute, "\"");
    }

    /**
     * One response header as a plain string.
     *
     * <p>Not {@code assertThat(response.headers().firstValue(name)).contains(...)}, which reads
     * like a substring check and is an equality check on the Optional's contents. It passed on
     * every header whose value happened to be exactly what was written and failed on the first
     * one carrying a charset — a test that is right by coincidence on five lines and wrong on
     * the sixth.
     */
    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name)
                .orElseThrow(() -> new AssertionError("No " + name + " header on the response"));
    }

    private static String between(String text, String after, String before) {
        int start = text.indexOf(after);
        if (start < 0) {
            throw new AssertionError("The page has no " + after + " in it: " + text);
        }
        start += after.length();
        return text.substring(start, text.indexOf(before, start));
    }

    /** Proves the assumption above is not silently skipping everything. */
    @Test
    void theConsoleResourceIsWhereChatServerLooksForIt() {
        try (InputStream in = ChatServer.class.getResourceAsStream(UI + "/index.html")) {
            assertThat(in).isNotNull();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
