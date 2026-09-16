package dev.agentkit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.core.tool.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A tool that brought its own interface.
 *
 * <h2>What MCP Apps is, in one paragraph</h2>
 *
 * <p>SEP-1865, final on 2026-01-26 and the first official extension to MCP. A server
 * predeclares a {@code ui://} resource holding a self-contained page and a tool points at it
 * from {@code _meta.ui.resourceUri}; a host that understands the extension fetches the
 * resource and renders it beside the result, and a host that does not sees an ordinary tool.
 * That fallback is the reason the whole thing is metadata, and it is why every refusal below
 * degrades to "an ordinary tool" rather than to an error.
 *
 * <h2>The refusals are the interesting part</h2>
 *
 * <p>This host is about to fetch a URI an untrusted server chose and hand the bytes to a
 * renderer. Three checks stand between those two facts, and each of them is the difference
 * between supporting an extension and having a hole.
 */
class AnMcpAppBecomesSomethingToLookAtTest {

    private static final String PAGE = """
            <!doctype html><meta charset="utf-8">
            <div id="root">A chart of the open tickets.</div>
            <script>window.addEventListener('message', () => {});</script>""";

    /** A server that advertises one tool and answers reads from a fixed table. */
    private static class FakeServer implements McpConnection {
        private final Map<String, Object> meta;
        private final Map<String, McpResource> resources;
        final List<String> read = new ArrayList<>();

        FakeServer(Map<String, Object> meta, Map<String, McpResource> resources) {
            this.meta = meta;
            this.resources = resources;
        }

        @Override
        public List<McpToolInfo> listTools() {
            return List.of(new McpToolInfo("tickets.chart", "Draw the open tickets.",
                    Map.of("type", "object", "properties", Map.of()), meta));
        }

        @Override
        public McpCallResult callTool(String name, Map<String, Object> arguments) {
            return new McpCallResult("47 open across three families.", false);
        }

        @Override
        public Optional<McpResource> readResource(String uri) {
            read.add(uri);
            return Optional.ofNullable(resources.get(uri));
        }

        @Override
        public void close() {
        }
    }

    private static Map<String, Object> declaring(String uri) {
        return Map.of("ui", Map.of("resourceUri", uri));
    }

    private static McpResource app(String uri) {
        return new McpResource(uri, McpResource.APP_HTML, PAGE);
    }

    private static ToolResult call(FakeServer server) {
        McpTool tool = new McpTool(server, server.listTools().getFirst());
        return tool.execute(new ToolInvocation("c1", "tickets.chart", Map.of("limit", 5)));
    }

    @Test
    void aToolThatDeclaredAnInterfaceCarriesItBackAsAView() {
        FakeServer server = new FakeServer(declaring("ui://tickets/chart"),
                Map.of("ui://tickets/chart", app("ui://tickets/chart")));

        ToolResult result = call(server);

        assertThat(server.read).containsExactly("ui://tickets/chart");
        assertThat(result.views()).singleElement().satisfies(view -> {
            assertThat(view.kind()).isEqualTo("mcp-app");
            assertThat(view.data().get("html")).isEqualTo(PAGE);
            assertThat(view.data().get("uri")).isEqualTo("ui://tickets/chart");
            // The extension's own data flow: a host delivers ui/notifications/tool-input and
            // ui/notifications/tool-result to the frame, and a renderer cannot invent either.
            assertThat(view.data().get("arguments")).isEqualTo(Map.of("limit", 5));
            assertThat(String.valueOf(view.data().get("result"))).contains("47 open");
        });
        // And the digest is untouched — the model reads what it always read.
        assertThat(result.content()).contains("47 open across three families.");
    }

    @Test
    void theDeprecatedFlatSpellingIsStillRead() {
        // `_meta["ui/resourceUri"]` is being phased out and servers in the wild still send it.
        // Refusing it would make this host stricter than the spec asks and show a person
        // nothing where something exists.
        FakeServer server = new FakeServer(Map.of("ui/resourceUri", "ui://tickets/chart"),
                Map.of("ui://tickets/chart", app("ui://tickets/chart")));

        assertThat(call(server).views()).hasSize(1);
    }

    @Test
    void aUriThatIsNotUiIsNeverFetched() {
        // The one that matters most. The uri is a string an untrusted server chose and this
        // host is about to fetch it; `resources/read` would happily ask for either of these.
        for (String hostile : List.of("file:///etc/passwd",
                "http://169.254.169.254/latest/meta-data/",
                "https://elsewhere.example/exfiltrate?q=1",
                "ui:/almost", "  ui://leading-space")) {
            FakeServer server = new FakeServer(declaring(hostile),
                    Map.of(hostile, app(hostile)));

            assertThat(call(server).views()).as("%s", hostile).isEmpty();
            assertThat(server.read).as("%s was fetched", hostile).isEmpty();
        }
    }

    @Test
    void plainHtmlIsNotAnApp() {
        // A server sending text/html has not opted into the extension. Rendering it anyway
        // would mean rendering arbitrary HTML any MCP server hands over.
        FakeServer server = new FakeServer(declaring("ui://tickets/chart"),
                Map.of("ui://tickets/chart",
                        new McpResource("ui://tickets/chart", "text/html", PAGE)));

        assertThat(call(server).views()).isEmpty();
        // Fetched, though — the refusal is on what came back, not on what was asked for.
        assertThat(server.read).containsExactly("ui://tickets/chart");
    }

    @Test
    void theProfileIsMatchedThroughWhitespaceAndCase() {
        for (String type : List.of("text/html;profile=mcp-app",
                "text/html; profile=mcp-app", "TEXT/HTML;PROFILE=MCP-APP",
                "text/html;charset=utf-8;profile=mcp-app")) {
            assertThat(new McpResource("ui://x", type, PAGE).isApp()).as("%s", type).isTrue();
        }
        for (String type : List.of("text/html", "application/json", "",
                "text/html;profile=something-else")) {
            assertThat(new McpResource("ui://x", type, PAGE).isApp()).as("%s", type).isFalse();
        }
    }

    @Test
    void aServerWithNoSuchResourceStillHasAWorkingTool() {
        // A server that does not implement resources answers with a JSON-RPC error, which
        // StdioMcpConnection turns into an empty Optional. The decoration is missing; the
        // tool is not.
        FakeServer server = new FakeServer(declaring("ui://tickets/chart"), Map.of());

        ToolResult result = call(server);

        assertThat(result.views()).isEmpty();
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("47 open");
    }

    @Test
    void aToolThatDeclaredNothingIsNotAskedAboutResources() {
        FakeServer server = new FakeServer(Map.of(), Map.of());

        assertThat(call(server).views()).isEmpty();
        // Not merely empty — never asked. A host that did a resources/read per tool call
        // against every server would be a host that doubled its own round trips for nothing.
        assertThat(server.read).isEmpty();
    }

    @Test
    void aConnectionWrittenBeforeTheExtensionExistedIsNotBroken() {
        // The default on McpConnection.readResource. A transport that predates MCP Apps has
        // no apps rather than a compile error, which is the same rule the three-arg
        // McpToolInfo constructor keeps.
        McpConnection old = new McpConnection() {
            @Override
            public List<McpToolInfo> listTools() {
                return List.of(new McpToolInfo("t", "d", Map.of()));
            }

            @Override
            public McpCallResult callTool(String name, Map<String, Object> arguments) {
                return new McpCallResult("fine", false);
            }

            @Override
            public void close() {
            }
        };

        assertThat(old.readResource("ui://anything")).isEmpty();
        assertThat(old.listTools().getFirst().meta()).isEmpty();
        assertThat(old.listTools().getFirst().uiResourceUri()).isEmpty();
    }

    @Test
    void aFailedCallKeepsItsInterfaceAndKeepsSayingItFailed() {
        FakeServer failing = new FakeServer(declaring("ui://tickets/chart"),
                Map.of("ui://tickets/chart", app("ui://tickets/chart"))) {
            @Override
            public McpCallResult callTool(String name, Map<String, Object> arguments) {
                return new McpCallResult("the upstream refused", true);
            }
        };

        ToolResult result = call(failing);

        // A tool that failed may still have an interface that says why — and the digest keeps
        // saying it failed, so nobody is shown a working-looking panel over a call that did
        // not work.
        assertThat(result.views()).hasSize(1);
        assertThat(result.isError()).isTrue();
        assertThat(result.views().getFirst().data().get("isError")).isEqualTo(true);
    }

    @Test
    void aPageLargerThanThisHostWillHoldIsCutRatherThanRefused() {
        String huge = "x".repeat(McpResource.MAX_CHARS * 2);
        McpResource resource = new McpResource("ui://x", McpResource.APP_HTML, huge);

        // Bounded because it is a server's bytes over a pipe. Cut rather than refused because
        // a UI resource is legitimately large — the spec requires it to be self-contained —
        // and a page that arrives truncated is a page a person can see is truncated.
        assertThat(resource.text().length()).isLessThan(huge.length());
        assertThat(resource.text()).startsWith("x");
    }

    @Test
    void theViewIsCheckedBeforeItLeavesTheModule() {
        // View refuses anything JSON cannot spell, and it refuses by throwing. A server that
        // sent an argument map with a non-JSON value would otherwise take the tool call down.
        FakeServer server = new FakeServer(declaring("ui://tickets/chart"),
                Map.of("ui://tickets/chart", app("ui://tickets/chart")));
        McpTool tool = new McpTool(server, server.listTools().getFirst());

        ToolResult result = tool.execute(new ToolInvocation("c1", "tickets.chart",
                Map.of("nested", Map.of("deep", List.of(1, 2, 3)))));

        assertThat(result.views()).singleElement()
                .satisfies(view -> assertThat(view.data().get("arguments"))
                        .isEqualTo(Map.of("nested", Map.of("deep", List.of(1, 2, 3)))));
        assertThat(View.of("mcp-app", Map.of("html", PAGE)).kind()).isEqualTo("mcp-app");
    }
}
