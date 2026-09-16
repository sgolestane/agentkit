package dev.agentkit.itops.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.itops.connector.TicketProvider;
import dev.agentkit.itops.domain.Ticket;
import dev.agentkit.itops.store.OpsStore;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A ticket id an outsider chose cannot become code on the page where a human approves
 * privileged actions (#192).
 *
 * <h2>Why the assertions read the page's source rather than a browser's DOM</h2>
 *
 * <p>The sink is JavaScript: {@code refresh()} builds markup in a template literal and
 * assigns it to {@code innerHTML}. There is no JavaScript engine on this module's test
 * classpath — Nashorn left the JDK, and adding a browser or a JS runtime to a demo's build
 * to check six lines of string handling is a worse trade than reading those six lines. So
 * the test reads {@code /ui/index.html} — the very bytes {@code serveUi} sends — and pins
 * the two properties that make the sink safe, one structural and one lexical:
 *
 * <ul>
 *   <li>no id is interpolated into an event-handler attribute, so nothing an id contains is
 *       ever parsed as JavaScript;</li>
 *   <li>the page's own {@code esc} table, applied here in Java exactly as the page declares
 *       it, closes every delimiter of the one context ids do reach.</li>
 * </ul>
 *
 * <p>The table is parsed out of the source rather than restated, so narrowing {@code esc}
 * fails this test instead of quietly disagreeing with a copy of itself.
 *
 * <h2>What it looked like unfixed</h2>
 *
 * <p>Driving the server below with {@code id} = {@code INC1" onmouseover="alert(...)} and
 * rendering the tickets branch through the page's own code produced:
 *
 * <pre>
 * &lt;tr class="clickable" onclick="openTicket('INC1" onmouseover="alert(document.cookie)')"&gt;
 * </pre>
 *
 * <p>— a handler beside the intended one, firing on hover, no click required. Against the
 * unfixed page two of the three tests here fail; the third is true either way, and is here
 * to establish that the id an outsider chose is what the page receives.
 *
 * <h2>What this does not reach</h2>
 *
 * <p>The delegated listener's wiring. Sending a ticket row's id to the wrong opener:
 *
 * <pre>
 * # index.html: openTicket(row.dataset.ticket) -&gt; openTicket(row.dataset.execution)
 * mvn -o -pl agentkit-examples-itops -am test   Tests run: 44, Failures: 0   SURVIVES
 * </pre>
 *
 * <p>Which is the price of reading source instead of clicking: this pins that an id cannot
 * become code, not that the right id reaches the right panel. Six other mutants — narrowing
 * {@code esc}'s class either way, mapping a quote to itself, restoring the {@code onclick},
 * dropping the {@code esc} around the id, and escaping the id server-side instead — are
 * killed.
 */
class OperatorConsoleEscapingTest {

    private static final String TENANT = "acme";

    /**
     * Two ways out of the nested contexts the id used to land in: the first closes the
     * JavaScript string, the second closes the HTML attribute around it.
     */
    private static final List<String> PAYLOADS = List.of(
            "INC0012345'),alert(document.cookie),openTicket('",
            "INC1\" onmouseover=\"alert(document.cookie)",
            "INC2</script><img src=x onerror=alert(1)>");

    /** Serves one ticket whose id is whatever the test chose. */
    private record OneTicket(Ticket ticket) implements TicketProvider {
        public String name() {
            return "servicenow";
        }

        public List<Ticket> searchRecent(String assignmentGroup, Duration lookback, int limit) {
            return List.of(ticket);
        }

        public List<Ticket> search(String query, int limit) {
            return List.of(ticket);
        }

        public Optional<Ticket> get(String id) {
            return Optional.of(ticket);
        }

        public List<Ticket.Comment> comments(String id) {
            return ticket.comments();
        }

        public Ticket assign(String id, String assignee, String assignmentGroup) {
            return ticket;
        }

        public Ticket comment(String id, String author, String body) {
            return ticket;
        }

        public Ticket updateStatus(String id, Ticket.Status status) {
            return ticket;
        }
    }

    /**
     * The id an outsider chose reaches the page unchanged, which is why the page is where
     * this has to be handled. Escaping in the JSON would corrupt the id for every other
     * consumer of the same payload and still leave the sink's own contexts open.
     */
    @Test
    void theApiHandsThePageTheIdVerbatim() throws Exception {
        for (String payload : PAYLOADS) {
            assertThat(ticketsJson(payload))
                    .as("id as delivered to the console")
                    .contains(quoteForJson(payload));
        }
    }

    /**
     * The structural half. An id in a {@code data-} attribute read back through
     * {@code dataset} is a string the whole way; an id in an {@code onclick} is source. No
     * handler attribute on this page may contain an interpolation, and the ticket id — the
     * one value here an outsider supplies — may not reach the DOM except through
     * {@code esc}.
     */
    @Test
    void noIdIsInterpolatedIntoAnEventHandler() {
        String page = page();

        Matcher handler = Pattern.compile("\\son[a-z]+\\s*=\\s*\"([^\"]*)\"").matcher(page);
        while (handler.find()) {
            assertThat(handler.group(1))
                    .as("event-handler attribute %s", handler.group())
                    .doesNotContain("${");
        }

        Matcher id = Pattern.compile("\\bt\\.id\\b").matcher(page);
        int seen = 0;
        while (id.find()) {
            seen++;
            assertThat(page.substring(Math.max(0, id.start() - 4), id.start()))
                    .as("the ticket id at offset %d reaches the DOM through esc", id.start())
                    .isEqualTo("esc(");
        }
        assertThat(seen).as("occurrences of the ticket id in the page").isGreaterThan(0);
    }

    /**
     * The lexical half, run against the page's own table. Quotes matter because the context
     * ids do reach is a quoted attribute, and the round-trip matters because a console that
     * neutralises an id into a different id sends the operator to the wrong ticket.
     */
    @Test
    void thePagesEscapeClosesEveryDelimiterAndStillRoundTrips() {
        Map<String, String> table = escapeTable(page());

        assertThat(table.keySet())
                .as("the alphabet esc declares")
                .contains("&", "<", ">", "\"", "'");

        for (String payload : PAYLOADS) {
            String escaped = escape(table, payload);
            assertThat(escaped)
                    .as("%s, escaped for a quoted attribute", payload)
                    .doesNotContain("\"").doesNotContain("'")
                    .doesNotContain("<").doesNotContain(">");
            assertThat(unescape(table, escaped))
                    .as("%s survives the round trip the operator's click depends on", payload)
                    .isEqualTo(payload);
        }
    }

    // --- driving the server -------------------------------------------------------

    private static String ticketsJson(String id) throws Exception {
        Ticket ticket = new Ticket(id, "servicenow", "Laptop won't boot", "Please help.",
                Ticket.Status.OPEN, "Service Desk", null,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                List.of());
        // Only the ticket routes are exercised, so the runtime collaborators stay null
        // rather than being stood up to be ignored.
        try (WebServer web = new WebServer(0, new OpsStore(), TENANT, new OneTicket(ticket),
                null, null, null, null, List.of())) {
            web.start();
            return HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(
                                    URI.create("http://localhost:" + web.port() + "/api/tickets"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .body();
        }
    }

    /** The bytes {@code serveUi} sends, read the same way it reads them. */
    private static String page() {
        try (InputStream in = WebServer.class.getResourceAsStream("/ui/index.html")) {
            assertThat(in).as("the console the server serves").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            throw new AssertionError(unreadable);
        }
    }

    // --- the page's own escaping, as declared ------------------------------------

    /**
     * Reads {@code esc}'s character class and replacement literal out of the page, so this
     * test measures what the page does rather than what a copy of it here says.
     */
    private static Map<String, String> escapeTable(String page) {
        int start = page.indexOf("const esc =");
        assertThat(start).as("esc is declared").isNotNegative();
        String source = page.substring(start, page.indexOf("}[c]));", start));

        Matcher alphabet = Pattern.compile("\\.replace\\(/\\[([^\\]]*)]/g").matcher(source);
        assertThat(alphabet.find()).as("esc escapes by character class").isTrue();
        String declared = alphabet.group(1);

        Map<String, String> table = new LinkedHashMap<>();
        Matcher pair = Pattern.compile("(['\"])(.)\\1\\s*:\\s*(['\"])(&#?\\w+;)\\3")
                .matcher(source);
        while (pair.find()) {
            table.put(pair.group(2), pair.group(4));
        }
        // A character in the class with no replacement maps to undefined at runtime and
        // silently eats the character, which is worse than not escaping it.
        assertThat(table.keySet())
                .as("every character esc matches has a replacement")
                .containsExactlyInAnyOrderElementsOf(
                        declared.chars().mapToObj(Character::toString).toList());
        return table;
    }

    private static String escape(Map<String, String> table, String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            String character = String.valueOf(value.charAt(i));
            out.append(table.getOrDefault(character, character));
        }
        return out.toString();
    }

    private static String unescape(Map<String, String> table, String value) {
        String out = value;
        for (Map.Entry<String, String> entry : table.entrySet()) {
            // '&' last: its entity is a prefix of nothing, but its replacement is what every
            // other entity starts with, so undoing it first would re-form the others.
            if (!entry.getKey().equals("&")) {
                out = out.replace(entry.getValue(), entry.getKey());
            }
        }
        return out.replace(table.get("&"), "&");
    }

    /** The id as Jackson writes it into the response, so a match is a match on the wire. */
    private static String quoteForJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
