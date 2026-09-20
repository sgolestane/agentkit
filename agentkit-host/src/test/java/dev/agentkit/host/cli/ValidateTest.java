package dev.agentkit.host.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code validate} is a pull request's check on an organization's agents: it passes a repository the host would
 * load, fails one it would refuse with the same problems, and under GitHub Actions puts each problem on the line of
 * the pull request it belongs to.
 */
class ValidateTest {

    @TempDir
    Path dir;

    @Test
    void aRepositoryTheHostWouldLoadPassesAndSaysWhatItHolds() throws Exception {
        Output out = new Output();
        int status = Validate.run(List.of(copy().toString()), Map.of(), out.stream);

        assertThat(status).isZero();
        assertThat(out.text()).contains("acme @ unversioned: 2 agent(s), 1 connector(s); the files are valid.")
                .contains("helpdesk — IT Helpdesk (tools from helpdesk)")
                .contains("set AGENTKIT_VALIDATE_CONNECT=true");
    }

    @Test
    void aRepositoryWithProblemsFailsWithEachOneAndItsPlaceForThePullRequest() throws Exception {
        Path repo = copy();
        Path agent = repo.resolve("agents/helpdesk/agent.yaml");
        Files.writeString(agent, Files.readString(agent).replace("confirm:", "confrim:")
                .replace("effects: [read, request, notify, grant]", "effects: [read, delete]"));

        Output out = new Output();
        int status = Validate.run(List.of(repo.toString()), Map.of("GITHUB_ACTIONS", "true",
                "AGENTKIT_VALIDATE_PATH_PREFIX", "orgs/acme/"), out.stream);

        assertThat(status).isEqualTo(1);
        assertThat(out.text()).contains("2 problems:")
                .contains("agents/helpdesk/agent.yaml confrim: is not a field here")
                .contains("::error file=orgs/acme/agents/helpdesk/agent.yaml,title=confrim::is not a field here; the fields "
                        + "are [audience, before, bind, confirm, deferred, description, input, limits, mcp, model, name, pattern, plans, prompt, tools]")
                .contains("::error file=orgs/acme/agents/helpdesk/agent.yaml,title=tools[0].effects::unknown effect "
                        + "\"delete\"");
    }

    @Test
    void connectingChecksTheAgentsAgainstTheirConnectorsAndDescribesWhatEachCanDo() throws Exception {
        try (var helpdesk = new dev.agentkit.host.HelpdeskConnectorAccess()) {
            Output out = new Output();
            int status = Validate.run(List.of(copy().toString()), Map.of("AGENTKIT_VALIDATE_CONNECT", "true",
                    "AGENTKIT_SECRET_ACME_HELPDESK_URL", helpdesk.url(),
                    "AGENTKIT_SECRET_ACME_HELPDESK_TOKEN", helpdesk.token()), out.stream);

            assertThat(status).as(out.text()).isZero();
            assertThat(out.text()).contains("2 agent(s) checked against their connectors; valid.")
                    .contains("grant: reset_mfa [confirmed]")
                    .contains("request: open_ticket")
                    .contains("bound: helpdesk/open_ticket {requester=principal.email}")
                    .contains("offered directly over MCP: helpdesk/directory_lookup")
                    .doesNotContain("delete_account");
        }

        Output missing = new Output();
        assertThat(Validate.run(List.of(copy().toString()), Map.of("AGENTKIT_VALIDATE_CONNECT", "true"), missing.stream))
                .isEqualTo(1);
        assertThat(missing.text()).contains("connectors/helpdesk.yaml: the secret HELPDESK_URL is not set");
    }

    @Test
    void withNoRepositoryItSaysWhatItNeeds() {
        Output out = new Output();
        assertThat(Validate.run(List.of(""), Map.of(), out.stream)).isEqualTo(2);
        assertThat(out.text()).contains("Name the repository to validate");
    }

    private int copies;

    private Path copy() throws IOException, URISyntaxException {
        Path source = Path.of(getClass().getClassLoader().getResource("repos/acme").toURI());
        Path target = dir.resolve("acme-" + copies++);
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path to = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(file, to);
                }
            }
        }
        return target;
    }

    private static final class Output {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
