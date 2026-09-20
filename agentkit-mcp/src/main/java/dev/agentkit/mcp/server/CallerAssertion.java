package dev.agentkit.mcp.server;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.ForwardingTool;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.mcp.CallMeta;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is calling a connector's tool, as the agent host that makes the call signs it: the caller assertion, a short-lived
 * JWT in the call's {@code _meta["dev.agentkit/caller"]} (see {@code docs/MCP-CONNECTORS.md}).
 *
 * <pre>
 * CallerAssertion callers = CallerAssertion.fromJwks(URI.create("https://agents.example.com/.well-known/jwks.json"),
 *         "https://agents.example.com", "ledger", "acme");
 * DeclaredTools served = callers.guard(tools, Set.of("acting_as"));
 * </pre>
 *
 * <p>An assertion is taken only if it is signed by one of the host's published keys, by that issuer, for this connector
 * (its {@code aud}), for this organization, unexpired, lasting no more than {@link #MAX_LIFETIME}. {@link #guard} then
 * holds every call to it: a call without one that checks out is refused, an argument naming the acting person must name
 * the caller it asserts, and a {@code jti} is not accepted twice by a tool that changes something. Inside a guarded
 * tool, {@link #caller()} is who is calling.
 */
public final class CallerAssertion {

    /** Where in a call's {@code _meta} the assertion is. */
    public static final String META_KEY = "dev.agentkit/caller";

    /** The longest an assertion may last: it covers one call, not a session. */
    public static final Duration MAX_LIFETIME = Duration.ofMinutes(2);

    private static final Set<JWSAlgorithm> ALGORITHMS = Set.of(JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512,
            JWSAlgorithm.RS256, JWSAlgorithm.PS256);
    private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

    private final JWKSource<SecurityContext> keys;
    private final String issuer;
    private final String audience;
    private final String org;
    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    /**
     * Who a call is from.
     *
     * @param subject a person's email, {@code agent:<name>} for work an agent does on its own (a deferred action), or
     *                {@code host} for the host's own lookups
     * @param email   the person's email; null when it is not a person
     */
    public record Caller(String org, String subject, String email, String agent, String agentVersion,
                         String conversation, String turn, String jti) {

        /** Whether this is an agent acting on its own, with no person present. */
        public boolean isAgent() {
            return subject.startsWith("agent:");
        }

        /** Whether this is the host looking something up for itself. */
        public boolean isHost() {
            return subject.equals("host");
        }

        /**
         * Who an argument naming the acting party must name: the person's email, or for an agent its name after
         * {@code agent:}.
         */
        public String identity() {
            return email != null ? email : isAgent() ? subject.substring("agent:".length()) : subject;
        }
    }

    /** A call whose caller could not be established, and why. */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    private CallerAssertion(JWKSource<SecurityContext> keys, String issuer, String audience, String org) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.issuer = Objects.requireNonNull(issuer, "issuer").replaceAll("/+$", "");
        this.audience = Objects.requireNonNull(audience, "audience");
        this.org = Objects.requireNonNull(org, "org");
    }

    /** Checks assertions against the keys the host publishes at {@code jwks}, fetched and refreshed on rotation. */
    public static CallerAssertion fromJwks(URI jwks, String issuer, String audience, String org) {
        try {
            return new CallerAssertion(JWKSourceBuilder.create(jwks.toURL()).retrying(true).build(), issuer, audience, org);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Not a URL: " + jwks, e);
        }
    }

    /** Checks assertions against a key set given whole: {@code jwks} is its JSON. */
    public static CallerAssertion fromKeys(String jwks, String issuer, String audience, String org) {
        try {
            return new CallerAssertion(new ImmutableJWKSet<>(JWKSet.parse(jwks)), issuer, audience, org);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Not a JSON Web Key Set", e);
        }
    }

    /** Who the call being served is from, from its {@code _meta}; {@link Refused} when that does not check out. */
    public Caller verify(Map<String, Object> meta) {
        Object token = meta == null ? null : meta.get(META_KEY);
        if (!(token instanceof String jwt) || jwt.isBlank()) {
            throw new Refused("The call carries no caller assertion.");
        }
        JWTClaimsSet claims;
        try {
            ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, keys));
            processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<SecurityContext>(Set.of(audience),
                    new JWTClaimsSet.Builder().issuer(issuer).claim("org", org).build(),
                    Set.of("sub", "iat", "exp", "jti"), null));
            claims = processor.process(jwt, null);
        } catch (Exception e) {
            throw new Refused("The caller assertion does not check out: " + e.getMessage());
        }
        Duration lifetime = Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant());
        if (lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw new Refused("The caller assertion lasts " + lifetime.toSeconds() + "s, longer than a call's "
                    + MAX_LIFETIME.toSeconds() + "s.");
        }
        return new Caller(org, claims.getSubject(), text(claims, "email"), text(claims, "agent"),
                text(claims, "agent_version"), text(claims, "conversation"), text(claims, "turn"), claims.getJWTID());
    }

    /** Who the call being served on this thread is from, inside a {@linkplain #guard guarded} tool. */
    public static Optional<Caller> caller() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Holds every call to {@code tools} to its caller assertion: refused without one that checks out; refused when an
     * argument in {@code identityArguments} names anyone but the caller; and, for a tool that changes something,
     * refused when its {@code jti} was already used.
     */
    public DeclaredTools guard(DeclaredTools tools, Set<String> identityArguments) {
        DeclaredTools guarded = new DeclaredTools();
        tools.entries().forEach(entry -> guarded.add(new Guarded(entry.tool(), identityArguments,
                entry.declaration().effect() != ToolEffect.READ), entry.declaration()));
        return guarded;
    }

    private final class Guarded extends ForwardingTool {
        private final Tool delegate;
        private final Set<String> identityArguments;
        private final boolean changes;

        Guarded(Tool delegate, Set<String> identityArguments, boolean changes) {
            this.delegate = delegate;
            this.identityArguments = Set.copyOf(identityArguments);
            this.changes = changes;
        }

        @Override
        protected Tool delegate() {
            return delegate;
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            Caller caller;
            try {
                caller = verify(CallMeta.received());
            } catch (Refused refused) {
                return ToolResult.error(refused.getMessage());
            }
            for (String argument : identityArguments) {
                Object named = invocation.arguments().get(argument);
                if (named != null && !String.valueOf(named).strip().toLowerCase(Locale.ROOT)
                        .equals(caller.identity().toLowerCase(Locale.ROOT))) {
                    return ToolResult.error(argument + " names " + named + ", but the call is made by "
                            + caller.identity() + ".");
                }
            }
            if (changes && !firstUse(caller.jti())) {
                return ToolResult.error("This caller assertion was already used; each call that changes something "
                        + "carries its own.");
            }
            Caller before = CURRENT.get();
            CURRENT.set(caller);
            try {
                return delegate.execute(invocation);
            } finally {
                if (before == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(before);
                }
            }
        }
    }

    /** Whether {@code jti} is new; each is remembered for as long as an assertion could still be valid. */
    private boolean firstUse(String jti) {
        Instant now = Instant.now();
        seen.values().removeIf(until -> until.isBefore(now));
        return seen.putIfAbsent(jti, now.plus(MAX_LIFETIME).plusSeconds(60)) == null;
    }

    private static String text(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return value == null ? null : String.valueOf(value);
    }
}
