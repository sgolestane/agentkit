package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two instances serve an organization, each from its own checkout of the repository, and do not change version at the
 * same moment. A conversation one of them pinned to a version the other has not loaded is served by the other all the
 * same — from its checkout if it has caught up, or from the repository's history — and a version no instance served
 * is not loaded for the asking.
 */
class AVersionPinnedByAnotherInstanceIsServedHereTest {

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private final List<OrgHost> hosts = new ArrayList<>();

    @AfterEach
    void stop() {
        hosts.forEach(OrgHost::close);
        if (helpdesk != null) {
            helpdesk.close();
        }
    }

    @Test
    void aVersionAnotherInstanceMadeCurrentIsServedHereFromHistoryOrTheCheckout() throws Exception {
        helpdesk = new HelpdeskConnector();
        AgentHost.Options options = AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN)));
        VersionLog shared = VersionLog.inMemory();
        RepoFixture repo = RepoFixture.copyInto(dir.resolve("a"));
        String v1 = repo.commit("one");
        Path checkoutB = dir.resolve("b");
        git(dir, "clone", "-q", repo.root().toString(), checkoutB.toString());
        OrgHost a = open(repo.root(), options, shared);
        OrgHost b = open(checkoutB, options, shared);

        repo.edit("agents/helpdesk/policy.md", "Helpdesk policy:", "Helpdesk policy, two:");
        String v2 = repo.commit("two");
        a.reload();

        assertThat(b.serving("0123456789abcdef0123456789abcdef01234567")).as("no instance served it").isEmpty();

        git(checkoutB, "fetch", "-q", "origin");
        assertThat(b.serving(v2)).as("taken out of b's history").isPresent()
                .get().satisfies(v -> assertThat(v.repo().version()).isEqualTo(v2));
        assertThat(b.current().repo().version()).as("b's checkout is still at one").isEqualTo(v1);
        assertThat(b.versions()).containsExactlyInAnyOrder(v1, v2);

        repo.edit("agents/helpdesk/policy.md", "Helpdesk policy, two:", "Helpdesk policy, three:");
        String v3 = repo.commit("three");
        a.reload();
        git(checkoutB, "pull", "-q", "--ff-only");

        assertThat(b.serving(v3)).as("b's checkout caught up").isPresent();
        assertThat(b.current().repo().version()).isEqualTo(v3);
    }

    private OrgHost open(Path checkout, AgentHost.Options options, VersionLog log) {
        OrgHost host = OrgHost.open(checkout, options, log);
        hosts.add(host);
        return host;
    }

    private static void git(Path in, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", in.toString(), "-c", "user.name=test",
                "-c", "user.email=test@example.com"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + output);
        }
    }
}
