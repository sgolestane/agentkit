package dev.agentkit.host.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.sun.net.httpserver.HttpHandler;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.host.OrgHost;
import dev.agentkit.host.Tenant;
import dev.agentkit.mcp.server.HttpMcpEndpoint;
import dev.agentkit.mcp.server.McpServer;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Each organization's agents as an MCP server that signs its callers in with the organization's identity provider,
 * the way MCP's authorization spec lays out.
 *
 * <pre>
 * POST /orgs/{org}/mcp                                      the org's agents; an access token from its identity provider
 * GET  /.well-known/oauth-protected-resource/orgs/{org}/mcp  which identity provider that is (RFC 9728)
 * </pre>
 *
 * <p>A request without a token that checks out is refused with {@code 401} and a {@code WWW-Authenticate} pointing at
 * the metadata, from which a client learns where to sign its person in. A token is taken only if it is signed by the
 * organization's issuer's published keys, is for this server — its audience is the org's MCP address, or the
 * {@code mcpAudience} its {@code org.yaml} names — and names someone in the org's directory. One organization per
 * address, so a token from one organization's provider is never read as another's.
 */
public final class OrgMcp {

    private static final Logger LOG = LoggerFactory.getLogger(OrgMcp.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String METADATA = "/.well-known/oauth-protected-resource";

    private final Map<String, OrgHost> orgs;
    private final Function<OrgHost, Optional<Oidc>> providers;
    private final URI publicUrl;
    private final Function<String, Optional<DeclaredTools>> toolsFor;
    private final Supplier<McpServer> server;
    private final Map<String, HttpMcpEndpoint> endpoints = new ConcurrentHashMap<>();

    /**
     * @param toolsFor what a tenant is served, as {@code HostMcp::toolsFor}
     */
    public OrgMcp(Map<String, OrgHost> orgs, Function<OrgHost, Optional<Oidc>> providers, URI publicUrl,
                  Function<String, Optional<DeclaredTools>> toolsFor, Supplier<McpServer> server) {
        this.orgs = Map.copyOf(orgs);
        this.providers = Objects.requireNonNull(providers, "providers");
        this.publicUrl = URI.create(Objects.requireNonNull(publicUrl, "publicUrl").toString().replaceAll("/+$", ""));
        this.toolsFor = Objects.requireNonNull(toolsFor, "toolsFor");
        this.server = Objects.requireNonNull(server, "server");
    }

    /** The org's MCP address: the resource its access tokens are for. */
    public String resource(String org) {
        return publicUrl + "/orgs/" + org + "/mcp";
    }

    /** For {@code /orgs/}: each organization's MCP endpoint. */
    public HttpHandler endpoints() {
        return exchange -> {
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");
            OrgHost org = parts.length == 4 && parts[3].equals("mcp") ? orgs.get(parts[2]) : null;
            if (org == null || providers.apply(org).isEmpty()) {
                try (exchange) {
                    exchange.sendResponseHeaders(404, -1);
                }
                return;
            }
            endpoints.computeIfAbsent(org.org(), name -> new HttpMcpEndpoint(server.get(), callers(org), toolsFor)
                    .challenge("Bearer resource_metadata=\"" + publicUrl + METADATA + "/orgs/" + name + "/mcp\""))
                    .handle(exchange);
        };
    }

    /** For {@code /.well-known/oauth-protected-resource/}: which identity provider each org's MCP address trusts. */
    public HttpHandler metadata() {
        return exchange -> {
            try (exchange) {
                String rest = exchange.getRequestURI().getPath().substring(METADATA.length());
                String[] parts = rest.split("/");
                OrgHost org = parts.length == 4 && parts[1].equals("orgs") && parts[3].equals("mcp") ? orgs.get(parts[2]) : null;
                Optional<Oidc> provider = Optional.ofNullable(org).flatMap(providers);
                if (provider.isEmpty()) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                Map<String, Object> document = new LinkedHashMap<>();
                document.put("resource", resource(org.org()));
                document.put("authorization_servers", List.of(provider.get().spec().issuer()));
                document.put("bearer_methods_supported", List.of("header"));
                document.put("scopes_supported", List.of("openid", "email"));
                document.put("resource_name", org.org() + "'s agents");
                byte[] body = JSON.writeValueAsBytes(document);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        };
    }

    /** Who a request to this org's address is from: the email in an access token that checks out, or nobody. */
    HttpMcpEndpoint.Callers callers(OrgHost org) {
        return headers -> {
            String authorization = Objects.toString(headers.getFirst("Authorization"), "");
            if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return Optional.empty();
            }
            Optional<Oidc> provider = providers.apply(org);
            if (provider.isEmpty()) {
                return Optional.empty();
            }
            String audience = provider.get().spec().mcpAudience().isEmpty() ? resource(org.org())
                    : provider.get().spec().mcpAudience();
            try {
                JWTClaimsSet claims = provider.get().accessToken(authorization.substring(7).strip(), audience);
                return provider.get().email(claims).map(email -> new Tenant(org.org(), email).id());
            } catch (Oidc.RejectedToken e) {
                LOG.debug("An MCP token for {} was refused: {}", org.org(), e.getMessage());
                return Optional.empty();
            }
        };
    }
}
