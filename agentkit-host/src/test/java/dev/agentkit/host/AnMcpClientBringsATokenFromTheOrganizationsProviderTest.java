package dev.agentkit.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.core.tool.FunctionTool;
import dev.agentkit.core.tool.ToolDeclaration;
import dev.agentkit.core.tool.ToolEffect;
import dev.agentkit.core.tool.ToolResult;
import dev.agentkit.host.auth.FakeIdentityProvider;
import dev.agentkit.host.auth.FakeIdentityProvider.Misbehaviour;
import dev.agentkit.host.auth.Oidc;
import dev.agentkit.host.auth.OrgMcp;
import dev.agentkit.mcp.HttpMcpConnection;
import dev.agentkit.mcp.server.McpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An MCP client signs its person in with the organization's identity provider and brings the access token to the
 * org's own MCP address. Without one that checks out it is refused with a challenge naming where to get one — the
 * org's protected-resource metadata, which names its provider — and a token for anything but this address, expired, or
 * signed with a key the provider does not publish is refused the same way.
 */
class AnMcpClientBringsATokenFromTheOrganizationsProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private HelpdeskConnector helpdesk;
    private FakeIdentityProvider idp;
    private OrgHost org;
    private HttpServer host;
    private OrgMcp mcp;

    @BeforeEach
    void start() throws Exception {
        helpdesk = new HelpdeskConnector();
        idp = new FakeIdentityProvider(0, "agentkit-host", null);
        RepoFixture repo = RepoFixture.copyInto(dir);
        repo.write("org.yaml", repo.read("org.yaml") + "\nsignIn:\n  issuer: " + idp.issuer() + "\n  clientId: agentkit-host\n");
        org = OrgHost.open(repo.root(), AgentHost.Options.hosted(Secrets.of(Map.of(
                "HELPDESK_URL", helpdesk.url(), "HELPDESK_TOKEN", HelpdeskConnector.TOKEN))));
        host = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        host.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        Oidc provider = new Oidc(org.current().repo().signIn().orElseThrow(), Optional.empty());
        // Who the caller is, as the host's MCP tools would see it.
        mcp = new OrgMcp(Map.of("acme", org), o -> Optional.of(provider),
                URI.create("http://127.0.0.1:" + host.getAddress().getPort()),
                tenant -> Optional.of(new DeclaredTools().add(FunctionTool.builder("whoami", "Who is asking")
                        .schema(Map.of("type", "object", "properties", Map.of()))
                        .handler(inv -> ToolResult.ok(tenant)).build(), new ToolDeclaration("host", ToolEffect.READ, null))),
                () -> new McpServer("agentkit-host", "1", ""));
        host.createContext("/orgs/", mcp.endpoints());
        host.createContext("/.well-known/oauth-protected-resource/", mcp.metadata());
        host.start();
    }

    @AfterEach
    void stop() {
        host.stop(0);
        org.close();
        idp.close();
        helpdesk.close();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + host.getAddress().getPort() + path;
    }

    private HttpResponse<String> initialize(String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url("/orgs/acme/mcp")))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}"));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void withoutATokenItSaysWhereToGetOne() throws Exception {
        HttpResponse<String> refused = initialize(null);

        assertThat(refused.statusCode()).isEqualTo(401);
        String challenge = refused.headers().firstValue("WWW-Authenticate").orElseThrow();
        assertThat(challenge).isEqualTo("Bearer resource_metadata=\"" + url("/.well-known/oauth-protected-resource/orgs/acme/mcp") + "\"");

        JsonNode metadata = JSON.readTree(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                url("/.well-known/oauth-protected-resource/orgs/acme/mcp"))).build(), HttpResponse.BodyHandlers.ofString()).body());
        assertThat(metadata.path("resource").asText()).isEqualTo(mcp.resource("acme"));
        assertThat(metadata.path("authorization_servers").toString()).isEqualTo("[\"" + idp.issuer() + "\"]");
        assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url("/orgs/globex/mcp")))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(404);
    }

    @Test
    void aTokenForThisAddressMakesTheCallerThePersonItNames() throws Exception {
        String token = idp.accessToken(HelpdeskConnector.PRIYA, mcp.resource("acme"), Duration.ofMinutes(5));

        try (HttpMcpConnection client = HttpMcpConnection.builder(URI.create(url("/orgs/acme/mcp")))
                .header("Authorization", "Bearer " + token).connect()) {
            assertThat(client.callTool("whoami", Map.of()).text()).isEqualTo("acme/" + HelpdeskConnector.PRIYA);
        }
    }

    @Test
    void aTokenThatDoesNotCheckOutIsRefused() throws Exception {
        assertThat(initialize(idp.accessToken(HelpdeskConnector.PRIYA, "https://elsewhere.example/mcp",
                Duration.ofMinutes(5))).statusCode()).as("another audience").isEqualTo(401);
        assertThat(initialize(idp.accessToken(HelpdeskConnector.PRIYA, mcp.resource("acme"),
                Duration.ofMinutes(-5))).statusCode()).as("expired").isEqualTo(401);
        idp.misbehave(Misbehaviour.UNPUBLISHED_KEY);
        assertThat(initialize(idp.accessToken(HelpdeskConnector.PRIYA, mcp.resource("acme"),
                Duration.ofMinutes(5))).statusCode()).as("unpublished key").isEqualTo(401);
        assertThat(initialize("not.a.token").statusCode()).as("not a token").isEqualTo(401);
    }
}
