package dev.agentkit.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.ToolDeclaration;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.mcp.CallMeta;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A connector takes who is calling from the host's signed caller assertion, and only when it checks out: the host's key,
 * the host as issuer, this connector as audience, this organization, unexpired, short-lived. A guarded tool refuses a
 * call without one, an argument naming anyone but the caller, and an assertion used twice for a change.
 */
class ACallerAssertionIsCheckedTest {

    private static final String ISSUER = "https://agents.acme.example";

    private final ECKey key = generate();
    private final CallerAssertion callers = CallerAssertion.fromKeys(new JWKSet(key.toPublicJWK()).toString(), ISSUER,
            "ledger", "acme");

    private static ECKey generate() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID("k1").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String assertion(ECKey with, Consumer<JWTClaimsSet.Builder> change) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("ledger")
                .subject("priya@acme.example").claim("email", "priya@acme.example").claim("org", "acme")
                .claim("agent", "access-desk").claim("agent_version", "abc123").claim("conversation", "conv-1")
                .claim("turn", "turn-1").issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60)))
                .jwtID(UUID.randomUUID().toString());
        change.accept(claims);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(with.getKeyID()).build(), claims.build());
        jwt.sign(new ECDSASigner(with));
        return jwt.serialize();
    }

    private Map<String, Object> meta(String jwt) {
        return Map.of(CallerAssertion.META_KEY, jwt);
    }

    @Test
    void anAssertionThatChecksOutSaysWhoIsCalling() throws Exception {
        CallerAssertion.Caller caller = callers.verify(meta(assertion(key, c -> { })));

        assertThat(caller.email()).isEqualTo("priya@acme.example");
        assertThat(caller.identity()).isEqualTo("priya@acme.example");
        assertThat(caller.agent()).isEqualTo("access-desk");
        assertThat(caller.agentVersion()).isEqualTo("abc123");
        assertThat(caller.conversation()).isEqualTo("conv-1");
        assertThat(caller.turn()).isEqualTo("turn-1");
        assertThat(caller.isAgent()).isFalse();

        CallerAssertion.Caller agent = callers.verify(meta(assertion(key, c -> c.subject("agent:access-desk")
                .claim("email", null))));
        assertThat(agent.isAgent()).isTrue();
        assertThat(agent.identity()).isEqualTo("access-desk");
    }

    @Test
    void anAssertionThatDoesNotCheckOutIsRefused() throws Exception {
        Instant now = Instant.now();
        Map<String, Consumer<JWTClaimsSet.Builder>> wrong = Map.of(
                "another connector", c -> c.audience("company"),
                "another organization", c -> c.claim("org", "globex"),
                "another issuer", c -> c.issuer("https://evil.example"),
                "expired", c -> c.issueTime(Date.from(now.minusSeconds(200))).expirationTime(Date.from(now.minusSeconds(100))),
                "too long", c -> c.expirationTime(Date.from(now.plus(Duration.ofHours(1)))),
                "no id", c -> c.jwtID(null));
        for (Map.Entry<String, Consumer<JWTClaimsSet.Builder>> each : wrong.entrySet()) {
            String jwt = assertion(key, each.getValue());
            assertThatThrownBy(() -> callers.verify(meta(jwt))).as(each.getKey()).isInstanceOf(CallerAssertion.Refused.class);
        }
        String forged = assertion(generate(), c -> { });
        assertThatThrownBy(() -> callers.verify(meta(forged))).isInstanceOf(CallerAssertion.Refused.class);
        assertThatThrownBy(() -> callers.verify(Map.of())).isInstanceOf(CallerAssertion.Refused.class)
                .hasMessage("The call carries no caller assertion.");
    }

    @Test
    void aGuardedToolKnowsItsCallerAndHoldsItsArgumentsToThem() throws Exception {
        DeclaredTools tools = callers.guard(new DeclaredTools()
                .add(FunctionTool.builder("revoke", "Revoke").schema(Map.of("type", "object", "properties", Map.of()))
                        .handler(inv -> ToolResult.ok("revoked by " + CallerAssertion.caller().orElseThrow().identity())).build(),
                        new ToolDeclaration("ledger", ToolEffect.REVOKE, "grant_id"))
                .add(FunctionTool.builder("look", "Look").schema(Map.of("type", "object", "properties", Map.of()))
                        .handler(inv -> ToolResult.ok("seen")).build(), new ToolDeclaration("ledger", ToolEffect.READ, null)),
                Set.of("acting_as"));
        String jwt = assertion(key, c -> { });

        ToolResult asItself = call(tools, "revoke", jwt, Map.of("acting_as", "Priya@acme.example"));
        ToolResult replayed = call(tools, "revoke", jwt, Map.of());
        ToolResult asSomeoneElse = call(tools, "revoke", assertion(key, c -> { }), Map.of("acting_as", "dana@acme.example"));
        ToolResult anonymous = call(tools, "revoke", null, Map.of());

        assertThat(asItself.content()).isEqualTo("revoked by priya@acme.example");
        assertThat(replayed.isError()).isTrue();
        assertThat(replayed.content()).contains("already used");
        assertThat(asSomeoneElse.content()).isEqualTo("acting_as names dana@acme.example, but the call is made by "
                + "priya@acme.example.");
        assertThat(anonymous.content()).isEqualTo("The call carries no caller assertion.");
        // A read may be repeated with the same assertion: nothing changes.
        assertThat(call(tools, "look", jwt, Map.of()).content()).isEqualTo("seen");
        assertThat(call(tools, "look", jwt, Map.of()).content()).isEqualTo("seen");
        assertThat(CallerAssertion.caller()).isEmpty();
    }

    private static ToolResult call(DeclaredTools tools, String name, String jwt, Map<String, Object> arguments) {
        Map<String, Object> meta = jwt == null ? Map.of() : Map.of(CallerAssertion.META_KEY, jwt);
        return CallMeta.receiving(meta, () -> tools.entry(name).orElseThrow().tool()
                .execute(new ToolInvocation("t", name, new HashMap<>(arguments))));
    }
}
