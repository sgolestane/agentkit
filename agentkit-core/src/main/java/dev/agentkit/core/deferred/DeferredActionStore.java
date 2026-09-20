package dev.agentkit.core.deferred;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where deferred actions are kept: in memory ({@link #inMemory()}), in a directory ({@link #inDirectory}) so a
 * schedule survives a restart, or in a database an application provides.
 *
 * <p>What every store promises:
 * <ul>
 *   <li><strong>Written before visible.</strong> If a change cannot be kept, the call throws and the store still holds
 *       what it held before.</li>
 *   <li><strong>One claim wins.</strong> {@link #claim} succeeds for one caller only, so an action runs once per claim;
 *       a store shared by several processes must make the claim atomic across them.</li>
 *   <li><strong>At least once, not exactly once.</strong> An action whose run was cut short — the process stopped
 *       while it was {@link DeferredAction.Status#RUNNING} — is run again, because nothing records how far it got.
 *       Write goals whose effects are safe to repeat (revoking what is already revoked, a second reminder).</li>
 * </ul>
 */
public interface DeferredActionStore {

    /** A store that forgets everything when the process ends. */
    static DeferredActionStore inMemory() {
        return LocalDeferredActionStore.inMemory();
    }

    /**
     * A store kept in {@code directory}, created if missing, loading every action already there. For one process: two
     * processes sharing a directory will each run every action.
     */
    static DeferredActionStore inDirectory(Path directory) {
        return LocalDeferredActionStore.inDirectory(directory);
    }

    /**
     * Adds an action, replacing a scheduled or finished one with the same id; returns whether it replaced one.
     *
     * @throws IllegalArgumentException if the id is not one the store can keep
     * @throws IllegalStateException    if the action it would replace is running
     */
    boolean put(DeferredAction action);

    /** Every action, earliest first. */
    List<DeferredAction> all();

    Optional<DeferredAction> get(String id);

    /** Actions whose time has come and that may be claimed, earliest first. */
    List<DeferredAction> due(Instant now);

    /** Claims a due action for running; false if someone else has it, or it is no longer scheduled. */
    boolean claim(String id, Instant now);

    /** Records how a claimed action ended; false, changing nothing, if that action is not running. */
    boolean finish(String id, boolean succeeded, String outcome, Instant now);
}
