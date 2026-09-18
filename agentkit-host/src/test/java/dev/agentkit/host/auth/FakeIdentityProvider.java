package dev.agentkit.host.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An OpenID Connect provider small enough to read, for tests and for trying the host's sign-in on one machine: it
 * serves discovery and its keys, signs people in (as {@link #signInAs}, or whoever types their email into its page),
 * trades a code for real RS256-signed tokens after checking PKCE, the redirect and the client, and mints access tokens
 * for MCP. It can misbehave on purpose, to show the host refuses what does not check out.
 *
 * <pre>
 * ./mvnw -q -pl agentkit-host exec:exec -Dexec.classpathScope=test \
 *   -Dexec.mainClass=dev.agentkit.host.auth.FakeIdentityProvider -Dexec.appArgs=8600
 * </pre>
 */
public final class FakeIdentityProvider implements AutoCloseable {

    /** How a token it issues is wrong, on purpose. */
    public enum Misbehaviour { NONE, WRONG_NONCE, WRONG_AUDIENCE, UNVERIFIED_EMAIL, UNPUBLISHED_KEY }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final RSAKey key;
    private final RSAKey unpublished;
    private final String clientId;
    private final String clientSecret;
    private final Map<String, Grant> codes = new ConcurrentHashMap<>();
    private volatile String signInAs;
    private volatile Misbehaviour misbehaviour = Misbehaviour.NONE;

    private record Grant(String email, String clientId, String redirect, String challenge, String nonce) {
    }

    /**
     * @param clientSecret the secret the client must present, or null for a public client
     */
    public FakeIdentityProvider(int port, String clientId, String clientSecret) throws IOException, JOSEException {
        this.key = new RSAKeyGenerator(2048).keyID("fake-1").generate();
        this.unpublished = new RSAKeyGenerator(2048).keyID("fake-1").generate();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/.well-known/openid-configuration", exchange -> json(exchange, 200, Map.of(
                "issuer", issuer(),
                "authorization_endpoint", issuer() + "/authorize",
                "token_endpoint", issuer() + "/token",
                "jwks_uri", issuer() + "/jwks",
                "response_types_supported", java.util.List.of("code"),
                "code_challenge_methods_supported", java.util.List.of("S256"),
                "id_token_signing_alg_values_supported", java.util.List.of("RS256"))));
        server.createContext("/jwks", exchange -> json(exchange, 200, new JWKSet(key.toPublicJWK()).toJSONObject()));
        server.createContext("/authorize", this::authorize);
        server.createContext("/token", this::token);
        server.start();
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Who the next sign-in is, without a page; null to ask on the page. */
    public FakeIdentityProvider signInAs(String email) {
        this.signInAs = email;
        return this;
    }

    public FakeIdentityProvider misbehave(Misbehaviour misbehaviour) {
        this.misbehaviour = misbehaviour;
        return this;
    }

    /** An access token for {@code email}, for {@code audience}, lasting {@code lifetime} (negative: already expired). */
    public String accessToken(String email, String audience, Duration lifetime) throws JOSEException {
        Instant now = Instant.now();
        return sign(new JWTClaimsSet.Builder().issuer(issuer()).subject(email).audience(audience)
                .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plus(lifetime)))
                .claim("email", email).claim("email_verified", true).claim("client_id", "claude-code").build(),
                new JOSEObjectType("at+jwt"));
    }

    private void authorize(HttpExchange exchange) throws IOException {
        try (exchange) {
            Map<String, String> query = form(exchange.getRequestURI().getRawQuery());
            if ("POST".equals(exchange.getRequestMethod())) {
                query.putAll(form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            }
            String email = signInAs != null ? signInAs : query.get("email");
            if (email == null || email.isBlank()) {
                StringBuilder hidden = new StringBuilder();
                query.forEach((k, v) -> hidden.append("<input type=\"hidden\" name=\"").append(escape(k))
                        .append("\" value=\"").append(escape(v)).append("\">"));
                html(exchange, "<!doctype html><title>Fake identity provider</title><body style=\"font:15px system-ui;"
                        + "max-width:26rem;margin:4rem auto\"><h1>Fake identity provider</h1><p>For trying the host's "
                        + "sign-in on one machine. Nothing here is checked.</p><form method=\"post\" action=\"/authorize\">"
                        + hidden + "<label>Email <input name=\"email\" type=\"email\" required autofocus></label> "
                        + "<button>Sign in</button></form></body>");
                return;
            }
            String code = UUID.randomUUID().toString();
            codes.put(code, new Grant(email.strip(), query.get("client_id"), query.get("redirect_uri"),
                    query.get("code_challenge"), query.get("nonce")));
            String redirect = query.get("redirect_uri");
            exchange.getResponseHeaders().add("Location", redirect + (redirect.contains("?") ? "&" : "?") + "code="
                    + encode(code) + "&state=" + encode(query.getOrDefault("state", "")));
            exchange.sendResponseHeaders(302, -1);
        }
    }

    private void token(HttpExchange exchange) throws IOException {
        try (exchange) {
            Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Grant grant = codes.remove(form.getOrDefault("code", ""));
            if (grant == null || !grant.redirect().equals(form.get("redirect_uri"))
                    || !clientId.equals(form.get("client_id")) || !clientId.equals(grant.clientId())) {
                json(exchange, 400, Map.of("error", "invalid_grant"));
                return;
            }
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(form.getOrDefault("code_verifier", "").getBytes(StandardCharsets.US_ASCII)));
            if (!challenge.equals(grant.challenge())) {
                json(exchange, 400, Map.of("error", "invalid_grant", "error_description", "PKCE"));
                return;
            }
            if (clientSecret != null) {
                String expected = "Basic " + Base64.getEncoder().encodeToString((encode(clientId) + ":" + encode(clientSecret))
                        .getBytes(StandardCharsets.UTF_8));
                if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    json(exchange, 401, Map.of("error", "invalid_client"));
                    return;
                }
            }
            Instant now = Instant.now();
            JWTClaimsSet id = new JWTClaimsSet.Builder().issuer(issuer()).subject(grant.email())
                    .audience(misbehaviour == Misbehaviour.WRONG_AUDIENCE ? "someone-else" : clientId)
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                    .claim("nonce", misbehaviour == Misbehaviour.WRONG_NONCE ? "not-yours" : grant.nonce())
                    .claim("email", grant.email())
                    .claim("email_verified", misbehaviour != Misbehaviour.UNVERIFIED_EMAIL).build();
            Map<String, Object> tokens = new LinkedHashMap<>();
            tokens.put("access_token", accessToken(grant.email(), clientId, Duration.ofMinutes(5)));
            tokens.put("id_token", sign(id, JOSEObjectType.JWT));
            tokens.put("token_type", "Bearer");
            tokens.put("expires_in", 300);
            json(exchange, 200, tokens);
        } catch (Exception e) {
            json(exchange, 500, Map.of("error", "server_error", "error_description", String.valueOf(e.getMessage())));
        }
    }

    private String sign(JWTClaimsSet claims, JOSEObjectType type) throws JOSEException {
        RSAKey signer = misbehaviour == Misbehaviour.UNPUBLISHED_KEY ? unpublished : key;
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).type(type).build(),
                claims);
        jwt.sign(new RSASSASigner(signer));
        return jwt.serialize();
    }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void html(HttpExchange exchange, String page) throws IOException {
        byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static Map<String, String> form(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw != null && !raw.isBlank()) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return values;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 && !args[0].isBlank() ? Integer.parseInt(args[0].strip()) : 8600;
        FakeIdentityProvider provider = new FakeIdentityProvider(port, "agentkit-host", null);
        System.out.println("Fake identity provider at " + provider.issuer() + " (client id agentkit-host, no secret)");
        Thread.currentThread().join();
    }
}
