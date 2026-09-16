package dev.agentkit.workbench.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.workbench.domain.Approval;
import dev.agentkit.workbench.domain.Run;
import dev.agentkit.workbench.web.WebServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Two front doors, one workbench.
 *
 * <h2>The sharpest test the two-module split makes available</h2>
 *
 * <p>Everything else in this module checks the chat against itself. This checks it against the
 * <em>real dashboard</em> — the one in {@code agentkit-examples-workbench}, running here as a
 * dependency, over the same {@link dev.agentkit.workbench.store.WorkbenchStore} and the same
 * {@link dev.agentkit.workbench.runtime.Workbench} the chat's tools hold. A parity claim tested
 * against a second copy of the chat's own beliefs proves nothing; this one cannot pass unless
 * the two consoles are genuinely looking at the same thing.
 *
 * <p>It is also why {@code WorkbenchChatApp} starts both servers in one process rather than leaving
 * them to two. {@code WorkbenchStore} is an in-memory object: two processes are two workbenches
 * that share only Jira and the durable memory, and they would disagree about every run,
 * approval, rule and verdict (#379). Persisting the store would have fixed a restart, not this.
 *
 * <p>The dashboard is driven over HTTP, as a browser would, rather than through its Java
 * internals — the endpoint is what a person's screen actually calls, and a test that reached
 * past it could pass against a console nobody could use.
 */
class BothConsolesAgreeAboutWhatHappenedTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TENANT = Console.TENANT;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private Console console;
    private WebServer dashboard;

    /** One workbench, two front doors — the shape {@code WorkbenchChatApp} boots. */
    private void start(ConsoleAlm alm) throws Exception {
        console = new Console(alm);
        dashboard = new WebServer(0, console.store, TENANT, "scripted", alm, console.workbench,
                null, console.learnings, null);
        dashboard.start();
    }

    @AfterEach
    void stop() {
        if (dashboard != null) {
            dashboard.close();
        }
    }

    @Test
    void aRunStartedInTheChatIsInTheDashboardsList() throws Exception {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-1", "Grant access", "Add me."));
        start(alm);
        console.llm.says("Nothing to do here.");

        console.say("workbench.execute", "ticket_key", "IT-1");

        List<Map<String, Object>> runs = list("/api/runs");
        assertThat(runs).singleElement().satisfies(run -> {
            assertThat(run.get("ticketKey")).isEqualTo("IT-1");
            assertThat(run.get("trigger")).isEqualTo("OPERATOR");
        });
    }

    @Test
    void anApprovalDecidedOnTheDashboardSettlesTheChatsRun() throws Exception {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-2", "Grant access", "Add someone."));
        start(alm);
        Map<String, Object> comment =
                ScriptedLlm.args("ticket_key", "IT-2", "body", "Access granted.");
        console.llm.proposes("jira.add_comment", comment)
                .proposes("jira.add_comment", comment)
                .says("Commented and finished.");

        // The chat starts it and it parks, exactly as it would from either console.
        assertThat(console.say("workbench.execute", "ticket_key", "IT-2")).contains("stopped to ask");
        assertThat(alm.commentsWritten).isEmpty();

        String approvalId = String.valueOf(list("/api/approvals").getFirst().get("id"));
        // And a person sitting at the dashboard finishes it. This is the assertion the whole
        // class exists for: the two consoles are not two deployments that agree, they are two
        // windows onto one workbench.
        post("/api/approvals/" + approvalId + "/approve",
                "{\"by\":\"sid@example.com\",\"note\":\"Looks right.\"}");

        assertThat(alm.commentsWritten).containsExactly("IT-2: Access granted.");
        assertThat(console.store.approval(TENANT, approvalId).orElseThrow().state())
                .isEqualTo(Approval.State.CONSUMED);
        // And the chat can read the outcome, because it is the same run.
        assertThat(console.say("approvals.list")).contains("CONSUMED");
        assertThat(console.store.runs(TENANT))
                .anySatisfy(run -> assertThat(run.status()).isEqualTo(Run.Status.COMPLETED));
    }

    @Test
    void aRuleAutomatedInTheChatIsAutomatedOnTheDashboard() throws Exception {
        start(new ConsoleAlm(ConsoleAlm.open("IT-1", "Grant access", "Add me.")));

        console.say("rules.automate", "category", "access-request");

        assertThat(list("/api/rules")).singleElement().satisfies(rule -> {
            assertThat(rule.get("category")).isEqualTo("access-request");
            assertThat(rule.get("enabled")).isEqualTo(true);
        });
    }

    @Test
    void aRuleToggledOnTheDashboardIsToggledInTheChat() throws Exception {
        start(new ConsoleAlm(ConsoleAlm.open("IT-1", "Grant access", "Add me.")));
        console.say("rules.automate", "category", "access-request");
        String id = console.store.rules(TENANT).getFirst().id();

        post("/api/rules/" + id + "/toggle", "{}");

        assertThat(console.say("rules.list")).contains("[paused]");
        assertThat(console.store.automated(TENANT, "access-request")).isFalse();
    }

    @Test
    void anOperatorsCommentFromEitherConsoleLandsInOneAuditTrail() throws Exception {
        ConsoleAlm alm = new ConsoleAlm(ConsoleAlm.open("IT-1", "Grant access", "Add me."));
        start(alm);

        console.say("alm.comment", "ticket_key", "IT-1", "body", "From the chat.");
        post("/api/tickets/IT-1/comment",
                "{\"body\":\"From the dashboard.\",\"by\":\"sid@example.com\"}");

        // One ticket, one history. A person reading it later should not be able to tell which
        // window somebody was sitting in front of, only who did what.
        assertThat(alm.commentsWritten)
                .containsExactly("IT-1: From the chat.", "IT-1: From the dashboard.");
        assertThat(console.store.operatorActions(TENANT, "IT-1"))
                .extracting(one -> one.by() + ": " + one.detail())
                .containsExactly("sid@example.com: From the chat.",
                        "sid@example.com: From the dashboard.");
        // And the chat reads back both, including the one it did not make.
        assertThat(console.say("tickets.get", "ticket_key", "IT-1"))
                .contains("From the dashboard.");
    }

    @Test
    void theDashboardsToolInventoryIsTheOneTheChatReports() throws Exception {
        start(new ConsoleAlm(ConsoleAlm.open("IT-1", "Grant access", "Add me.")));

        List<String> onTheDashboard = list("/api/tools").stream()
                .map(tool -> String.valueOf(tool.get("name"))).toList();
        String inTheChat = console.say("workbench.capabilities");

        // What a run may do is one fact, and two consoles reporting different answers to it
        // would be two consoles disagreeing about what the agent is allowed to do — which is
        // the disagreement that matters most of all of them.
        assertThat(onTheDashboard).isNotEmpty();
        assertThat(onTheDashboard).allSatisfy(name -> assertThat(inTheChat).contains(name));
    }

    // --- driving the dashboard the way a browser does ---------------------------------

    private String url(String path) {
        return "http://localhost:" + dashboard.port() + path;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String path) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s said %s", path, response.body())
                .isEqualTo(200);
        return JSON.readValue(response.body(), List.class);
    }

    private void post(String path, String body) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url(path)))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("POST %s said %s", path, response.body())
                .isBetween(200, 299);
    }
}
