package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.agentkit.host.auth.FakeIdentityProvider;
import dev.agentkit.host.auth.Oidc;
import dev.agentkit.host.auth.OidcSignIn;
import dev.agentkit.host.auth.SessionStore;
import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two instances of the host behind one address share where sessions are kept: a sign-in begun on one finishes on
 * the other, the person is signed in on both, and signing out on either signs them out of both.
 */
class APersonSignedInOnOneInstanceIsSignedInOnAnotherTest {

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private FakeIdentityProvider idp;
    private OrgHost org;
    private final List<HttpServer> instances = new ArrayList<>();

    @AfterEach
    void stop() {
        instances.forEach(server -> server.stop(0));
        if (org != null) {
            org.close();
        }
        if (idp != null) {
            idp.close();
        }
        if (helpdesk != null) {
            helpdesk.close();
        }
    }

    @Test
    void aSignInBegunOnOneInstanceFinishesOnAnotherAndHoldsOnBoth() throws Exception {
        helpdesk = new HelpdeskConnector();
        idp = new FakeIdentityProvider(0, "agentkit-host", null);
        RepoFixture repo = RepoFixture.copyInto(dir);
        repo.write("org.yaml", repo.read("org.yaml") + "\nsignIn:\n  issuer: " + idp.issuer() + "\n  clientId: agentkit-host\n");
        org = OrgHost.open(repo.root(), AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
        Oidc provider = new Oidc(org.current().repo().signIn().orElseThrow(), Optional.empty());
        HttpServer a = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer b = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The provider sends people back to the host's one public address, which here is instance a.
        URI publicUrl = URI.create("http://127.0.0.1:" + a.getAddress().getPort());
        SessionStore shared = SessionStore.inMemory();
        for (HttpServer server : List.of(a, b)) {
            OidcSignIn signIn = new OidcSignIn(Map.of("acme", org), o -> Optional.of(provider), publicUrl, Instant::now,
                    shared);
            server.createContext("/sign-in", signIn);
            server.createContext("/sign-out", signIn);
            server.createContext("/", exchange -> {
                byte[] who = signIn.of(exchange).orElse("nobody").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, who.length);
                exchange.getResponseBody().write(who);
                exchange.close();
            });
            server.start();
            instances.add(server);
        }
        idp.signInAs(HelpdeskConnector.PRIYA);
        HttpClient browser = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NORMAL).build();

        HttpResponse<String> landed = get(browser, url(b, "/sign-in?org=acme"));

        assertThat(landed.uri().getPort()).as("finished on a").isEqualTo(a.getAddress().getPort());
        assertThat(landed.body()).isEqualTo("acme/" + HelpdeskConnector.PRIYA);
        assertThat(get(browser, url(b, "/")).body()).as("signed in on b too").isEqualTo("acme/" + HelpdeskConnector.PRIYA);

        get(browser, url(b, "/sign-out"));
        assertThat(get(browser, url(a, "/")).body()).isEqualTo("nobody");
    }

    private static String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static HttpResponse<String> get(HttpClient browser, String url) throws Exception {
        return browser.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
