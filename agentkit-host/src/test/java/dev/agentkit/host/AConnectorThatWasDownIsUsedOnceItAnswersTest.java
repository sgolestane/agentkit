package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A connector that could not be reached when a version loaded leaves its agents unavailable, saying why — until it
 * answers. Then the version is loaded afresh and its agents are available, with no new commit and no restart.
 */
class AConnectorThatWasDownIsUsedOnceItAnswersTest {

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private OrgHost org;

    @AfterEach
    void stop() {
        if (org != null) {
            org.close();
        }
        if (helpdesk != null) {
            helpdesk.close();
        }
    }

    @Test
    void itsAgentsBecomeAvailableOnceItIsReached() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        RepoFixture repo = RepoFixture.copyInto(dir);
        String version = repo.commit("the helpdesk");
        org = OrgHost.open(repo.root(), AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", "http://127.0.0.1:" + port + "/mcp", "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));

        assertThat(org.current().agent("helpdesk").orElseThrow().unavailable())
                .hasValue("The helpdesk connector could not be reached.");
        assertThat(org.reconnect()).as("still down").isFalse();

        helpdesk = HelpdeskConnector.onPort(port);

        assertThat(org.reconnect()).isTrue();
        assertThat(org.current().repo().version()).isEqualTo(version);
        assertThat(org.current().agent("helpdesk").orElseThrow().unavailable()).isEmpty();
        assertThat(org.current().connectors().failures()).isEmpty();
        assertThat(org.reconnect()).as("nothing left to reach").isFalse();
    }
}
