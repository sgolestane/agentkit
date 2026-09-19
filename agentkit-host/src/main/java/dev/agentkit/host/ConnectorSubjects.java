package dev.agentkit.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.deferred.SubjectRecord;
import dev.agentkit.core.deferred.SubjectResolver;
import dev.agentkit.host.repo.AgentDefinition;
import dev.agentkit.mcp.McpCallResult;
import dev.agentkit.mcp.McpConnection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subjects of deferred work, looked up in their system of record through a connector: the tool an agent definition
 * names for each kind, called with the subject's id, answering with one JSON object.
 *
 * <pre>{@code
 * {
 *   "id": "GR-1001",
 *   "identifiers": ["GR-1001", "priya@acme.example"],  // what a deferred action may act on
 *   "contacts": ["dana@acme.example"],                  // whom it may notify besides
 *   "facts": {"status": "ACTIVE", "expires_at": "2026-09-16T17:00:00Z", ...},
 *   "holdings": ["GR-1001"]                             // optional: what a removal must cover
 * }
 * }</pre>
 *
 * <p>The host calls the connector itself rather than through a model, because the record is what bounds the action:
 * which values its tools may name, whom it may tell. An error, or anything that is not such an object, is a subject
 * that does not exist.
 */
final class ConnectorSubjects implements SubjectResolver {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectorSubjects.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String org;
    private final Map<String, AgentDefinition.Subject> subjects;
    private final OrgConnectors connectors;

    ConnectorSubjects(String org, Map<String, AgentDefinition.Subject> subjects, OrgConnectors connectors) {
        this.org = org;
        this.subjects = Map.copyOf(subjects);
        this.connectors = connectors;
    }

    @Override
    public Set<String> kinds() {
        return subjects.keySet();
    }

    @Override
    public Optional<SubjectRecord> resolve(String kind, String id) {
        return read(kind, id).map(record -> record.subject);
    }

    /** What the subject holds, as its record says, for the scheduler's feedback. */
    List<String> holdings(SubjectRecord subject) {
        return read(subject.kind(), subject.id()).map(record -> record.holdings).orElse(List.of());
    }

    private record Read(SubjectRecord subject, List<String> holdings) {
    }

    private Optional<Read> read(String kind, String id) {
        AgentDefinition.Subject spec = subjects.get(kind);
        if (spec == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        Optional<McpConnection> client = connectors.client(spec.tool().connector());
        if (client.isEmpty()) {
            return Optional.empty();
        }
        McpCallResult result = client.get().callTool(spec.tool().tool(), Map.of(spec.argument(), id.strip()),
                connectors.meta(spec.tool().connector(), dev.agentkit.host.auth.CallerSigner.Caller.host(org)));
        if (result.isError()) {
            return Optional.empty();
        }
        try {
            JsonNode record = JSON.readTree(result.text());
            if (record == null || !record.isObject()) {
                return Optional.empty();
            }
            Map<String, String> facts = new LinkedHashMap<>();
            record.path("facts").fields().forEachRemaining(f -> {
                if (f.getValue().isValueNode() && !f.getValue().isNull()) {
                    facts.put(f.getKey(), f.getValue().asText());
                }
            });
            String recordId = record.path("id").asText(id.strip());
            Set<String> identifiers = strings(record.path("identifiers"));
            identifiers.add(recordId);
            return Optional.of(new Read(new SubjectRecord(kind, recordId, identifiers, strings(record.path("contacts")),
                    facts), new ArrayList<>(strings(record.path("holdings")))));
        } catch (IOException e) {
            LOG.warn("{} answered for {} {} with something that is not JSON", spec.tool(), kind, id);
            return Optional.empty();
        }
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        array.forEach(v -> {
            if (v.isTextual() && !v.asText().isBlank()) {
                values.add(v.asText().strip());
            }
        });
        return values;
    }
}
