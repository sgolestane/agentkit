package dev.agentkit.host.repo;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An organization's repository of agents, at one version: {@code org.yaml}, {@code connectors/*.yaml} and
 * {@code agents/<id>/}. {@link RepoLoader} reads one; nothing here has been connected to anything yet.
 *
 * @param org          the organization's id
 * @param version      what was loaded: a commit, or a commit marked as having uncommitted changes
 * @param defaultModel the model an agent that names none runs on
 * @param directory    where a person's record is looked up, if the organization says
 * @param connectors   each connector by name
 * @param agents       each agent by id
 */
public record OrgRepo(String org, String version, String defaultModel, Optional<DirectorySpec> directory,
                      Map<String, ConnectorSpec> connectors, Map<String, AgentDefinition> agents) {

    public OrgRepo {
        Objects.requireNonNull(org, "org");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(defaultModel, "defaultModel");
        Objects.requireNonNull(directory, "directory");
        connectors = Map.copyOf(connectors);
        agents = Map.copyOf(agents);
    }

    /**
     * Where a person's record comes from: a tool on one of the connectors, called with their email.
     *
     * @param argument the argument the email goes in
     */
    public record DirectorySpec(String connector, String tool, String argument) {
        public DirectorySpec {
            Objects.requireNonNull(connector, "connector");
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(argument, "argument");
        }
    }

    /**
     * One connector, as {@code connectors/<name>.yaml} describes it.
     *
     * @param name          the file's name, without {@code .yaml}
     * @param server        the entry {@code McpConnectors} reads: {@code url} or {@code command}, {@code headers},
     *                      {@code trustAnnotations}, {@code tools}, {@code timeoutSeconds}; placeholders unresolved
     * @param authoritative whether this connector enforces its own rules for what it grants, so a grant through it
     *                      need not also be confirmed by the person
     */
    public record ConnectorSpec(String name, ObjectNode server, boolean authoritative) {
        public ConnectorSpec {
            Objects.requireNonNull(name, "name");
            server = server.deepCopy();
        }

        public boolean isLocal() {
            return server.has("command");
        }
    }
}
