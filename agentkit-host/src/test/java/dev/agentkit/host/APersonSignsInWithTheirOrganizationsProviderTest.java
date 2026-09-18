package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.agentkit.host.auth.FakeIdentityProvider;
import dev.agentkit.host.auth.FakeIdentityProvider.Misbehaviour;
import dev.agentkit.host.auth.Oidc;
import dev.agentkit.host.auth.OidcSignIn;
import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A person signs in to the console with their organization's identity provider — OpenID Connect's code flow, with
 * PKCE, a state and a nonce — and is who its ID token says only when the token checks out and the directory knows them.
 */
class APersonSignsInWithTheirOrganizationsProviderTest {

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private FakeIdentityProvider idp;
    private OrgHost org;
    private HttpServer host;
    private OidcSignIn signIn;

    private void start(String clientSecret) throws Exception {
        helpdesk = new HelpdeskConnector();
        idp = new FakeIdentityProvider(0, "agentkit-host", clientSecret);
        RepoFixture repo = RepoFixture.copyInto(dir);
        repo.write("org.yaml", repo.read("org.yaml") + "\nsignIn:\n  issuer: " + idp.issuer() + "\n  clientId: agentkit-host\n");
        org = OrgHost.open(repo.root(), AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
        host = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        URI publicUrl = URI.create("http://127.0.0.1:" + host.getAddress().getPort());
        Oidc provider = new Oidc(org.current().repo().signIn().orElseThrow(), Optional.ofNullable(clientSecret));
        signIn = new OidcSignIn(Map.of("acme", org), o -> Optional.of(provider), publicUrl, Instant::now);
        host.createContext("/sign-in", signIn);
        host.createContext("/sign-out", signIn);
        host.createContext("/", exchange -> {
            byte[] who = signIn.of(exchange).orElse("nobody").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, who.length);
            exchange.getResponseBody().write(who);
            exchange.close();
        });
        host.start();
    }

    @AfterEach
    void stop() {
        if (host != null) {
            host.stop(0);
        }
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

    private String url(String path) {
        return "http://127.0.0.1:" + host.getAddress().getPort() + path;
    }

    private static HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    private static HttpResponse<String> get(HttpClient browser, String url) throws Exception {
        return browser.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aPersonTheDirectoryKnowsIsSignedInAndThenOut() throws Exception {
        start(null);
        idp.signInAs(HelpdeskConnector.PRIYA);
        HttpClient browser = browser();

        HttpResponse<String> landed = get(browser, url("/sign-in?org=acme"));

        assertThat(landed.uri().getPath()).isEqualTo("/");
        assertThat(landed.body()).isEqualTo("acme/" + HelpdeskConnector.PRIYA);
        assertThat(get(browser(), url("/")).body()).isEqualTo("nobody");

        assertThat(get(browser, url("/sign-out")).body()).contains("You are signed out.");
        assertThat(get(browser, url("/")).body()).isEqualTo("nobody");
    }

    @Test
    void aConfidentialClientSendsItsSecret() throws Exception {
        start("s3cret");
        idp.signInAs(HelpdeskConnector.DANA);

        assertThat(get(browser(), url("/sign-in")).body()).isEqualTo("acme/" + HelpdeskConnector.DANA);
    }

    @Test
    void someoneTheProviderKnowsAndTheDirectoryDoesNotIsRefused() throws Exception {
        start(null);
        idp.signInAs("mallory@acme.example");
        HttpClient browser = browser();

        HttpResponse<String> refused = get(browser, url("/sign-in?org=acme"));

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body()).contains("mallory@acme.example is not in acme&#39;s directory.".replace("&#39;", "'"));
        assertThat(get(browser, url("/")).body()).isEqualTo("nobody");
    }

    @Test
    void anAnswerThatDoesNotCheckOutSignsNobodyIn() throws Exception {
        start(null);
        idp.signInAs(HelpdeskConnector.PRIYA);
        for (Misbehaviour wrong : new Misbehaviour[] {Misbehaviour.WRONG_NONCE, Misbehaviour.WRONG_AUDIENCE,
                Misbehaviour.UNPUBLISHED_KEY, Misbehaviour.UNVERIFIED_EMAIL}) {
            idp.misbehave(wrong);
            HttpClient browser = browser();

            HttpResponse<String> refused = get(browser, url("/sign-in?org=acme"));

            assertThat(refused.statusCode()).as(wrong.name()).isEqualTo(403);
            assertThat(get(browser, url("/")).body()).as(wrong.name()).isEqualTo("nobody");
        }
    }

    @Test
    void aSignInStartedInOneBrowserCannotBeFinishedInAnother() throws Exception {
        start(null);
        idp.signInAs(HelpdeskConnector.PRIYA);
        HttpClient attacker = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        String toProvider = get(attacker, url("/sign-in?org=acme")).headers().firstValue("Location").orElseThrow();
        String backToHost = get(attacker, toProvider).headers().firstValue("Location").orElseThrow();

        HttpClient victim = browser();
        HttpResponse<String> refused = get(victim, backToHost);

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body()).contains("This sign-in was not started here");
        assertThat(get(victim, url("/")).body()).isEqualTo("nobody");
    }
}
