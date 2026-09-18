package dev.agentkit.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.host.repo.OrgRepo;
import dev.agentkit.mcp.McpCallResult;
import dev.agentkit.mcp.McpConnection;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who someone is in an organization, as its directory says: the tool {@code org.yaml} names, called with their
 * email, read as one JSON object. Its scalar fields become the principal's facts; a {@code groups} list becomes their
 * groups.
 *
 * <p>The host reads the record itself, over the connection, rather than through a model: a principal is the input
 * to bindings and audiences, so it must be what the directory said, not what a model repeated.
 */
public final class Directory {

    private static final Logger LOG = LoggerFactory.getLogger(Directory.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String org;
    private final Optional<OrgRepo.DirectorySpec> spec;
    private final OrgConnectors connectors;

    public Directory(String org, Optional<OrgRepo.DirectorySpec> spec, OrgConnectors connectors) {
        this.org = Objects.requireNonNull(org, "org");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.connectors = Objects.requireNonNull(connectors, "connectors");
    }

    /**
     * The principal for someone the host has authenticated as {@code email}. With no directory configured they are
     * known by their email alone; with one, someone it does not know is nobody.
     */
    public Optional<Principal> principal(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (spec.isEmpty()) {
            return Optional.of(new Principal(org, normalized, normalized, Set.of(), Map.of()));
        }
        OrgRepo.DirectorySpec directory = spec.get();
        Optional<McpConnection> client = connectors.client(directory.connector());
        if (client.isEmpty()) {
            LOG.warn("The directory connector {} of {} is not connected", directory.connector(), org);
            return Optional.empty();
        }
        McpCallResult result = client.get().callTool(directory.tool(), Map.of(directory.argument(), normalized));
        if (result.isError()) {
            return Optional.empty();
        }
        JsonNode record;
        try {
            record = JSON.readTree(result.text());
        } catch (IOException e) {
            LOG.warn("The directory of {} answered with something that is not JSON", org);
            return Optional.empty();
        }
        if (record == null || !record.isObject()) {
            return Optional.empty();
        }
        Map<String, String> facts = new LinkedHashMap<>();
        Set<String> groups = new LinkedHashSet<>();
        record.fields().forEachRemaining(field -> {
            JsonNode value = field.getValue();
            if (field.getKey().equals("groups") && value.isArray()) {
                value.forEach(g -> {
                    if (g.isTextual() && !g.asText().isBlank()) {
                        groups.add(g.asText().strip());
                    }
                });
            } else if (value.isValueNode() && !value.isNull()) {
                facts.put(field.getKey(), value.asText());
            }
        });
        return Optional.of(new Principal(org, normalized, normalized, groups, facts));
    }
}
