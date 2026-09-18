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
        return HttpConnector.serve(port, "onboarding-systems", INSTRUCTIONS, token, systems.catalog());
    }

    public static void main(String[] args) throws Exception {
        String token = System.getenv("ONBOARDING_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("Set ONBOARDING_TOKEN: the bearer token the agent host sends this connector.");
            System.exit(2);
        }
        int port = Integer.parseInt(System.getenv().getOrDefault("ONBOARDING_PORT", "8140"));
        String actor = System.getenv().getOrDefault("ONBOARDING_ACTOR", "onboarding");
        HttpConnector served = serve(port, token, OnboardingSystems.open(actor, 1_000));
        System.out.println("onboarding systems at " + served.url());
        Thread.currentThread().join();
    }
}
