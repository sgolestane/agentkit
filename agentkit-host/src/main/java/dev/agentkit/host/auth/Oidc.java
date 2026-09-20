package dev.agentkit.host.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import dev.agentkit.host.repo.OrgRepo;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One organization's identity provider, as an OpenID Connect client sees it: where to send a person to sign in, how to
 * turn what comes back into who they are, and how to check an access token an MCP client presents.
 *
 * <p>Everything about the provider comes from its discovery document at {@code <issuer>/.well-known/openid-configuration},
 * whose issuer must be the one {@code org.yaml} names. Tokens are checked against the keys the provider publishes
 * (fetched, cached and refreshed on rotation): signed with an asymmetric algorithm, by that issuer, for the expected
 * audience, not expired — and an ID token, for the sign-in it answers (its nonce). Nothing a token says is believed
 * before that.
 */
public final class Oidc {

    /** The signing algorithms accepted: asymmetric only, so a token cannot be made with anything the host holds. */
    static final Set<JWSAlgorithm> ALGORITHMS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512, JWSAlgorithm.ES256, JWSAlgorithm.ES384,
            JWSAlgorithm.ES512);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OrgRepo.SignInSpec spec;
    private final Optional<String> clientSecret;
    private final HttpClient http;
    private volatile Discovery discovery;
    private volatile JWKSource<SecurityContext> keys;

    /** What the provider's discovery document says, of what the host uses. */
    public record Discovery(String issuer, URI authorize, URI token, URI jwks) {
    }

    /** A token that did not prove what it claims, and why, for a log; the person is told only that it failed. */
    public static final class RejectedToken extends Exception {
        public RejectedToken(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public Oidc(OrgRepo.SignInSpec spec, Optional<String> clientSecret) {
        this(spec, clientSecret, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    Oidc(OrgRepo.SignInSpec spec, Optional<String> clientSecret, HttpClient http) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
        this.http = Objects.requireNonNull(http, "http");
    }

    public OrgRepo.SignInSpec spec() {
        return spec;
    }

    /** The provider's endpoints, fetched once and kept. */
    public Discovery discovery() throws IOException {
        Discovery known = discovery;
        if (known != null) {
            return known;
        }
        JsonNode document = get(URI.create(spec.issuer() + "/.well-known/openid-configuration"));
        String issuer = document.path("issuer").asText("").replaceAll("/+$", "");
        if (!issuer.equals(spec.issuer())) {
            throw new IOException("The identity provider at " + spec.issuer() + " says its issuer is " + issuer);
        }
        Discovery found = new Discovery(issuer, URI.create(document.path("authorization_endpoint").asText()),
                URI.create(document.path("token_endpoint").asText()), URI.create(document.path("jwks_uri").asText()));
        discovery = found;
        return found;
    }

    /** Where to send a person to sign in: the authorization code flow, with PKCE and a nonce. */
    public URI authorizeUrl(URI redirect, String state, String nonce, String challenge) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", spec.clientId());
        query.put("redirect_uri", redirect.toString());
        query.put("scope", "openid email profile");
        query.put("state", state);
        query.put("nonce", nonce);
        query.put("code_challenge", challenge);
        query.put("code_challenge_method", "S256");
        URI authorize = discovery().authorize();
        return URI.create(authorize + (authorize.getQuery() == null ? "?" : "&") + form(query));
    }

    /** Trades the code a sign-in came back with for its ID token, and checks it: the person's claims. */
    public JWTClaimsSet signIn(String code, URI redirect, String verifier, String nonce) throws IOException, RejectedToken {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirect.toString());
        form.put("code_verifier", verifier);
        form.put("client_id", spec.clientId());
        HttpRequest.Builder request = HttpRequest.newBuilder(discovery().token()).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(form)));
        clientSecret.ifPresent(secret -> request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (encode(spec.clientId()) + ":" + encode(secret)).getBytes(StandardCharsets.UTF_8))));
        JsonNode tokens;
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            tokens = JSON.readTree(response.body());
            if (response.statusCode() != 200) {
                throw new IOException("The identity provider refused the code: " + tokens.path("error").asText(
                        String.valueOf(response.statusCode())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        String idToken = tokens.path("id_token").asText("");
        if (idToken.isEmpty()) {
            throw new IOException("The identity provider gave no ID token");
        }
        JWTClaimsSet claims = verify(idToken, Set.of(spec.clientId()));
        Object nonceClaim = claims.getClaim("nonce");
        if (!nonce.equals(nonceClaim)) {
            throw new RejectedToken("The ID token is not for this sign-in: its nonce differs", null);
        }
        return claims;
    }

    /** Checks an access token an MCP client presents: this issuer's, for {@code audience}. */
    public JWTClaimsSet accessToken(String token, String audience) throws RejectedToken {
        return verify(token, Set.of(audience));
    }

    /**
     * The person's email, from {@link OrgRepo.SignInSpec#emailClaim}; empty when there is none, or the provider says
     * it is not verified.
     */
    public Optional<String> email(JWTClaimsSet claims) {
        Object verified = claims.getClaim("email_verified");
        if (Boolean.FALSE.equals(verified) || "false".equals(verified)) {
            return Optional.empty();
        }
        Object email = claims.getClaim(spec.emailClaim());
        return email instanceof String text && text.contains("@") ? Optional.of(text.strip()) : Optional.empty();
    }

    private JWTClaimsSet verify(String token, Set<String> audience) throws RejectedToken {
        try {
            ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT,
                    new JOSEObjectType("at+jwt"), null));
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, keys()));
            processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<SecurityContext>(audience,
                    new JWTClaimsSet.Builder().issuer(spec.issuer()).build(), Set.of("sub", "exp", "iat"), null));
            return processor.process(token, null);
        } catch (Exception e) {
            throw new RejectedToken("The token was refused: " + e.getMessage(), e);
        }
    }

    private JWKSource<SecurityContext> keys() throws IOException {
        JWKSource<SecurityContext> known = keys;
        if (known != null) {
            return known;
        }
        try {
            JWKSource<SecurityContext> built = JWKSourceBuilder.create(discovery().jwks().toURL()).retrying(true).build();
            keys = built;
            return built;
        } catch (MalformedURLException e) {
            throw new IOException("The identity provider's jwks_uri is not a URL", e);
        }
    }

    private JsonNode get(URI uri) throws IOException {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException(uri + " answered " + response.statusCode());
            }
            return JSON.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    static String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
