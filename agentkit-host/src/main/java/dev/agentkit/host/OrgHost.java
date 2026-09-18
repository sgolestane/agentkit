package dev.agentkit.host;

import dev.agentkit.host.repo.DefinitionException;
import dev.agentkit.host.repo.GitVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
 *
 * <p><strong>Across a restart.</strong> Every version made current is noted in a {@link VersionLog}. On opening, the
 * host loads the checkout as usual, then loads again the most recent earlier versions the log names — each from its
 * commit, taken out of the repository's history — so conversations pinned to them go on as before. A version that
 * was never committed (a working tree with changes) cannot be taken out again, and is let go.
 */
public final class OrgHost implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OrgHost.class);

    /** How many versions stay loaded, the current one included. */
    public static final int RETAINED = 3;

    private final Path checkout;
    private final Function<Path, AgentHost> loader;
    private final Function<Path, String> versionOf;
    private final VersionLog log;
    private final Function<String, Optional<Past>> past;
    private final LinkedHashMap<String, AgentHost> versions = new LinkedHashMap<>();
    private final Map<String, Runnable> cleanups = new HashMap<>();
    private AgentHost current;

    /** An earlier version loaded again, and what to do once it is let go. */
    record Past(AgentHost host, Runnable cleanup) {
    }

    OrgHost(Path checkout, Function<Path, AgentHost> loader, Function<Path, String> versionOf) {
        this(checkout, loader, versionOf, VersionLog.inMemory(), version -> Optional.empty());
    }

    /**
     * @param past loads an earlier version again, by its id; empty when it cannot be
     */
    OrgHost(Path checkout, Function<Path, AgentHost> loader, Function<Path, String> versionOf, VersionLog log,
            Function<String, Optional<Past>> past) {
        this.checkout = Objects.requireNonNull(checkout, "checkout");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.versionOf = Objects.requireNonNull(versionOf, "versionOf");
        this.log = Objects.requireNonNull(log, "log");
        this.past = Objects.requireNonNull(past, "past");
    }

    /**
     * Opens the repository checked out in {@code checkout}, at the commit it holds.
     *
     * @throws DefinitionException listing every problem with it
     */
    public static OrgHost open(Path checkout, AgentHost.Options options) {
        return open(checkout, options, VersionLog.inMemory());
    }

    /**
     * Opens the repository checked out in {@code checkout}, and the earlier versions {@code log} says were serving.
     *
     * @throws DefinitionException listing every problem with the checkout's version
     */
    public static OrgHost open(Path checkout, AgentHost.Options options, VersionLog log) {
        OrgHost host = new OrgHost(checkout, dir -> AgentHost.open(dir, GitVersion.of(dir), options), GitVersion::of,
                log, version -> extracted(checkout, version, options));
        host.reload();
        host.restore();
        return host;
    }

    /** {@code version} of the repository, taken out of its history into a directory of its own and loaded. */
    private static Optional<Past> extracted(Path checkout, String version, AgentHost.Options options) {
        if (!GitVersion.isCommit(version)) {
            return Optional.empty();
        }
        Path dir;
        try {
            dir = Files.createTempDirectory("agentkit-version-");
        } catch (IOException e) {
            LOG.warn("Could not make a directory to load {} into", version, e);
            return Optional.empty();
        }
        Runnable cleanup = () -> delete(dir);
        try {
            if (!GitVersion.extract(checkout, version, dir)) {
                LOG.warn("{} is not in the history of {}; conversations pinned to it will be told so", version, checkout);
                cleanup.run();
                return Optional.empty();
            }
            return Optional.of(new Past(AgentHost.open(dir, version, options), cleanup));
        } catch (RuntimeException e) {
            LOG.warn("{} could not be loaded again: {}", version, e.getMessage());
            cleanup.run();
            return Optional.empty();
        }
    }

    private static void delete(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Litter in the temporary directory, at worst.
                }
            });
        } catch (IOException ignored) {
            // As above.
        }
    }

    /**
     * Loads again the earlier versions the log names, most recent first, until {@link #RETAINED} are loaded. They go
     * before the current one: they are older.
     */
    synchronized void restore() {
        String org = current.repo().org();
        List<String> wanted = new ArrayList<>();
        for (String version : log.recent(org, RETAINED + 1)) {
            if (!versions.containsKey(version) && versions.size() + wanted.size() < RETAINED) {
                wanted.add(version);
            }
        }
        Collections.reverse(wanted);
        LinkedHashMap<String, AgentHost> rebuilt = new LinkedHashMap<>();
        for (String version : wanted) {
            past.apply(version).ifPresent(loaded -> {
                if (loaded.host().repo().org().equals(org)) {
                    rebuilt.put(version, loaded.host());
                    cleanups.put(version, loaded.cleanup());
                } else {
                    loaded.host().close();
                    loaded.cleanup().run();
                }
            });
        }
        if (!rebuilt.isEmpty()) {
            rebuilt.putAll(versions);
            versions.clear();
            versions.putAll(rebuilt);
            LOG.info("{} serves {} again, from before the restart", org, List.copyOf(rebuilt.keySet()).subList(0,
                    rebuilt.size() - 1));
        }
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
        log.loaded(loaded.repo().org(), loaded.repo().version(), Instant.now());
        List<String> retired = new ArrayList<>();
        while (versions.size() > RETAINED) {
            Map.Entry<String, AgentHost> oldest = versions.entrySet().iterator().next();
            versions.remove(oldest.getKey());
            oldest.getValue().close();
            Optional.ofNullable(cleanups.remove(oldest.getKey())).ifPresent(Runnable::run);
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
        cleanups.values().forEach(Runnable::run);
        cleanups.clear();
    }
}
