package dev.agentkit.host.repo;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentkit.host.models.Budget;
import java.util.List;
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
 * @param admins       the directory groups whose members see the organization's admin view; empty for nobody
 * @param repository   where a change proposed in the admin view goes as a pull request, if the organization says
 * @param signIn       the organization's identity provider, which its people sign in with, if the organization says
 * @param provider     the model provider its agents run on with its own key, or empty for the host's account
 * @param budget       the most its agents may spend on models, whoever pays; {@link Budget#NONE} for no cap of its own
 */
public record OrgRepo(String org, String version, String defaultModel, Optional<DirectorySpec> directory,
                      Map<String, ConnectorSpec> connectors, Map<String, AgentDefinition> agents, List<String> admins,
                      Optional<RepositorySpec> repository, Optional<SignInSpec> signIn, Optional<String> provider,
                      Budget budget) {

    /** A repository that names no model provider or budget of its own. */
    public OrgRepo(String org, String version, String defaultModel, Optional<DirectorySpec> directory,
                   Map<String, ConnectorSpec> connectors, Map<String, AgentDefinition> agents, List<String> admins,
                   Optional<RepositorySpec> repository, Optional<SignInSpec> signIn) {
        this(org, version, defaultModel, directory, connectors, agents, admins, repository, signIn, Optional.empty(),
                Budget.NONE);
    }

    public OrgRepo {
        Objects.requireNonNull(org, "org");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(defaultModel, "defaultModel");
        Objects.requireNonNull(directory, "directory");
        connectors = Map.copyOf(connectors);
        admins = admins == null ? List.of() : List.copyOf(admins);
        agents = Map.copyOf(agents);
        repository = repository == null ? Optional.empty() : repository;
        signIn = signIn == null ? Optional.empty() : signIn;
        provider = provider == null ? Optional.empty() : provider;
        budget = budget == null ? Budget.NONE : budget;
    }

    /**
     * The organization's identity provider: an OpenID Connect issuer its people sign in to the console with, and whose
     * access tokens its MCP callers present.
     *
     * @param issuer      the issuer, as its discovery document and tokens name it
     * @param clientId    the host's client id there; the client secret, if any, is the org's OIDC_CLIENT_SECRET secret
     * @param emailClaim  the claim holding the person's email, which the directory looks them up by
     * @param mcpAudience the audience an MCP access token must carry; empty for the org's MCP address
     */
    public record SignInSpec(String issuer, String clientId, String emailClaim, String mcpAudience) {
        public SignInSpec {
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(clientId, "clientId");
            issuer = issuer.replaceAll("/+$", "");
            emailClaim = emailClaim == null || emailClaim.isBlank() ? "email" : emailClaim;
            mcpAudience = mcpAudience == null ? "" : mcpAudience;
        }
    }

    /**
     * The organization's repository on GitHub, where a change proposed in the admin view is opened as a pull request.
     *
     * @param github the repository, {@code owner/name}
     * @param path   where the organization's files are in it: empty for its root
     * @param base   the branch pull requests are opened against
     * @param api    GitHub's API, or a GitHub Enterprise server's
     */
    public record RepositorySpec(String github, String path, String base, String api) {
        public RepositorySpec {
            Objects.requireNonNull(github, "github");
            path = path == null ? "" : path.replaceAll("^/+|/+$", "");
            base = base == null || base.isBlank() ? "main" : base;
            api = api == null || api.isBlank() ? "https://api.github.com" : api.replaceAll("/+$", "");
        }
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
