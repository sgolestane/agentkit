package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A restart does not strand the conversations pinned to earlier versions: the host notes each version it makes current,
 * and on opening loads the checkout and then the earlier versions it was serving, each taken out of the repository's
 * history. A version that was never committed cannot be, and is let go.
 */
class AVersionIsServedAgainAfterARestartTest {

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private final VersionLog log = VersionLog.inMemory();

    @BeforeEach
    void start() throws Exception {
        helpdesk = new HelpdeskConnector();
    }

    @AfterEach
    void stop() {
        helpdesk.close();
    }

    private OrgHost open(Path checkout) {
        return OrgHost.open(checkout, AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))), log);
    }

    private static String policy(OrgHost org, String version) {
        return org.version(version).orElseThrow().agent("helpdesk").orElseThrow().definition().policy();
    }

    @Test
    void theVersionsItWasServingAreLoadedAgainFromTheirCommits() {
        RepoFixture repo = RepoFixture.copyInto(dir);
        String v1 = repo.write("agents/helpdesk/policy.md", "Policy one.").commit("one");
        String v2;
        try (OrgHost org = open(repo.root())) {
            v2 = repo.write("agents/helpdesk/policy.md", "Policy two.").commit("two");
            org.reload();
            assertThat(org.versions()).containsExactly(v1, v2);
        }
        String v3 = repo.write("agents/helpdesk/policy.md", "Policy three.").commit("three");

        try (OrgHost restarted = open(repo.root())) {
            assertThat(restarted.current().repo().version()).isEqualTo(v3);
            assertThat(restarted.versions()).containsExactly(v1, v2, v3);
            assertThat(policy(restarted, v1)).isEqualTo("Policy one.");
            assertThat(policy(restarted, v2)).isEqualTo("Policy two.");
            assertThat(policy(restarted, v3)).isEqualTo("Policy three.");
        }
        assertThat(log.recent("acme", 5)).containsExactly(v3, v2, v1);
    }

    @Test
    void aCheckoutInsideALargerRepositoryIsTakenOutAsItsOwnDirectory() {
        Path checkout = dir.resolve("orgs").resolve("acme");
        RepoFixture repo = RepoFixture.copyInto(checkout);
        git(dir, "init", "-q");
        String v1 = commit(checkout, "agents/helpdesk/policy.md", "Policy one.");
        try (OrgHost org = open(checkout)) {
            assertThat(org.current().repo().version()).isEqualTo(v1);
        }
        String v2 = commit(checkout, "agents/helpdesk/policy.md", "Policy two.");

        try (OrgHost restarted = open(checkout)) {
            assertThat(restarted.versions()).containsExactly(v1, v2);
            assertThat(policy(restarted, v1)).isEqualTo("Policy one.");
        }
    }

    @Test
    void aWorkingTreeThatWasNeverCommittedIsLetGo() {
        RepoFixture repo = RepoFixture.copyInto(dir);
        String v1 = repo.commit("one");
        String dirty;
        try (OrgHost org = open(repo.root())) {
            repo.write("agents/helpdesk/policy.md", "Being edited.");
            org.reload();
            dirty = org.current().repo().version();
            assertThat(dirty).startsWith(v1 + "-dirty-");
        }
        String v2 = repo.write("agents/helpdesk/policy.md", "Committed.").commit("two");

        try (OrgHost restarted = open(repo.root())) {
            assertThat(restarted.versions()).containsExactly(v1, v2).doesNotContain(dirty);
        }
    }

    private static String commit(Path checkout, String file, String content) {
        try {
            java.nio.file.Files.writeString(checkout.resolve(file), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        git(checkout, "add", "-A", ".");
        git(checkout, "-c", "user.name=test", "-c", "user.email=test@example.com", "commit", "-q", "-m", file);
        return git(checkout, "rev-parse", "HEAD").strip();
    }

    private static String git(Path where, String... args) {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", where.toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
            }
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
