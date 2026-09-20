package dev.agentkit.core.deferred;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
 * Deferred actions kept by this process: in memory, or in a directory so a schedule survives a restart. Made by
 * {@link DeferredActionStore#inMemory()} and {@link DeferredActionStore#inDirectory}.
 *
 * <p>On disk each action is one {@code <id>.properties} file, written to a temporary file, flushed to disk and moved
 * into place, so a crash leaves either the old action or the new one and never half of either. Properties rather than
 * JSON because this module takes no dependency to read a file, and {@code java.util.Properties} escapes the
 * multi-line goal and outcome faithfully. A file that cannot be read fails the load, with its name in the message,
 * rather than being skipped: an action that vanished from the schedule is the failure a store like this exists to
 * prevent.
 *
 * <p>An action that was {@link DeferredAction.Status#RUNNING} when the process stopped is scheduled again on load,
 * because nothing records how far it got. The store is for one process: two processes sharing a directory will each
 * run every action.
 */
final class LocalDeferredActionStore implements DeferredActionStore {

    /** Ids become file names, so only what cannot escape the directory is allowed. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,200}");

    private final Path directory;
    private final Map<String, DeferredAction> actions = new LinkedHashMap<>();

    private LocalDeferredActionStore(Path directory) {
        this.directory = directory;
    }

    static LocalDeferredActionStore inMemory() {
        return new LocalDeferredActionStore(null);
    }

    static LocalDeferredActionStore inDirectory(Path directory) {
        Objects.requireNonNull(directory, "directory");
        LocalDeferredActionStore store = new LocalDeferredActionStore(directory);
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

    /**
     * Adds an action, replacing a scheduled or finished one with the same id; returns whether it replaced one.
     *
     * @throws IllegalArgumentException if the id is not a safe file name, or differs only in case from one already
     *                                  held (the same file on a case-insensitive disk)
     * @throws IllegalStateException    if the action it would replace is running
     * @throws UncheckedIOException     if it could not be written; nothing changed
     */
    @Override
    public synchronized boolean put(DeferredAction action) {
        Objects.requireNonNull(action, "action");
        if (!SAFE_ID.matcher(action.id()).matches()) {
            throw new IllegalArgumentException("A deferred action id may use letters, digits, '.', '_' and '-': "
                    + action.id());
        }
        for (String held : actions.keySet()) {
            if (!held.equals(action.id()) && held.equalsIgnoreCase(action.id())) {
                throw new IllegalArgumentException("The deferred action id " + action.id() + " differs only in case from "
                        + held + ", which would share its file");
            }
        }
        DeferredAction existing = actions.get(action.id());
        if (existing != null && existing.status() == DeferredAction.Status.RUNNING) {
            throw new IllegalStateException("The deferred action " + action.id() + " is running and cannot be replaced");
        }
        save(action);
        actions.put(action.id(), action);
        return existing != null;
    }

    /** Every action, earliest first. */
    @Override
    public synchronized List<DeferredAction> all() {
        return actions.values().stream().sorted(Comparator.comparing(DeferredAction::runAt)).toList();
    }

    @Override
    public synchronized Optional<DeferredAction> get(String id) {
        return Optional.ofNullable(id == null ? null : actions.get(id));
    }

    /** Scheduled actions whose time has come, earliest first. */
    @Override
    public synchronized List<DeferredAction> due(Instant now) {
        return actions.values().stream()
                .filter(a -> a.status() == DeferredAction.Status.SCHEDULED && !a.runAt().isAfter(now))
                .sorted(Comparator.comparing(DeferredAction::runAt)).toList();
    }

    /**
     * Claims a scheduled action for running; false if it is no longer scheduled.
     *
     * @throws UncheckedIOException if the claim could not be written; the action is still scheduled
     */
    @Override
    public synchronized boolean claim(String id, Instant now) {
        DeferredAction action = actions.get(id);
        if (action == null || action.status() != DeferredAction.Status.SCHEDULED) {
            return false;
        }
        DeferredAction running = action.withStatus(DeferredAction.Status.RUNNING, "", null);
        save(running);
        actions.put(id, running);
        return true;
    }

    /**
     * Records how a claimed action ended; false, changing nothing, if that action is not running.
     *
     * @throws UncheckedIOException if it could not be written; the action is still running here, and scheduled again
     *                              after a restart
     */
    @Override
    public synchronized boolean finish(String id, boolean succeeded, String outcome, Instant now) {
        DeferredAction action = actions.get(id);
        if (action == null || action.status() != DeferredAction.Status.RUNNING) {
            return false;
        }
        DeferredAction finished = action.withStatus(
                succeeded ? DeferredAction.Status.DONE : DeferredAction.Status.FAILED, outcome, now);
        save(finished);
        actions.put(id, finished);
        return true;
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
        try {
            StringWriter text = new StringWriter();
            p.store(text, "deferred action");
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save deferred action " + action.id(), e);
        }
    }

    private static DeferredAction read(Path file) {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
            String id = p.getProperty("id");
            if (id == null || !SAFE_ID.matcher(id).matches() || !file.getFileName().toString().equals(id + ".properties")) {
                throw new IllegalStateException("its id does not match its file name");
            }
            return new DeferredAction(id, p.getProperty("subjectKind"), p.getProperty("subjectId"),
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
