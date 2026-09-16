package dev.agentkit.workbench.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.workbench.domain.Ticket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The client's parsing, routing and auth against canned Jira responses — the wire format is
 * the contract a real instance answers with, so these fixtures are copied from Jira Cloud's
 * own response shapes.
 */
class TheJiraClientSpeaksJiraTest {

    /** Records every request and answers from a routing table. */
    static final class CannedTransport implements HttpTransport {
        record Sent(String method, URI uri, Map<String, String> headers, String body) {}

        final List<Sent> sent = new ArrayList<>();
        final Map<String, Response> routes = new java.util.LinkedHashMap<>();

        @Override
        public Response send(String method, URI uri, Map<String, String> headers, String body) {
            sent.add(new Sent(method, uri, headers, body));
            String key = method + ' ' + uri.getPath();
            return routes.entrySet().stream()
                    .filter(route -> key.startsWith(route.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(new Response(404, "{}"));
        }
    }

    private static final String ISSUE_JSON = """
            {"key":"IT-42","fields":{
              "summary":"Create a mailbox for John Smith",
              "description":"New starter Monday.",
              "status":{"name":"To Do","statusCategory":{"key":"new"}},
              "priority":{"name":"High"},
              "assignee":null,
              "reporter":{"displayName":"Riley Chen"},
              "created":"2026-08-27T09:15:00.000+0000",
              "updated":"2026-08-28T10:00:00.000+0000"}}""";

    private static JiraClient client(CannedTransport transport) {
        return new JiraClient(transport, "https://acme.atlassian.net/", "sid@example.com",
                "token-123", null);
    }

    @Test
    void searchUsesTheNewEndpointAndParsesIssues() {
        CannedTransport transport = new CannedTransport();
        transport.routes.put("GET /rest/api/2/search/jql",
                new HttpTransport.Response(200, "{\"issues\":[" + ISSUE_JSON + "]}"));
        List<Ticket> tickets = client(transport).inbox(10);

        assertThat(tickets).hasSize(1);
        Ticket ticket = tickets.get(0);
        assertThat(ticket.key()).isEqualTo("IT-42");
        assertThat(ticket.summary()).isEqualTo("Create a mailbox for John Smith");
        assertThat(ticket.statusCategory()).isEqualTo(Ticket.Category.OPEN);
        assertThat(ticket.priority()).isEqualTo("High");
        assertThat(ticket.assignee()).isNull();
        assertThat(ticket.reporter()).isEqualTo("Riley Chen");
        assertThat(ticket.createdAt().toString()).isEqualTo("2026-08-27T09:15:00Z");
        // The request carried Basic auth built from email:token, and the default JQL.
        CannedTransport.Sent request = transport.sent.get(0);
        assertThat(request.headers().get("Authorization")).startsWith("Basic ");
        assertThat(request.uri().getQuery()).contains("jql=");
    }

    @Test
    void searchFallsBackToTheClassicEndpointWhereTheNewOneIsAbsent() {
        CannedTransport transport = new CannedTransport();
        // Data Center: /search/jql is 404, /search answers.
        transport.routes.put("GET /rest/api/2/search/jql", new HttpTransport.Response(404, ""));
        transport.routes.put("GET /rest/api/2/search",
                new HttpTransport.Response(200, "{\"issues\":[" + ISSUE_JSON + "]}"));
        assertThat(client(transport).inbox(5)).hasSize(1);
        assertThat(transport.sent).hasSize(2);
        assertThat(transport.sent.get(1).uri().getPath()).isEqualTo("/rest/api/2/search");
    }

    @Test
    void aMissingTicketIsAnEmptyAnswerNotAFault() {
        CannedTransport transport = new CannedTransport();
        assertThat(client(transport).ticket("IT-404")).isEmpty();
    }

    @Test
    void addingACommentPostsTheV2Body() throws Exception {
        CannedTransport transport = new CannedTransport();
        transport.routes.put("POST /rest/api/2/issue/IT-42/comment",
                new HttpTransport.Response(201, "{}"));
        client(transport).addComment("IT-42", "Done, see you Monday.");
        CannedTransport.Sent request = transport.sent.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(request.body()).path("body").asText())
                .isEqualTo("Done, see you Monday.");
    }

    @Test
    void aTransitionIsResolvedByNameAndPostedById() {
        CannedTransport transport = new CannedTransport();
        transport.routes.put("GET /rest/api/2/issue/IT-42/transitions",
                new HttpTransport.Response(200,
                        "{\"transitions\":[{\"id\":\"21\",\"name\":\"In Progress\"},"
                                + "{\"id\":\"31\",\"name\":\"Done\"}]}"));
        transport.routes.put("POST /rest/api/2/issue/IT-42/transitions",
                new HttpTransport.Response(204, ""));
        client(transport).transition("IT-42", "done");
        CannedTransport.Sent posted = transport.sent.get(transport.sent.size() - 1);
        assertThat(posted.method()).isEqualTo("POST");
        assertThat(posted.body()).contains("\"31\"");
    }

    @Test
    void anUnofferedTransitionIsRefusedWithTheNamesInPlay() {
        CannedTransport transport = new CannedTransport();
        transport.routes.put("GET /rest/api/2/issue/IT-42/transitions",
                new HttpTransport.Response(200, "{\"transitions\":[]}"));
        assertThatThrownBy(() -> client(transport).transition("IT-42", "Done"))
                .isInstanceOf(AlmException.class)
                .hasMessageContaining("no transition named");
    }

    @Test
    void assignmentUsesTheSignedInAccountId() {
        CannedTransport transport = new CannedTransport();
        transport.routes.put("GET /rest/api/2/myself", new HttpTransport.Response(200,
                "{\"accountId\":\"abc123\",\"displayName\":\"Sid\","
                        + "\"emailAddress\":\"sid@example.com\"}"));
        transport.routes.put("PUT /rest/api/2/issue/IT-42/assignee",
                new HttpTransport.Response(204, ""));
        client(transport).assignToMe("IT-42");
        CannedTransport.Sent put = transport.sent.get(transport.sent.size() - 1);
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.body()).contains("abc123");
    }

    @Test
    void aProviderFaultCarriesTheStatusAndABoundedBody() {
        CannedTransport transport = new CannedTransport();
        transport.routes.put("GET /rest/api/2/issue/IT-42/comment",
                new HttpTransport.Response(500, "boom ".repeat(200)));
        assertThatThrownBy(() -> client(transport).comments("IT-42"))
                .isInstanceOf(AlmException.class)
                .hasMessageContaining("500")
                .extracting(thrown -> ((AlmException) thrown).getMessage().length())
                .satisfies(length -> assertThat((int) length).isLessThan(500));
    }

    @Test
    void anUnconfiguredEnvironmentYieldsNoClient() {
        // The env in a test JVM has no JIRA_* values; fromEnv must say so, not throw.
        Optional<JiraClient> client = JiraClient.fromEnv();
        if (System.getenv("JIRA_BASE_URL") == null) {
            assertThat(client).isEmpty();
        }
    }
}
