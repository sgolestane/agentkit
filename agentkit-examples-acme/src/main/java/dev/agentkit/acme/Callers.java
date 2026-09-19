package dev.agentkit.acme;

import dev.agentkit.mcp.server.CallerAssertion;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * How one of Acme's connectors checks who is calling it: the agent host's caller assertion, verified against the keys
 * the host publishes.
 *
 * <pre>
 * AGENTKIT_HOST_JWKS_URL=http://localhost:8400/.well-known/jwks.json   # the host's published keys
 * AGENTKIT_HOST_ISSUER=http://localhost:8400                          # optional: the host's address, by default the URL's origin
 * CONNECTOR_ORG=acme                                                  # optional: the organization this connector serves
 * </pre>
 *
 * Without {@code AGENTKIT_HOST_JWKS_URL} the connector takes identity from the arguments the host binds, and says so.
 */
public final class Callers {

    private Callers() {
    }

    /** The checker for a connector named {@code audience}, as {@code connectors/<audience>.yaml} names it to the host. */
    public static Optional<CallerAssertion> fromEnv(Map<String, String> env, String audience) {
        String jwks = env.get("AGENTKIT_HOST_JWKS_URL");
        if (jwks == null || jwks.isBlank()) {
            System.err.println(audience + ": AGENTKIT_HOST_JWKS_URL is not set, so calls are not checked for a caller "
                    + "assertion; who is calling comes from the arguments the host binds.");
            return Optional.empty();
        }
        URI keys = URI.create(jwks.strip());
        String issuer = env.getOrDefault("AGENTKIT_HOST_ISSUER", keys.getScheme() + "://" + keys.getAuthority());
        return Optional.of(CallerAssertion.fromJwks(keys, issuer.strip(), audience, env.getOrDefault("CONNECTOR_ORG", "acme")));
    }
}
