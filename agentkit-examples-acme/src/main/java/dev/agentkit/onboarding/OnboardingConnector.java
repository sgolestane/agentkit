package dev.agentkit.onboarding;

import dev.agentkit.acme.HttpConnector;
import java.io.IOException;

/**
 * The onboarding systems as an MCP connector over HTTP, the way the agent host reaches one, answering only a caller
 * with the bearer token.
 *
 * <pre>
 * ONBOARDING_TOKEN=...         # required: the token the host's connector file sends
 * ONBOARDING_PORT=8140         # optional
 * ONBOARDING_ACTOR=onboarding  # optional: the onboarding agent's deferred.actor, which may revoke for anyone
 * AGENTKIT_HOST_JWKS_URL=...   # optional: check every call's caller assertion (see dev.agentkit.acme.Callers)
 * </pre>
 *
 * The systems are seeded from {@code onboarding/hr.json} and kept in memory.
 */
public final class OnboardingConnector {

    static final String INSTRUCTIONS = "The systems an IT onboarding touches: the HRIS, Okta, GitHub, AWS, Salesforce, "
            + "Slack, Workday and the IT service desk.";

    private OnboardingConnector() {
    }

    /** Serves {@code systems} on {@code port} (0 for any free one). */
    public static HttpConnector serve(int port, String token, OnboardingSystems systems) throws IOException {
        return serve(port, token, systems, java.util.Optional.empty());
    }

    /**
     * Serves {@code systems}, taking a call only with a caller assertion {@code callers} accepts, and only when its
     * {@code requested_by} is that caller.
     */
    public static HttpConnector serve(int port, String token, OnboardingSystems systems,
                                      java.util.Optional<dev.agentkit.mcp.server.CallerAssertion> callers) throws IOException {
        dev.agentkit.core.tool.DeclaredTools tools = systems.catalog();
        return HttpConnector.serve(port, "onboarding-systems", INSTRUCTIONS, token,
                callers.map(c -> c.guard(tools, java.util.Set.of("requested_by"))).orElse(tools));
    }

    public static void main(String[] args) throws Exception {
        String token = System.getenv("ONBOARDING_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("Set ONBOARDING_TOKEN: the bearer token the agent host sends this connector.");
            System.exit(2);
        }
        int port = Integer.parseInt(System.getenv().getOrDefault("ONBOARDING_PORT", "8140"));
        String actor = System.getenv().getOrDefault("ONBOARDING_ACTOR", "onboarding");
        HttpConnector served = serve(port, token, OnboardingSystems.open(actor, 1_000),
                dev.agentkit.acme.Callers.fromEnv(System.getenv(), "onboarding"));
        System.out.println("onboarding systems at " + served.url());
        Thread.currentThread().join();
    }
}
