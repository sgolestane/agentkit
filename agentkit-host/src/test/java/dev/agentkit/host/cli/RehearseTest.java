package dev.agentkit.host.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.ProposedCall;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.host.HelpdeskConnectorAccess;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code rehearse} is a pull request's second check: it rehearses the eval cases of the agents the change touches —
 * all of them when something every agent depends on changed — and reports what held, what did not and what each agent
 * would have done, on the pull request and in its summary.
 */
class RehearseTest {

    private static final String EVALS = """
            cases:
              - name: locked-out
                as: priya.natarajan@acme.example
                say: I lost my phone and can't sign in.
                expect:
                  - calls: open_ticket
                  - never: reset_mfa
            """;

    @TempDir
    Path dir;

    private HelpdeskConnectorAccess helpdesk;
    private Path repo;

    @BeforeEach
    void start() throws Exception {
        helpdesk = new HelpdeskConnectorAccess();
        repo = dir.resolve("acme");
        Path source = Path.of(getClass().getClassLoader().getResource("repos/acme").toURI());
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path to = repo.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(file, to);
                }
            }
        }
        Files.writeString(repo.resolve("agents/helpdesk/evals.yaml"), EVALS);
        git("init", "-q");
        commit();
    }

    @AfterEach
    void stop() {
        helpdesk.close();
    }

    @Test
    void onlyTheAgentsAChangeTouchesAreRehearsedAndOneWithoutCasesIsFlagged() throws Exception {
        Output unchanged = new Output();
        assertThat(Rehearse.run(List.of(repo.toString()), env(Map.of("AGENTKIT_REHEARSE_SINCE", "HEAD")),
                unchanged.stream, Optional.of(new Scripted()))).isZero();
        assertThat(unchanged.text()).contains("no agent changed; nothing to rehearse.");

        Files.writeString(repo.resolve("agents/helpdesk/policy.md"), "Open a ticket for anything lost.");
        Files.writeString(repo.resolve("agents/security-desk/policy.md"), "Changed too.");
        Path summary = dir.resolve("summary.md");
        Path report = dir.resolve("report.json");
        // The host, taking the report for its admin view.
        List<String> posted = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer host = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        host.createContext("/host/rehearsals/acme", exchange -> {
            posted.add(exchange.getRequestHeaders().getFirst("Authorization") + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        host.start();
        Output out = new Output();
        int status;
        try {
            status = Rehearse.run(List.of(repo.toString()), env(Map.of("AGENTKIT_REHEARSE_SINCE", "HEAD",
                    "GITHUB_ACTIONS", "true", "GITHUB_STEP_SUMMARY", summary.toString(),
                    "AGENTKIT_REHEARSE_REPORT", report.toString(), "AGENTKIT_VALIDATE_PATH_PREFIX", "orgs/acme/",
                    "AGENTKIT_REHEARSE_POST_URL", "http://127.0.0.1:" + host.getAddress().getPort() + "/",
                    "AGENTKIT_REHEARSE_POST_TOKEN", "report-token",
                    "AGENTKIT_REHEARSE_PULL_REQUEST", "https://github.com/acme/agents/pull/7")),
                    out.stream, Optional.of(new Scripted(
                            call("open_ticket", Map.of("summary", "Lost phone")), text("I would open a ticket."))));
        } finally {
            host.stop(0);
        }
        assertThat(posted).singleElement().satisfies(p -> assertThat(p).startsWith("Bearer report-token {")
                .contains("\"pullRequest\" : \"https://github.com/acme/agents/pull/7\"").contains("\"held\" : 1"));
        assertThat(out.text()).contains("The report was sent to http://127.0.0.1:");

        assertThat(status).as(out.text()).isZero();
        assertThat(out.text()).contains("helpdesk — IT Helpdesk: 1 case(s)")
                .contains("PASS  locked-out (as priya.natarajan@acme.example, completed")
                .contains("would request: open_ticket {summary=Lost phone}")
                .contains("::warning file=orgs/acme/agents/security-desk/agent.yaml,title=No eval cases::")
                .contains("1 of 1 case(s) held; no cases for [security-desk].");
        assertThat(Files.readString(summary)).contains("## Rehearsal of acme")
                .contains("| helpdesk | locked-out | priya.natarajan@acme.example | ✅ held |")
                .contains("- request: `open_ticket` {summary=Lost phone}")
                .contains("⚠️ Changed with no eval cases: security-desk");
        JsonNode json = new ObjectMapper().readTree(report.toFile());
        assertThat(json.path("held").asInt()).isEqualTo(1);
        assertThat(json.path("results").get(0).path("calls").get(0).path("would").asText()).isEqualTo("request");
    }

    @Test
    void aChangeEveryAgentDependsOnRehearsesThemAllAndAFailedCaseFailsTheCheck() throws Exception {
        Files.writeString(repo.resolve("org.yaml"), Files.readString(repo.resolve("org.yaml")) + "\n# a comment\n");
        Output out = new Output();
        int status = Rehearse.run(List.of(repo.toString()), env(Map.of("AGENTKIT_REHEARSE_SINCE", "HEAD",
                "GITHUB_ACTIONS", "true")), out.stream, Optional.of(new Scripted(
                        call("reset_mfa", Map.of()), text("I would reset your MFA."))));

        assertThat(status).isEqualTo(1);
        assertThat(out.text()).contains("org.yaml changed, which every agent depends on; rehearsing them all.")
                .contains("FAIL  locked-out")
                .contains("::error file=agents/helpdesk/evals.yaml,title=helpdesk / locked-out::")
                .contains("0 of 1 case(s) held");
    }

    @Test
    void withoutAModelNothingIsRehearsed() {
        Output out = new Output();
        assertThat(Rehearse.run(List.of(repo.toString()), env(Map.of()), out.stream, Optional.empty())).isEqualTo(2);
        assertThat(out.text()).contains("set OPENROUTER_API_KEY");
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, String> env(Map<String, String> more) {
        Map<String, String> env = new HashMap<>(more);
        env.put("AGENTKIT_SECRET_ACME_HELPDESK_URL", helpdesk.url());
        env.put("AGENTKIT_SECRET_ACME_HELPDESK_TOKEN", helpdesk.token());
        return env;
    }

    private void commit() throws Exception {
        git("add", "-A", ".");
        git("-c", "user.name=test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "agents");
    }

    private void git(String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git failed: " + output);
        }
    }

    private static LlmResponse text(String text) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, TextBlock.of(text)), LlmStopReason.END_TURN, TokenUsage.ZERO);
    }

    private static LlmResponse call(String tool, Map<String, Object> input) {
        return LlmResponse.of(Message.of(Role.ASSISTANT, ProposedCall.of("c-" + tool, tool, input)),
                LlmStopReason.TOOL_USE, TokenUsage.ZERO);
    }

    /** A model that says what it is told, in order. */
    private static final class Scripted implements LlmClient {
        private final Deque<LlmResponse> script;

        Scripted(LlmResponse... responses) {
            this.script = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public synchronized LlmResponse generate(LlmRequest request) {
            if (script.isEmpty()) {
                throw new IllegalStateException("The script ran out");
            }
            return script.poll();
        }
    }

    private static final class Output {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
