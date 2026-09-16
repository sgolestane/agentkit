package dev.agentkit.workbench.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentkit.workbench.domain.Ticket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Jira over its real REST API — the production {@link Alm}.
 *
 * <p>Authenticates the way Atlassian Cloud expects a person's integration to: HTTP Basic
 * with the account email and an API token, so the workbench sees exactly the tickets the
 * signed-in agent is allowed to see. That is the workbench's onboarding story — no routing
 * changes, no service account provisioning; the human connects with the identity they
 * already have.
 *
 * <p>Search goes to {@code /rest/api/2/search/jql} first (Jira Cloud retired the classic
 * search endpoint in 2025) and falls back to {@code /rest/api/2/search} on 404/410, so a
 * Data Center instance works unmodified. Everything else is the stable v2 issue API, chosen
 * over v3 because v2 returns descriptions and comments as plain text rather than ADF.
 *
 * <p>All configuration comes from the environment:
 * {@code JIRA_BASE_URL} (e.g. {@code https://yourco.atlassian.net}), {@code JIRA_EMAIL},
 * {@code JIRA_API_TOKEN}, and optionally {@code WORKBENCH_JQL} to scope the inbox (default:
 * unresolved tickets, newest first).
 */
public final class JiraClient implements Alm {

    /** The inbox scope when the deployment does not state one. */
    public static final String DEFAULT_JQL = "resolution = EMPTY ORDER BY updated DESC";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter JIRA_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT);
    private static final String FIELDS =
            "summary,description,status,priority,assignee,reporter,created,updated";

    private final HttpTransport transport;
    private final String baseUrl;
    private final String authorization;
    private final String inboxJql;

    /** Cached after the first successful call; identity does not change mid-process. */
    private volatile Me myself;

    public JiraClient(HttpTransport transport, String baseUrl, String email, String apiToken,
            String inboxJql) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(apiToken, "apiToken");
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((email + ':' + apiToken).getBytes(StandardCharsets.UTF_8));
        this.inboxJql = inboxJql == null || inboxJql.isBlank() ? DEFAULT_JQL : inboxJql;
    }

    /**
     * The client the environment describes, or empty with a reason when it describes none.
     * Empty rather than thrown so the console can boot unconfigured and say what is missing.
     */
    public static Optional<JiraClient> fromEnv() {
        String base = System.getenv("JIRA_BASE_URL");
        String email = System.getenv("JIRA_EMAIL");
        String token = System.getenv("JIRA_API_TOKEN");
        if (base == null || base.isBlank() || email == null || email.isBlank()
                || token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new JiraClient(HttpTransport.overTheWire(), base, email, token,
                System.getenv("WORKBENCH_JQL")));
    }

    @Override
    public String name() {
        return "jira";
    }

    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public Me myself() {
        Me cached = myself;
        if (cached != null) {
            return cached;
        }
        JsonNode me = get("/rest/api/2/myself");
        Me resolved = new Me(text(me, "accountId"), text(me, "displayName"),
                text(me, "emailAddress"));
        myself = resolved;
        return resolved;
    }

    @Override
    public List<Ticket> inbox(int limit) {
        return searchJql(inboxJql, limit);
    }

    @Override
    public List<Ticket> search(String textQuery, int limit) {
        // The free text goes into JQL as a quoted term. Escaping the quotes and backslashes
        // is what keeps a search string a search string rather than a JQL fragment.
        String escaped = textQuery.replace("\\", "\\\\").replace("\"", "\\\"");
        return searchJql("text ~ \"" + escaped + "\" ORDER BY updated DESC", limit);
    }

    private List<Ticket> searchJql(String jql, int limit) {
        String query = "jql=" + encode(jql) + "&maxResults=" + Math.max(1, limit)
                + "&fields=" + encode(FIELDS);
        HttpTransport.Response response = raw("GET", "/rest/api/2/search/jql?" + query, null);
        if (response.status() == 404 || response.status() == 410) {
            // Data Center still serves the classic endpoint; Cloud no longer does. Same
            // request shape, same response shape for the fields asked for.
            response = raw("GET", "/rest/api/2/search?" + query, null);
        }
        JsonNode body = parsed(response, "search");
        List<Ticket> tickets = new ArrayList<>();
        for (JsonNode issue : body.path("issues")) {
            tickets.add(toTicket(issue));
        }
        return List.copyOf(tickets);
    }

    @Override
    public Optional<Ticket> ticket(String key) {
        HttpTransport.Response response =
                raw("GET", "/rest/api/2/issue/" + encode(key) + "?fields=" + encode(FIELDS), null);
        if (response.status() == 404) {
            return Optional.empty();
        }
        return Optional.of(toTicket(parsed(response, "issue " + key)));
    }

    @Override
    public List<Ticket.Comment> comments(String key) {
        JsonNode body = get("/rest/api/2/issue/" + encode(key) + "/comment");
        List<Ticket.Comment> comments = new ArrayList<>();
        for (JsonNode comment : body.path("comments")) {
            comments.add(new Ticket.Comment(
                    text(comment.path("author"), "displayName"),
                    text(comment, "body"),
                    time(text(comment, "created"))));
        }
        return List.copyOf(comments);
    }

    @Override
    public void addComment(String key, String body) {
        ObjectNode payload = JSON.createObjectNode().put("body", body);
        post("/rest/api/2/issue/" + encode(key) + "/comment", payload.toString());
    }

    @Override
    public List<Transition> transitions(String key) {
        JsonNode body = get("/rest/api/2/issue/" + encode(key) + "/transitions");
        List<Transition> transitions = new ArrayList<>();
        for (JsonNode transition : body.path("transitions")) {
            transitions.add(new Transition(text(transition, "id"), text(transition, "name")));
        }
        return List.copyOf(transitions);
    }

    @Override
    public void transition(String key, String transitionName) {
        Transition match = transitions(key).stream()
                .filter(candidate -> candidate.toName().equalsIgnoreCase(transitionName.strip()))
                .findFirst()
                .orElseThrow(() -> new AlmException("Jira offers no transition named '"
                        + transitionName + "' for " + key + " right now.", 400));
        ObjectNode payload = JSON.createObjectNode();
        payload.putObject("transition").put("id", match.id());
        post("/rest/api/2/issue/" + encode(key) + "/transitions", payload.toString());
    }

    @Override
    public void assignToMe(String key) {
        Me me = myself();
        ObjectNode payload = JSON.createObjectNode();
        if (me.accountId() != null && !me.accountId().isBlank()) {
            payload.put("accountId", me.accountId());
        } else {
            // Data Center has no account ids; assignment is by username there.
            payload.put("name", me.displayName());
        }
        HttpTransport.Response response =
                raw("PUT", "/rest/api/2/issue/" + encode(key) + "/assignee", payload.toString());
        require(response, "assign " + key);
    }

    // --- plumbing -----------------------------------------------------------------

    private JsonNode get(String path) {
        return parsed(raw("GET", path, null), path);
    }

    private void post(String path, String body) {
        require(raw("POST", path, body), path);
    }

    private HttpTransport.Response raw(String method, String path, String body) {
        Map<String, String> headers = body == null
                ? Map.of("Authorization", authorization, "Accept", "application/json")
                : Map.of("Authorization", authorization, "Accept", "application/json",
                        "Content-Type", "application/json");
        return transport.send(method, URI.create(baseUrl + path), headers, body);
    }

    private static JsonNode parsed(HttpTransport.Response response, String what) {
        require(response, what);
        try {
            return JSON.readTree(response.body() == null ? "" : response.body());
        } catch (Exception unparseable) {
            throw new AlmException("Jira returned a response that is not JSON for " + what + ".",
                    unparseable);
        }
    }

    private static void require(HttpTransport.Response response, String what) {
        if (response.status() >= 200 && response.status() < 300) {
            return;
        }
        // Jira's error bodies quote user-written text; bounded so a failure message stays a
        // message rather than a copy of the ticket.
        String detail = response.body() == null ? "" : response.body();
        if (detail.length() > 300) {
            detail = detail.substring(0, 300) + "…";
        }
        throw new AlmException("Jira answered " + response.status() + " for " + what
                + (detail.isBlank() ? "." : ": " + detail), response.status());
    }

    private Ticket toTicket(JsonNode issue) {
        JsonNode fields = issue.path("fields");
        String categoryKey = fields.path("status").path("statusCategory").path("key")
                .asText("new");
        Ticket.Category category = switch (categoryKey) {
            case "done" -> Ticket.Category.DONE;
            case "indeterminate" -> Ticket.Category.IN_PROGRESS;
            default -> Ticket.Category.OPEN;
        };
        return new Ticket(
                text(issue, "key"),
                name(),
                text(fields, "summary") == null ? "" : text(fields, "summary"),
                text(fields, "description"),
                fields.path("status").path("name").asText("Unknown"),
                category,
                fields.path("priority").isMissingNode() || fields.path("priority").isNull()
                        ? null : fields.path("priority").path("name").asText(null),
                fields.path("assignee").isNull() || fields.path("assignee").isMissingNode()
                        ? null : fields.path("assignee").path("displayName").asText(null),
                fields.path("reporter").isNull() || fields.path("reporter").isMissingNode()
                        ? null : fields.path("reporter").path("displayName").asText(null),
                time(text(fields, "created")),
                time(text(fields, "updated")));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /** Jira writes {@code 2026-08-28T09:15:00.000+0000}; ISO-8601 parsers disagree on it. */
    private static Instant time(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return OffsetDateTime.parse(value, JIRA_TIME).toInstant();
        } catch (DateTimeParseException notJiraShaped) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException unknown) {
                return Instant.EPOCH;
            }
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
