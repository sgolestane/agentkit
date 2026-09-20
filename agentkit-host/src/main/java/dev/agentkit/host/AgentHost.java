package dev.agentkit.host;

import dev.agentkit.host.repo.AgentDefinition;
import dev.agentkit.host.repo.DefinitionException;
import dev.agentkit.host.repo.GitVersion;
import dev.agentkit.host.repo.OrgRepo;
import dev.agentkit.host.repo.RepoLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One organization's agents, live: its repository loaded at a version, its connectors connected, and every agent
 * assembled against them.
 *
 * <p>Opening either succeeds whole or fails with every problem the repository has — the same list a pull request's
 * check shows — so a version that would not run is never half-served. A connector that is down is not such a
 * problem: the agents that need it are open but {@link HostedAgent#unavailable() unavailable}.
 */
public final class AgentHost implements AutoCloseable {

    private final OrgRepo repo;
    private final OrgConnectors connectors;
    private final Directory directory;
    private final Map<String, HostedAgent> agents;

    private AgentHost(OrgRepo repo, OrgConnectors connectors, Map<String, HostedAgent> agents) {
        this.repo = repo;
        this.connectors = connectors;
        this.directory = new Directory(repo.org(), repo.directory(), connectors);
        this.agents = Map.copyOf(agents);
    }

    /** How to open an organization. */
    public record Options(Secrets secrets, Map<String, String> placeholders, boolean allowLocalConnectors,
                          Optional<dev.agentkit.host.auth.CallerSigner> signer) {
        public Options {
            Objects.requireNonNull(secrets, "secrets");
            placeholders = Map.copyOf(placeholders);
            signer = signer == null ? Optional.empty() : signer;
        }

        public Options(Secrets secrets, Map<String, String> placeholders, boolean allowLocalConnectors) {
            this(secrets, placeholders, allowLocalConnectors, Optional.empty());
        }

        /** These options, with every connector call carrying a caller assertion {@code signer} signs. */
        public Options signedBy(dev.agentkit.host.auth.CallerSigner signer) {
            return new Options(secrets, placeholders, allowLocalConnectors, Optional.of(signer));
        }

        /** Remote connectors only, with these secrets. */
        public static Options hosted(Secrets secrets) {
            return new Options(secrets, Map.of(), false);
        }
    }

    /**
     * Opens the repository checked out in {@code dir}, at the version Git says it holds.
     *
     * @throws DefinitionException listing every problem with it
     */
    public static AgentHost open(Path dir, Options options) {
        return open(dir, GitVersion.of(dir), options);
    }

    /** Opens the repository in {@code dir} as {@code version}. */
    public static AgentHost open(Path dir, String version, Options options) {
        OrgRepo repo = RepoLoader.load(dir, version);
        OrgConnectors connectors = OrgConnectors.connect(repo, options.secrets(), options.placeholders(),
                options.allowLocalConnectors(), options.signer());
        try {
            List<DefinitionException.Problem> problems = new ArrayList<>();
            Map<String, HostedAgent> agents = new LinkedHashMap<>();
            for (AgentDefinition definition : repo.agents().values()) {
                try {
                    agents.put(definition.id(), HostedAgent.assemble(repo, definition, connectors));
                } catch (DefinitionException e) {
                    problems.addAll(e.problems());
                }
            }
            // How people are told what waits for them: a tool that notifies, taking the arguments named. Checked when
            // its connector was reached; one that was not makes no notification, and says so in the log when used.
            repo.notifications().ifPresent(notify -> connectors.catalog(notify.tool().connector()).ifPresent(catalog -> {
                Optional<dev.agentkit.core.tool.DeclaredTools.Entry> entry = catalog.entry(notify.tool().tool());
                if (entry.isEmpty()) {
                    problems.add(new DefinitionException.Problem("org.yaml", "notify.tool",
                            notify.tool().connector() + " has no tool " + notify.tool().tool() + " that declares what it does"));
                } else if (entry.get().declaration().effect() != dev.agentkit.core.tool.ToolEffect.NOTIFY) {
                    problems.add(new DefinitionException.Problem("org.yaml", "notify.tool", notify.tool() + " is declared "
                            + entry.get().declaration().effect().wire() + "; people are told through a tool that notifies"));
                } else {
                    for (String argument : List.of(notify.to(), notify.text())) {
                        if (!BoundTool.hasArgument(entry.get().tool().inputSchema(), argument)) {
                            problems.add(new DefinitionException.Problem("org.yaml", "notify",
                                    notify.tool() + " has no argument " + argument));
                        }
                    }
                }
            }));
            if (!problems.isEmpty()) {
                throw new DefinitionException(problems);
            }
            return new AgentHost(repo, connectors, agents);
        } catch (RuntimeException e) {
            connectors.close();
            throw e;
        }
    }

    public OrgRepo repo() {
        return repo;
    }

    public Optional<HostedAgent> agent(String id) {
        return Optional.ofNullable(agents.get(id));
    }

    /** Every agent, by id. */
    public Map<String, HostedAgent> agents() {
        return agents;
    }

    /** The agents {@code principal} may use. */
    public List<HostedAgent> agentsFor(Principal principal) {
        return agents.values().stream().filter(a -> a.admits(principal)).toList();
    }

    /** Whether {@code principal} may see the organization's admin view: they are in a group {@code org.yaml} names. */
    public boolean isAdmin(Principal principal) {
        return principal.org().equals(repo.org()) && repo.admins().stream().anyMatch(principal.groups()::contains);
    }

    /** Who someone the host has authenticated as {@code email} is in this organization. */
    public Optional<Principal> principal(String email) {
        return directory.principal(email);
    }

    public OrgConnectors connectors() {
        return connectors;
    }

    @Override
    public void close() {
        connectors.close();
    }
}
