package dev.agentkit.core.deferred;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Where deferred actions are kept: in memory ({@link #inMemory()}), or in a directory ({@link #inDirectory}) so a
 * schedule survives a restart.
 *
 * <p>On disk each action is one {@code <id>.properties} file, written to a temporary file and moved into place, so a
 * crash leaves either the old action or the new one and never half of either. Properties rather than JSON because
 * this module takes no dependency to read a file, and {@code java.util.Properties} escapes the multi-line goal and
 * outcome faithfully. A file that cannot be read is skipped with its name in the exception's message rather than
 * silently: an action that vanished from the schedule is the failure a store like this exists to prevent.
 *
 * <p>An action that was {@link DeferredAction.Status#RUNNING} when the process stopped is scheduled again on load: it
 * may not have finished, so actions should be written to be safe to run twice.
 */
public final class DeferredActionStore {

    /** Ids become file names, so only what cannot escape the directory is allowed. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,200}");

    private final Path directory;
    private final Map<String, DeferredAction> actions = new LinkedHashMap<>();

    private DeferredActionStore(Path directory) {
        this.directory = directory;
    }

    /** A store that forgets everything when the process ends. */
    public static DeferredActionStore inMemory() {
        return new DeferredActionStore(null);
    }

    /** A store kept in {@code directory}, created if missing, loading every action already there. */
    public static DeferredActionStore inDirectory(Path directory) {
        Objects.requireNonNull(directory, "directory");
        DeferredActionStore store = new DeferredActionStore(directory);
        try {
            Files.createDirectories(directory);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.properties")) {
                for (Path file : files) {
                    DeferredAction action = read(file);
                    store.actions.put(action.id(), action.status() == DeferredAction.Status.RUNNING
                            ? action.withStatus(DeferredAction.Status.SCHEDULED, "", null) : action);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read deferred actions from " + directory, e);
        }
        return store;
    }

    /** Adds an action, replacing one with the same id; returns whether it replaced one. */
    public synchronized boolean put(DeferredAction action) {
        Objects.requireNonNull(action, "action");
        if (!SAFE_ID.matcher(action.id()).matches()) {
            throw new IllegalArgumentException("A deferred action id may use letters, digits, '.', '_' and '-': "
                    + action.id());
        }
        boolean replaced = actions.put(action.id(), action) != null;
        save(action);
        return replaced;
    }

    /** Every action, earliest first. */
    public synchronized List<DeferredAction> all() {
        return actions.values().stream().sorted(Comparator.comparing(DeferredAction::runAt)).toList();
    }

    public synchronized Optional<DeferredAction> get(String id) {
        return Optional.ofNullable(id == null ? null : actions.get(id));
    }

    /** Scheduled actions whose time has come, earliest first. */
    public synchronized List<DeferredAction> due(Instant now) {
        return actions.values().stream()
                .filter(a -> a.status() == DeferredAction.Status.SCHEDULED && !a.runAt().isAfter(now))
                .sorted(Comparator.comparing(DeferredAction::runAt)).toList();
    }

    /** Claims a scheduled action for running; false if it is no longer scheduled. */
    public synchronized boolean claim(String id, Instant now) {
        DeferredAction action = actions.get(id);
        if (action == null || action.status() != DeferredAction.Status.SCHEDULED) {
            return false;
        }
        DeferredAction running = action.withStatus(DeferredAction.Status.RUNNING, "", null);
        actions.put(id, running);
        save(running);
        return true;
    }

    /** Records how a claimed action ended. */
    public synchronized void finish(String id, boolean succeeded, String outcome, Instant now) {
        DeferredAction action = actions.get(id);
        if (action != null) {
            DeferredAction finished = action.withStatus(
                    succeeded ? DeferredAction.Status.DONE : DeferredAction.Status.FAILED, outcome, now);
            actions.put(id, finished);
            save(finished);
        }
    }

    private void save(DeferredAction action) {
        if (directory == null) {
            return;
        }
        Properties p = new Properties();
        p.setProperty("id", action.id());
        p.setProperty("subjectKind", action.subjectKind());
        p.setProperty("subjectId", action.subjectId());
        p.setProperty("runAt", action.runAt().toString());
        p.setProperty("when", action.when());
        p.setProperty("goal", action.goal());
        if (action.scheduledAt() != null) {
            p.setProperty("scheduledAt", action.scheduledAt().toString());
        }
        p.setProperty("scheduledBy", action.scheduledBy());
        p.setProperty("status", action.status().name());
        p.setProperty("outcome", action.outcome());
        if (action.finishedAt() != null) {
            p.setProperty("finishedAt", action.finishedAt().toString());
        }
        Path file = directory.resolve(action.id() + ".properties");
        Path temp = directory.resolve(action.id() + ".properties.tmp");
        try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            p.store(out, "deferred action");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save deferred action " + action.id(), e);
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save deferred action " + action.id(), e);
        }
    }

    private static DeferredAction read(Path file) {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
            return new DeferredAction(p.getProperty("id"), p.getProperty("subjectKind"), p.getProperty("subjectId"),
                    Instant.parse(p.getProperty("runAt")), p.getProperty("when"), p.getProperty("goal"),
                    instant(p.getProperty("scheduledAt")), p.getProperty("scheduledBy"),
                    DeferredAction.Status.valueOf(p.getProperty("status", "SCHEDULED")), p.getProperty("outcome"),
                    instant(p.getProperty("finishedAt")));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not read the deferred action in " + file.getFileName() + ": "
                    + e.getMessage(), e);
        }
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
