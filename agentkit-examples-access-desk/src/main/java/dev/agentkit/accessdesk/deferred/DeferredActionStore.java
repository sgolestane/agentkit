package dev.agentkit.accessdesk.deferred;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where deferred actions are kept: in a JSON file, rewritten atomically on every change, so a schedule
 * survives a restart. With no file it is kept in memory.
 *
 * <p>An action that was {@link DeferredAction.Status#RUNNING} when the process stopped is scheduled again
 * on load: it may not have finished, and the actions this store holds are written to be safe to run
 * twice (revoking a revoked grant does nothing; a reminder may be sent twice).
 */
public final class DeferredActionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path file;
    private final Map<String, DeferredAction> actions = new LinkedHashMap<>();

    private DeferredActionStore(Path file) {
        this.file = file;
    }

    /** A store kept in {@code file}, loading what it already holds; null keeps it in memory. */
    public static DeferredActionStore open(Path file) {
        DeferredActionStore store = new DeferredActionStore(file);
        if (file != null && Files.exists(file)) {
            try {
                List<DeferredAction> loaded = MAPPER.readValue(file.toFile(), new TypeReference<>() {
                });
                for (DeferredAction action : loaded) {
                    store.actions.put(action.id(), action.status() == DeferredAction.Status.RUNNING
                            ? action.withStatus(DeferredAction.Status.SCHEDULED, "", null) : action);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read deferred actions from " + file, e);
            }
        }
        return store;
    }

    /** Adds an action, replacing one with the same id; returns whether it replaced one. */
    public synchronized boolean put(DeferredAction action) {
        boolean replaced = actions.put(action.id(), action) != null;
        save();
        return replaced;
    }

    public synchronized List<DeferredAction> all() {
        return actions.values().stream().sorted(Comparator.comparing(DeferredAction::runAt)).toList();
    }

    public synchronized Optional<DeferredAction> get(String id) {
        return Optional.ofNullable(actions.get(id));
    }

    /** Scheduled actions whose time has come, oldest first. */
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
        actions.put(id, action.withStatus(DeferredAction.Status.RUNNING, "", null));
        save();
        return true;
    }

    /** Records how a claimed action ended. */
    public synchronized void finish(String id, boolean succeeded, String outcome, Instant now) {
        DeferredAction action = actions.get(id);
        if (action != null) {
            actions.put(id, action.withStatus(succeeded ? DeferredAction.Status.DONE : DeferredAction.Status.FAILED,
                    outcome, now));
            save();
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), List.copyOf(actions.values()));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save deferred actions to " + file, e);
        }
    }
}
