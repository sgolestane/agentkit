package dev.agentkit.host.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.agentkit.chat.web.ChatServer;
import dev.agentkit.host.OrgHost;
import dev.agentkit.host.Tenant;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signing in to the console with the organization's own identity provider: OpenID Connect's authorization code flow,
 * with PKCE, a state bound to the browser that started it, and a nonce bound to the ID token that answers it.
 *
 * <pre>
 * GET /sign-in?org=acme      to the organization's identity provider (a list of organizations when not named)
 * GET /sign-in/callback      back from it: the code traded for an ID token, checked, and a session begun
 * GET /sign-out              the session ended
 * </pre>
 *
 * <p>Who someone is comes only from an ID token that passed {@link Oidc}'s checks, and only if the organization's
 * directory knows the email it carries: a person the identity provider knows and the directory does not is refused. A
 * session is a random id in an {@code HttpOnly} cookie, {@code Secure} on an https host, and lasts
 * {@link #SESSION_LENGTH}. Sessions and sign-ins in progress are kept in a {@link SessionStore}: with a database,
 * shared by every instance of the host, so a person stays signed in across instances and restarts.
 */
public final class OidcSignIn implements ChatServer.Tenants, HttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(OidcSignIn.class);

    static final String SESSION_COOKIE = "agentkit-session";
    static final String STATE_COOKIE = "agentkit-sign-in";
    static final Duration SESSION_LENGTH = Duration.ofHours(8);
    static final Duration SIGN_IN_LENGTH = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, OrgHost> orgs;
    private final Function<OrgHost, Optional<Oidc>> providers;
    private final URI publicUrl;
    private final Supplier<Instant> clock;
    private final SessionStore store;

    private static final String SESSION = "session";
    private static final String SIGN_IN = "sign-in";

    /** A sign-in waiting for the identity provider to send the person back. */
    private record Pending(String org, String nonce, String verifier) {
        String write() {
            return org + '\n' + nonce + '\n' + verifier;
        }

        static Pending read(String written) {
            String[] parts = written.split("\n", 3);
            return new Pending(parts[0], parts[1], parts[2]);
        }
    }

    /**
     * @param providers the organization's identity provider, if it has one
     * @param publicUrl the host's address as a browser reaches it, which the provider sends people back to
     */
    public OidcSignIn(Map<String, OrgHost> orgs, Function<OrgHost, Optional<Oidc>> providers, URI publicUrl,
                      Supplier<Instant> clock) {
        this(orgs, providers, publicUrl, clock, SessionStore.inMemory());
    }

    /** @param store where sessions and sign-ins in progress are kept */
    public OidcSignIn(Map<String, OrgHost> orgs, Function<OrgHost, Optional<Oidc>> providers, URI publicUrl,
                      Supplier<Instant> clock, SessionStore store) {
        this.store = Objects.requireNonNull(store, "store");
        this.orgs = Map.copyOf(orgs);
        this.providers = Objects.requireNonNull(providers, "providers");
        this.publicUrl = URI.create(Objects.requireNonNull(publicUrl, "publicUrl").toString().replaceAll("/+$", ""));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Where the identity provider sends a person back to; register it with the provider. */
    public URI redirect() {
        return URI.create(publicUrl + "/sign-in/callback");
    }

    @Override
    public Optional<String> of(HttpExchange exchange) {
        return cookie(exchange, SESSION_COOKIE).flatMap(id -> store.get(SESSION, id, clock.get()));
    }

    @Override
    public Optional<String> signIn() {
        return Optional.of("/sign-in");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/sign-in" -> start(exchange);
                case "/sign-in/callback" -> callback(exchange);
                case "/sign-out" -> {
                    cookie(exchange, SESSION_COOKIE).ifPresent(id -> store.remove(SESSION, id));
                    exchange.getResponseHeaders().add("Set-Cookie", clear(SESSION_COOKIE));
                    page(exchange, 200, "Signed out", "<p>You are signed out.</p><p><a href=\"/sign-in\">Sign in again</a></p>");
                }
                default -> exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    private void start(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        List<String> offered = orgs.values().stream().filter(o -> providers.apply(o).isPresent()).map(OrgHost::org)
                .sorted().toList();
        String org = query.getOrDefault("org", offered.size() == 1 ? offered.get(0) : "");
        if (org.isEmpty()) {
            StringBuilder list = new StringBuilder("<p>Which organization?</p><ul>");
            offered.forEach(name -> list.append("<li><a href=\"/sign-in?org=").append(escape(name)).append("\">")
                    .append(escape(name)).append("</a></li>"));
            page(exchange, 200, "Sign in", list.append("</ul>").toString());
            return;
        }
        Optional<Oidc> provider = Optional.ofNullable(orgs.get(org)).flatMap(providers);
        if (provider.isEmpty()) {
            page(exchange, 404, "Sign in", "<p>" + escape(org) + " has no identity provider here.</p>");
            return;
        }
        prune();
        String state = random();
        String nonce = random();
        String verifier = random() + random();
        store.put(SIGN_IN, state, new Pending(org, nonce, verifier).write(), clock.get().plus(SIGN_IN_LENGTH));
        URI to;
        try {
            to = provider.get().authorizeUrl(redirect(), state, nonce, challenge(verifier));
        } catch (IOException e) {
            LOG.warn("The identity provider of {} could not be reached: {}", org, e.getMessage());
            page(exchange, 502, "Sign in", "<p>" + escape(org) + "'s identity provider could not be reached. Try again "
                    + "shortly.</p>");
            return;
        }
        // The state is bound to this browser too, so a sign-in someone else started cannot be finished in it.
        exchange.getResponseHeaders().add("Set-Cookie", cookie(STATE_COOKIE, state, SIGN_IN_LENGTH));
        exchange.getResponseHeaders().add("Location", to.toString());
        exchange.sendResponseHeaders(302, -1);
    }

    private void callback(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        exchange.getResponseHeaders().add("Set-Cookie", clear(STATE_COOKIE));
        String state = query.getOrDefault("state", "");
        Pending started = state.isEmpty() ? null
                : store.take(SIGN_IN, state, clock.get()).map(Pending::read).orElse(null);
        Optional<String> browserState = cookie(exchange, STATE_COOKIE);
        if (started == null || browserState.isEmpty() || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                browserState.get().getBytes(StandardCharsets.UTF_8))) {
            refuse(exchange, "This sign-in was not started here, or took too long.");
            return;
        }
        if (query.containsKey("error")) {
            refuse(exchange, "The identity provider did not sign you in: " + query.get("error") + ".");
            return;
        }
        OrgHost org = orgs.get(started.org());
        Optional<Oidc> provider = Optional.ofNullable(org).flatMap(providers);
        if (provider.isEmpty()) {
            refuse(exchange, "This organization has no identity provider here any more.");
            return;
        }
        JWTClaimsSet claims;
        try {
            claims = provider.get().signIn(query.getOrDefault("code", ""), redirect(), started.verifier(), started.nonce());
        } catch (Oidc.RejectedToken e) {
            LOG.warn("A sign-in to {} was refused: {}", started.org(), e.getMessage());
            refuse(exchange, "The identity provider's answer did not check out.");
            return;
        } catch (IOException e) {
            LOG.warn("A sign-in to {} failed: {}", started.org(), e.getMessage());
            refuse(exchange, "The identity provider could not complete the sign-in.");
            return;
        }
        Optional<String> email = provider.get().email(claims);
        if (email.isEmpty()) {
            refuse(exchange, "The identity provider did not say which verified email you sign in with.");
            return;
        }
        if (org.current().principal(email.get()).isEmpty()) {
            refuse(exchange, email.get() + " is not in " + org.org() + "'s directory.");
            return;
        }
        String session = random();
        store.put(SESSION, session, new Tenant(org.org(), email.get()).id(), clock.get().plus(SESSION_LENGTH));
        exchange.getResponseHeaders().add("Set-Cookie", cookie(SESSION_COOKIE, session, SESSION_LENGTH));
        exchange.getResponseHeaders().add("Location", "/");
        exchange.sendResponseHeaders(302, -1);
    }

    // ---------------------------------------------------------------- helpers

    private void prune() {
        store.prune(clock.get());
    }

    /** Refuses a sign-in, saying why: escaped, since part of it can come from the identity provider's redirect. */
    private void refuse(HttpExchange exchange, String why) throws IOException {
        page(exchange, 403, "Not signed in", "<p>" + escape(why) + "</p><p><a href=\"/sign-in\">Try again</a></p>");
    }

    private String cookie(String name, String value, Duration lasts) {
        return name + "=" + value + "; Path=/; Max-Age=" + lasts.toSeconds() + "; HttpOnly; SameSite=Lax"
                + ("https".equals(publicUrl.getScheme()) ? "; Secure" : "");
    }

    private String clear(String name) {
        return name + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax" + ("https".equals(publicUrl.getScheme()) ? "; Secure" : "");
    }

    static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Optional<String> cookie(HttpExchange exchange, String name) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String[] pair = part.strip().split("=", 2);
                if (pair.length == 2 && pair[0].equals(name) && !pair[1].isBlank()) {
                    // A cookie value may come wrapped in double quotes (RFC 6265's cookie-value allows it).
                    String value = pair[1].strip();
                    return Optional.of(value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")
                            ? value.substring(1, value.length() - 1) : value);
                }
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    values.putIfAbsent(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return values;
    }

    private static void page(HttpExchange exchange, int status, String title, String body) throws IOException {
        String html = """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
                <title>%s · AgentKit</title>
                <style>body{font:15px system-ui,sans-serif;max-width:26rem;margin:4rem auto;padding:0 1rem;color:#1d1d1f}
                @media(prefers-color-scheme:dark){body{background:#1c1c1e;color:#f2f2f7}a{color:#8ab4f8}}</style>
                </head><body><h1>%s</h1>%s</body></html>
                """.formatted(escape(title), escape(title), body);
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().add("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
