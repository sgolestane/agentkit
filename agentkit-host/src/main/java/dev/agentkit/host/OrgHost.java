package dev.agentkit.host;

import dev.agentkit.host.repo.DefinitionException;
import dev.agentkit.host.repo.GitVersion;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One organization's agents across versions of its repository: the version new conversations start on, and the
 * recent ones conversations already running are pinned to.
 *
 * <p>Merging a change to the repository and reloading makes a new version current. The versions before it stay
 * loaded, up to {@link #RETAINED}, so a conversation started on one keeps talking to the agent it started with — the
 * same prompt, policy and tools — rather than one that changed underneath it. A conversation pinned to a version
 * that has since been let go is told so, and not quietly moved.
 *
 * <p>A version that does not load leaves the current one serving: a bad merge is reported, not deployed.
 */
public final class OrgHost implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OrgHost.class);

    /** How many versions stay loaded, the current one included. */
    public static final int RETAINED = 3;

    private final Path checkout;
    private final Function<Path, AgentHost> loader;
    private final Function<Path, String> versionOf;
    private final LinkedHashMap<String, AgentHost> versions = new LinkedHashMap<>();
    private AgentHost current;

    OrgHost(Path checkout, Function<Path, AgentHost> loader, Function<Path, String> versionOf) {
        this.checkout = Objects.requireNonNull(checkout, "checkout");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.versionOf = Objects.requireNonNull(versionOf, "versionOf");
    }

    /**
     * Opens the repository checked out in {@code checkout}, at the commit it holds.
     *
     * @throws DefinitionException listing every problem with it
     */
    public static OrgHost open(Path checkout, AgentHost.Options options) {
        OrgHost host = new OrgHost(checkout, dir -> AgentHost.open(dir, GitVersion.of(dir), options), GitVersion::of);
        host.reload();
        return host;
    }

    /**
     * Loads the checkout again if it holds a different version, and makes it current.
     *
     * @return the version now current
     * @throws DefinitionException if the new version does not load; the current one goes on serving
     */
    public synchronized String reload() {
        String version = versionOf.apply(checkout);
        if (current != null && current.repo().version().equals(version)) {
            return version;
        }
        AgentHost loaded = loader.apply(checkout);
        if (current != null && !loaded.repo().org().equals(current.repo().org())) {
            loaded.close();
            throw new DefinitionException(List.of(new DefinitionException.Problem("org.yaml", "org",
                    "was " + current.repo().org() + "; an organization's repository cannot become another's")));
        }
        AgentHost replaced = versions.put(loaded.repo().version(), loaded);
        if (replaced != null && replaced != loaded) {
            replaced.close();
        }
        current = loaded;
        List<String> retired = new ArrayList<>();
        while (versions.size() > RETAINED) {
            Map.Entry<String, AgentHost> oldest = versions.entrySet().iterator().next();
            versions.remove(oldest.getKey());
            oldest.getValue().close();
            retired.add(oldest.getKey());
        }
        LOG.info("{} is now at {}{}", loaded.repo().org(), loaded.repo().version(),
                retired.isEmpty() ? "" : "; let go of " + retired);
        return loaded.repo().version();
    }

    public synchronized String org() {
        return current.repo().org();
    }

    /** The version new conversations start on. */
    public synchronized AgentHost current() {
        return current;
    }

    /** A version still loaded. */
    public synchronized Optional<AgentHost> version(String version) {
        return Optional.ofNullable(versions.get(version));
    }

    /** The versions loaded, oldest first. */
    public synchronized List<String> versions() {
        return List.copyOf(versions.keySet());
    }

    @Override
    public synchronized void close() {
        versions.values().forEach(AgentHost::close);
        versions.clear();
    }
}
