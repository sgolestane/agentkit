package dev.agentkit.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentkit.core.tool.DeclaredTools;
import dev.agentkit.host.repo.DefinitionException;
import dev.agentkit.host.repo.DefinitionException.Problem;
import dev.agentkit.host.repo.OrgRepo;
import dev.agentkit.host.repo.OrgRepo.ConnectorSpec;
import dev.agentkit.mcp.McpConnection;
import dev.agentkit.mcp.McpConnectors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An organization's connectors, connected: each one's tools with what they declare, and its connection.
 *
 * <p>A connector that cannot be reached does not take the organization down. It is recorded with why, and the
 * agents that use it say so when asked, while every other agent keeps working. A file that is wrong — a secret that
 * does not exist, a placeholder nobody fills, a local command where only remote connectors are allowed — is a
 * {@link DefinitionException}, because no retry will fix it.
 */
public final class OrgConnectors implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OrgConnectors.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");

    private final Map<String, ConnectorSpec> specs;
    private final Map<String, McpConnectors.Connected> connected;
    private final Map<String, String> failed;
    private final Optional<dev.agentkit.host.auth.CallerSigner> signer;

    private OrgConnectors(Map<String, ConnectorSpec> specs, Map<String, McpConnectors.Connected> connected,
                          Map<String, String> failed, Optional<dev.agentkit.host.auth.CallerSigner> signer) {
        this.specs = Map.copyOf(specs);
        this.connected = Map.copyOf(connected);
        this.failed = Map.copyOf(failed);
        this.signer = signer;
    }

    /**
     * What a call to {@code connector} for {@code caller} carries in its {@code _meta}: the caller assertion, signed
     * now, for that connector; empty when the host signs none.
     */
    public Map<String, Object> meta(String connector, dev.agentkit.host.auth.CallerSigner.Caller caller) {
        return signer.<Map<String, Object>>map(s -> Map.of(dev.agentkit.mcp.server.CallerAssertion.META_KEY,
                s.sign(caller, connector))).orElse(Map.of());
    }

    /** {@code tool}, one of {@code connector}'s, its every call carrying an assertion for {@code caller}. */
    public dev.agentkit.core.tool.Tool asserted(String connector, dev.agentkit.core.tool.Tool tool,
                                              dev.agentkit.host.auth.CallerSigner.Caller caller) {
        if (signer.isEmpty()) {
            return tool;
        }
        return new dev.agentkit.core.tool.ForwardingTool() {
            @Override
            protected dev.agentkit.core.tool.Tool delegate() {
                return tool;
            }

            @Override
            public dev.agentkit.core.tool.ToolResult execute(dev.agentkit.core.tool.ToolInvocation invocation) {
                return dev.agentkit.mcp.CallMeta.sending(meta(connector, caller), () -> tool.execute(invocation));
            }
        };
    }

    /**
     * Connects to every connector of {@code repo}.
     *
     * @param placeholders values the application supplies for {@code ${name}}, besides secrets
     * @param allowLocal   whether a connector may be a local {@code command}; a hosted deployment runs none
     */
    public static OrgConnectors connect(OrgRepo repo, Secrets secrets, Map<String, String> placeholders,
                                        boolean allowLocal) {
        return connect(repo, secrets, placeholders, allowLocal, Optional.empty());
    }

    /**
     * {@link #connect(OrgRepo, Secrets, Map, boolean)}, with every call to a connector carrying a caller assertion that
     * {@code signer} signs.
     */
    public static OrgConnectors connect(OrgRepo repo, Secrets secrets, Map<String, String> placeholders,
                                        boolean allowLocal, Optional<dev.agentkit.host.auth.CallerSigner> signer) {
        List<Problem> problems = new ArrayList<>();
        Map<String, Map<String, String>> resolved = new LinkedHashMap<>();
        for (ConnectorSpec spec : repo.connectors().values()) {
            String file = "connectors/" + spec.name() + ".yaml";
            if (spec.isLocal() && !allowLocal) {
                problems.add(new Problem(file, "command", "this host only reaches connectors by url"));
                continue;
            }
            Map<String, String> values = new HashMap<>();
            for (String name : placeholders(spec.server())) {
                if (name.startsWith("secret:")) {
                    String secret = name.substring("secret:".length());
                    secrets.get(secret).ifPresentOrElse(v -> values.put(name, v),
                            () -> problems.add(new Problem(file, "", "the secret " + secret + " is not set")));
                } else if (placeholders.containsKey(name)) {
                    values.put(name, placeholders.get(name));
                } else {
                    problems.add(new Problem(file, "", "nothing fills ${" + name + "}"));
                }
            }
            resolved.put(spec.name(), values);
        }
        if (!problems.isEmpty()) {
            throw new DefinitionException(problems);
        }
        Map<String, McpConnectors.Connected> connected = new LinkedHashMap<>();
        Map<String, String> failed = new LinkedHashMap<>();
        for (ConnectorSpec spec : repo.connectors().values()) {
            ObjectNode file = JSON.createObjectNode();
            file.putArray("servers").add(spec.server());
            try {
                connected.put(spec.name(), McpConnectors.connect(file.toString(), resolved.get(spec.name())));
            } catch (RuntimeException e) {
                LOG.warn("Connector {} of {} could not be connected: {}", spec.name(), repo.org(), e.getMessage());
                failed.put(spec.name(), "The " + spec.name() + " connector could not be reached.");
            }
        }
        return new OrgConnectors(repo.connectors(), connected, failed, signer);
    }

    /** The connector's tools, each with its declaration; empty if it is not connected. */
    public Optional<DeclaredTools> catalog(String connector) {
        return Optional.ofNullable(connected.get(connector)).map(McpConnectors.Connected::catalog);
    }

    /** The connection to the connector, for the host's own calls; empty if it is not connected. */
    public Optional<McpConnection> client(String connector) {
        return Optional.ofNullable(connected.get(connector)).map(c -> c.client(connector));
    }

    /** Why the connector is not connected, if it is not. */
    public Optional<String> failure(String connector) {
        return Optional.ofNullable(failed.get(connector));
    }

    /** Every connector not connected, with why. */
    public Map<String, String> failures() {
        return failed;
    }

    public boolean isAuthoritative(String connector) {
        ConnectorSpec spec = specs.get(connector);
        return spec != null && spec.authoritative();
    }

    @Override
    public void close() {
        connected.values().forEach(McpConnectors.Connected::close);
    }

    private static List<String> placeholders(JsonNode node) {
        List<String> names = new ArrayList<>();
        collect(node, names);
        return names;
    }

    private static void collect(JsonNode node, List<String> names) {
        if (node.isTextual()) {
            Matcher m = PLACEHOLDER.matcher(node.asText());
            while (m.find()) {
                if (!names.contains(m.group(1))) {
                    names.add(m.group(1));
                }
            }
        } else if (node.isContainerNode()) {
            node.forEach(child -> collect(child, names));
        }
    }
}
